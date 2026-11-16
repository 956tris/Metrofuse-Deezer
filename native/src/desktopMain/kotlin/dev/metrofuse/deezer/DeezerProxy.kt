package dev.metrofuse.deezer

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class DeezerProxy(parent: CoroutineScope, private val opener: MediaOpener) : AutoCloseable {
    private val scope =
        CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val random = SecureRandom()
    private val entries = linkedMapOf<String, Entry>()
    private val clients = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    private val permits = Semaphore(4)
    private var listener: ServerSocket? = null

    @Volatile
    private var closed = false

    private class Entry(
        var media: DeezerMedia,
        var location: MediaLocation,
        val refresh: suspend () -> DeezerMedia
    ) {
        val mutex = Mutex()
    }

    suspend fun register(
        media: DeezerMedia,
        location: MediaLocation,
        refresh: suspend () -> DeezerMedia
    ): String = withContext(Dispatchers.IO) {
        synchronized(entries) {
            check(!closed)
            val server = listener ?: ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")).also {
                listener = it
                scope.launch(Dispatchers.IO) { accept(it) }
            }
            val token = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
            entries[token] = Entry(media, location, refresh)
            while (entries.size > 32) entries.remove(entries.keys.first())
            "http://127.0.0.1:${server.localPort}/stream/$token"
        }
    }

    private suspend fun accept(server: ServerSocket) {
        while (scope.isActive && !closed) {
            val client = try {
                server.accept()
            } catch (_: Exception) {
                break
            }
            if (!permits.tryAcquire()) {
                client.use { runCatching { status(it.getOutputStream(), 503) } }
                continue
            }
            clients += client
            scope.launch(Dispatchers.IO) {
                try {
                    client.use { serve(it) }
                } catch (_: CancellationException) {
                    // Scope cancellation closes sockets and aborts the owned stream.
                } catch (_: Exception) {
                    // A player may close a range request as soon as it has buffered enough audio.
                } finally {
                    clients -= client
                    permits.release()
                }
            }
        }
    }

    private suspend fun serve(socket: Socket) {
        socket.soTimeout = 5000
        if (!socket.inetAddress.isLoopbackAddress) return
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val line = readLine(input, 4096) ?: return
        val parts = line.split(' ')
        if (parts.size != 3 || parts[0] !in setOf("GET", "HEAD")) {
            status(output, 405)
            return
        }
        val token = Regex("/stream/([a-f0-9]{64})").matchEntire(parts[1])?.groupValues?.get(1)
        if (token == null) {
            status(output, 404)
            return
        }
        val headers = mutableMapOf<String, String>()
        var bytes = line.length
        while (true) {
            val header = readLine(input, 4096) ?: return
            bytes += header.length
            if (bytes > 16_384 || headers.size >= 40) {
                status(output, 431)
                return
            }
            if (header.isEmpty()) break
            val name = header.substringBefore(':').trim().lowercase()
            if (!header.contains(':') || name in headers) {
                status(output, 400)
                return
            }
            headers[name] = header.substringAfter(':').trim()
        }
        val host = headers["host"]
        if (host != null && host != "127.0.0.1:${socket.localPort}") {
            status(output, 400)
            return
        }
        val entry = synchronized(entries) { entries[token] }
        if (entry == null || closed) {
            status(output, 410)
            return
        }
        var wroteHeaders = false
        try {
            refresh(entry, false)
            val location = entry.location
            val requested = try {
                ByteRange.parse(headers["range"], location.length)
            } catch (
                _: Exception
            ) {
                status(output, 416, mapOf("Content-Range" to "bytes */${location.length}"))
                return
            }
            val rangeHeaders = buildMap {
                put("Content-Type", entry.media.mimeType)
                put("Content-Length", requested.length.toString())
                put("Accept-Ranges", "bytes")
                if (headers["range"] !=
                    null
                ) {
                    put(
                        "Content-Range",
                        "bytes ${requested.start}-${requested.end}/${location.length}"
                    )
                }
            }
            if (parts[0] == "HEAD") {
                status(output, if (headers["range"] == null) 200 else 206, rangeHeaders)
                return
            }
            val aligned = requested.aligned(location.length)
            var response = opener.open(entry.media, location.uri, "GET", aligned)
            if (response.status in setOf(401, 403)) {
                response.close()
                refresh(entry, true)
                response = opener.open(entry.media, entry.location.uri, "GET", aligned)
            }
            response.use { upstream ->
                validateRange(upstream, aligned, location.length)
                status(output, if (headers["range"] == null) 200 else 206, rangeHeaders)
                wroteHeaders = true
                val decoder = DeezerStripe(
                    entry.media.trackId,
                    entry.media.cipher == "BF_CBC_STRIPE"
                )
                val block = ByteArray(STRIPE_BYTES)
                upstream.body.use { body ->
                    var offset = aligned.start
                    while (offset <= aligned.end) {
                        currentCoroutineContext().ensureActive()
                        val wanted = minOf(STRIPE_BYTES.toLong(), aligned.end - offset + 1).toInt()
                        if (readBlock(body, block, wanted) !=
                            wanted
                        ) {
                            throw DeezerFailure("MEDIA_TRUNCATED")
                        }
                        val plain = decoder.decode(block, wanted, offset)
                        val first = (requested.start - offset).coerceIn(0, wanted.toLong()).toInt()
                        val last = (requested.end - offset + 1).coerceIn(0, wanted.toLong()).toInt()
                        if (last > first) output.write(plain, first, last - first)
                        offset += wanted
                    }
                    output.flush()
                }
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            if (!wroteHeaders) status(output, 502)
        }
    }

    private suspend fun refresh(entry: Entry, force: Boolean) = entry.mutex.withLock {
        if (!force && entry.media.expiresAt > System.currentTimeMillis() + 30_000) return@withLock
        val refreshed = entry.refresh()
        val location = withContext(Dispatchers.IO) { probeMedia(refreshed, opener) }
        if (refreshed.trackId != entry.media.trackId || refreshed.format != entry.media.format ||
            location.length != entry.location.length
        ) {
            throw DeezerFailure("MEDIA_CHANGED")
        }
        entry.media = refreshed
        entry.location = location
    }

    private fun validateRange(response: MediaResponse, aligned: ByteRange, total: Long) {
        if (response.status == 200 && aligned.start == 0L && response.length == total) return
        if (response.status != 206) throw DeezerFailure("RANGE_UNSUPPORTED")
        val content =
            Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(response.contentRange.orEmpty())
                ?: throw DeezerFailure("INVALID_CONTENT_RANGE")
        val start = content.groupValues[1].toLongOrNull()
        val end = content.groupValues[2].toLongOrNull()
        val length = content.groupValues[3].toLongOrNull()
        if (start != aligned.start || end == null || end < aligned.end || end >= total ||
            length != total
        ) {
            throw DeezerFailure("INVALID_CONTENT_RANGE")
        }
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
        clients.toList().forEach { runCatching { it.close() } }
    }

    override fun close() {
        closed = true
        synchronized(entries) {
            listener?.close()
            listener = null
        }
        clear()
        scope.cancel()
    }

    private fun readLine(input: InputStream, limit: Int): String? {
        val bytes = ArrayList<Byte>()
        while (bytes.size <= limit) {
            val value = input.read()
            if (value == -1) return null
            if (value ==
                10
            ) {
                return bytes.toByteArray().toString(Charsets.US_ASCII).removeSuffix("\r")
            }
            if (value == 0 || value > 127) return null
            bytes += value.toByte()
        }
        return null
    }

    private fun status(output: OutputStream, code: Int, headers: Map<String, String> = emptyMap()) {
        val reason = when (code) {
            200 -> "OK"
            206 -> "Partial Content"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            410 -> "Gone"
            416 -> "Range Not Satisfiable"
            431 -> "Request Header Fields Too Large"
            503 -> "Service Unavailable"
            else -> "Bad Gateway"
        }
        val text = buildString {
            append("HTTP/1.1 $code $reason\r\nConnection: close\r\nCache-Control: no-store\r\n")
            if ("Content-Length" !in headers) append("Content-Length: 0\r\n")
            headers.forEach { (key, value) -> append("$key: $value\r\n") }
            append("\r\n")
        }
        output.write(text.toByteArray(Charsets.US_ASCII))
        output.flush()
    }
}
