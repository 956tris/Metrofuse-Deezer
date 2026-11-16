package dev.metrofuse.deezer

import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal const val STRIPE_BYTES = 2048
internal const val MAX_MEDIA_BYTES = 2L * 1024 * 1024 * 1024
private const val MAX_BUFFER_CACHE_BYTES = 256L * 1024 * 1024

internal class DeezerStripe(trackId: String, private val encrypted: Boolean = true) {
    private val cipher = Cipher.getInstance("Blowfish/CBC/NoPadding")

    init {
        val hash = MessageDigest.getInstance(
            "MD5"
        ).digest(trackId.toByteArray(Charsets.US_ASCII)).joinToString("") {
            "%02x".format(it)
        }
        val secret = "g4el58wc0zvf9na1"
        val key =
            ByteArray(16) { (hash[it].code xor hash[it + 16].code xor secret[it].code).toByte() }
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "Blowfish"),
            IvParameterSpec(
                ByteArray(8) {
                    it.toByte()
                }
            )
        )
        key.fill(0)
    }

    fun decode(block: ByteArray, count: Int, absoluteOffset: Long): ByteArray {
        require(
            absoluteOffset >= 0 && absoluteOffset % STRIPE_BYTES == 0L && count in 1..STRIPE_BYTES
        )
        return if (encrypted && count == STRIPE_BYTES &&
            absoluteOffset / STRIPE_BYTES % 3 == 0L
        ) {
            cipher.doFinal(block, 0, count)
        } else {
            block.copyOf(count)
        }
    }
}

internal data class ByteRange(val start: Long, val end: Long) {
    val length: Long get() = end - start + 1
    fun aligned(total: Long): ByteRange = ByteRange(
        start / STRIPE_BYTES * STRIPE_BYTES,
        minOf(((end / STRIPE_BYTES) + 1) * STRIPE_BYTES - 1, total - 1)
    )

    companion object {
        fun parse(header: String?, total: Long): ByteRange {
            require(total in 1..MAX_MEDIA_BYTES)
            if (header == null) return ByteRange(0, total - 1)
            val match =
                Regex("bytes=([0-9]*)-([0-9]*)").matchEntire(header.trim())
                    ?: throw DeezerFailure("INVALID_RANGE")
            val first = match.groupValues[1]
            val last = match.groupValues[2]
            val start: Long
            val end: Long
            if (first.isEmpty()) {
                val suffix =
                    last.toLongOrNull()?.takeIf { it > 0 } ?: throw DeezerFailure("INVALID_RANGE")
                start = (total - suffix).coerceAtLeast(0)
                end = total - 1
            } else {
                start = first.toLongOrNull() ?: throw DeezerFailure("INVALID_RANGE")
                end =
                    if (last.isEmpty()) {
                        total - 1
                    } else {
                        (
                            last.toLongOrNull()
                                ?: throw DeezerFailure("INVALID_RANGE")
                            ).coerceAtMost(total - 1)
                    }
            }
            if (start < 0 || start >= total || end < start) throw DeezerFailure("INVALID_RANGE")
            return ByteRange(start, end)
        }
    }
}

internal interface MediaResponse : AutoCloseable {
    val status: Int
    val length: Long?
    val contentRange: String?
    val body: InputStream
}

internal fun interface MediaOpener {
    fun open(media: DeezerMedia, uri: URI, method: String, range: ByteRange?): MediaResponse
}

internal class DeezerCdn :
    MediaOpener,
    AutoCloseable {
    private val connections = Collections.newSetFromMap(
        ConcurrentHashMap<HttpURLConnection, Boolean>()
    )

    @Volatile
    private var closed = false

    override fun open(
        media: DeezerMedia,
        uri: URI,
        method: String,
        range: ByteRange?
    ): MediaResponse {
        if (closed) throw DeezerFailure("PLUGIN_STOPPED")
        var target = uri
        for (redirect in 0..3) {
            validateTarget(media, target)
            val connection = target.toURL().openConnection() as HttpURLConnection
            connections += connection
            try {
                connection.instanceFollowRedirects = false
                connection.requestMethod = method
                connection.connectTimeout = 5000
                connection.readTimeout = 10_000
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("Referer", "https://www.deezer.com/")
                range?.let { connection.setRequestProperty("Range", "bytes=${it.start}-${it.end}") }
                val status = connection.responseCode
                if (closed) throw DeezerFailure("PLUGIN_STOPPED")
                if (status in setOf(301, 302, 303, 307, 308)) {
                    target =
                        target.resolve(
                            connection.getHeaderField("Location")
                                ?: throw DeezerFailure("MEDIA_REDIRECT_FAILED")
                        )
                    connections -= connection
                    connection.disconnect()
                    continue
                }
                val encoding = connection.getHeaderField("Content-Encoding")
                if (!encoding.isNullOrBlank() &&
                    !encoding.equals("identity", true)
                ) {
                    throw DeezerFailure("ENCODED_MEDIA_UNSUPPORTED")
                }
                return object : MediaResponse {
                    override val status = status
                    override val length = connection.getHeaderField(
                        "Content-Length"
                    )?.toLongOrNull()
                    override val contentRange = connection.getHeaderField("Content-Range")
                    override val body: InputStream get() = connection.inputStream
                    override fun close() {
                        connections -= connection
                        connection.disconnect()
                    }
                }
            } catch (failure: Throwable) {
                connections -= connection
                connection.disconnect()
                throw failure
            }
        }
        throw DeezerFailure("TOO_MANY_MEDIA_REDIRECTS")
    }

    private fun validateTarget(media: DeezerMedia, uri: URI) {
        val direct =
            uri.scheme == "https" && uri.userInfo == null &&
                (
                    uri.host.equals("dzcdn.net", true) ||
                        uri.host?.endsWith(".dzcdn.net", true) == true
                    )
        val proxied =
            media.server?.let {
                uri.scheme == it.scheme && uri.authority == it.authority &&
                    uri.path == it.path.trimEnd('/') + "/deezer-media-proxy"
            } ==
                true
        if (!direct && !proxied) throw DeezerFailure("UNSAFE_MEDIA_URL")
    }

    fun cancelRequests() {
        connections.toList().forEach { it.disconnect() }
        connections.clear()
    }

    override fun close() {
        closed = true
        cancelRequests()
    }
}

internal data class FlacInfo(val sampleRate: Int, val bitDepth: Int, val channels: Int, val totalSamples: Long) {
    val durationSeconds: Long? get() = totalSamples.takeIf { it > 0 }?.div(sampleRate)
}

internal data class MediaLocation(val uri: URI, val length: Long, val flac: FlacInfo? = null)

internal fun parseFlacInfo(bytes: ByteArray): FlacInfo {
    var offset = 0
    if (bytes.size >= 10 && bytes.copyOfRange(0, 3).contentEquals(byteArrayOf(73, 68, 51))) {
        val sizeBytes = bytes.copyOfRange(6, 10)
        if (sizeBytes.any { it.toInt() and 0x80 != 0 }) throw DeezerFailure("INVALID_FLAC_HEADER")
        offset = 10 + sizeBytes.fold(0) { size, byte -> (size shl 7) or byte.toInt() }
        if (bytes[3].toInt() == 4 && bytes[5].toInt() and 0x10 != 0) offset += 10
    }
    if (offset < 0 || offset + 42 > bytes.size ||
        !bytes.copyOfRange(offset, offset + 4).contentEquals(byteArrayOf(102, 76, 97, 67)) ||
        bytes[offset + 4].toInt() and 0x7f != 0 ||
        bytes[offset + 5].toInt() != 0 || bytes[offset + 6].toInt() != 0 || bytes[offset + 7].toInt() != 34
    ) {
        throw DeezerFailure("INVALID_FLAC_HEADER")
    }
    fun byte(index: Int) = bytes[offset + index].toInt() and 0xff
    val sampleRate = (byte(18) shl 12) or (byte(19) shl 4) or (byte(20) shr 4)
    val channels = ((byte(20) shr 1) and 7) + 1
    val depth = ((byte(20) and 1) shl 4 or (byte(21) shr 4)) + 1
    val samples = ((byte(21).toLong() and 15L) shl 32) or (byte(22).toLong() shl 24) or
        (byte(23).toLong() shl 16) or (byte(24).toLong() shl 8) or byte(25).toLong()
    if (sampleRate !in 8000..768000 || depth !in 8..32) throw DeezerFailure("INVALID_FLAC_HEADER")
    return FlacInfo(sampleRate, depth, channels, samples)
}

private fun flacLocation(media: DeezerMedia, uri: URI, total: Long, opener: MediaOpener): MediaLocation {
    val wanted = minOf(64L * 1024, total).toInt()
    opener.open(media, uri, "GET", ByteRange(0, wanted - 1L)).use { response ->
        if (response.status == 206) {
            val content = Regex("bytes 0-([0-9]+)/([0-9]+)").matchEntire(response.contentRange.orEmpty())
                ?: throw DeezerFailure("INVALID_CONTENT_RANGE")
            if (content.groupValues[1].toLongOrNull()?.let { it < wanted - 1L || it >= total } != false ||
                content.groupValues[2].toLongOrNull() != total
            ) throw DeezerFailure("INVALID_CONTENT_RANGE")
        } else if (response.status != 200 || response.length?.let { it != total } == true) {
            throw DeezerFailure("MEDIA_UNREACHABLE", retryable = true)
        }
        val decoder = DeezerStripe(media.trackId, media.cipher == "BF_CBC_STRIPE")
        val plain = ByteArray(wanted)
        val block = ByteArray(STRIPE_BYTES)
        response.body.use { input ->
            var offset = 0
            while (offset < wanted) {
                val count = minOf(STRIPE_BYTES, wanted - offset)
                if (readBlock(input, block, count) != count) throw DeezerFailure("MEDIA_TRUNCATED", retryable = true)
                decoder.decode(block, count, offset.toLong()).copyInto(plain, offset)
                offset += count
            }
        }
        return MediaLocation(uri, total, parseFlacInfo(plain))
    }
}

internal fun probeMedia(media: DeezerMedia, opener: MediaOpener): MediaLocation {
    var lastFailure: DeezerFailure? = null
    for (uri in media.sources) {
        try {
            opener.open(media, uri, "HEAD", null).use { response ->
                val size = response.length
                if (response.status in 200..299 && size != null &&
                    size in 1..MAX_MEDIA_BYTES && (media.contentLength == null || media.contentLength == size)
                ) {
                    return if (media.lossless) flacLocation(media, uri, size, opener) else MediaLocation(uri, size)
                }
            }
            opener.open(media, uri, "GET", ByteRange(0, 0)).use { response ->
                val size = response.contentRange?.substringAfterLast('/')?.toLongOrNull()
                if (response.status == 206 && size != null &&
                    size in 1..MAX_MEDIA_BYTES && (media.contentLength == null || media.contentLength == size)
                ) {
                    return if (media.lossless) flacLocation(media, uri, size, opener) else MediaLocation(uri, size)
                }
            }
        } catch (failure: DeezerFailure) {
            lastFailure = failure
        } catch (_: Exception) {
            // A resolver may return several CDNs. Try the next source without exposing signed URLs.
        }
    }
    throw lastFailure ?: DeezerFailure("MEDIA_UNREACHABLE", retryable = true)
}

internal fun readBlock(input: InputStream, buffer: ByteArray, wanted: Int): Int {
    var count = 0
    while (count < wanted) {
        val size = input.read(buffer, count, wanted - count)
        if (size == -1) break
        if (size == 0) continue
        count += size
    }
    return count
}

internal class DeezerBuffer(private val opener: MediaOpener) : AutoCloseable {
    private val lock = Mutex()
    private val directory = lazy {
        val root =
            System.getProperty("java.io.tmpdir")?.let(::File)?.takeIf {
                it.isDirectory &&
                    it.canWrite()
            }
                ?: throw DeezerFailure("TEMP_STORAGE_UNAVAILABLE")
        File.createTempFile("metrofuse-deezer-", ".cache", root).also { file ->
            if (!file.delete() || !file.mkdir()) throw DeezerFailure("TEMP_STORAGE_UNAVAILABLE")
        }
    }
    private val files = linkedMapOf<String, File>()

    @Volatile
    private var revision = 0L

    @Volatile
    private var closed = false

    suspend fun resolve(media: DeezerMedia, location: MediaLocation): File = lock.withLock {
        check(!closed)
        val generation = revision
        val key = "${media.trackId}:${media.format}"
        synchronized(files) {
            files[key]?.takeIf { it.isFile && it.length() == location.length }
        }?.let { return@withLock it }
        val file = withContext(Dispatchers.IO) {
            File.createTempFile(
                "track-",
                if (media.lossless) {
                    ".flac"
                } else {
                    ".mp3"
                },
                directory.value
            )
        }
        try {
            withContext(Dispatchers.IO) {
                opener.open(media, location.uri, "GET", null).use { response ->
                    if (response.status != 200 ||
                        response.length?.let { it != location.length } == true
                    ) {
                        throw DeezerFailure("MEDIA_CHANGED", retryable = true)
                    }
                    val decoder = DeezerStripe(media.trackId, media.cipher == "BF_CBC_STRIPE")
                    val block = ByteArray(STRIPE_BYTES)
                    response.body.use { input ->
                        file.outputStream().use { output ->
                            var offset = 0L
                            while (offset < location.length) {
                                currentCoroutineContext().ensureActive()
                                if (closed ||
                                    generation != revision
                                ) {
                                    throw DeezerFailure("SESSION_CHANGED")
                                }
                                val wanted = minOf(
                                    STRIPE_BYTES.toLong(),
                                    location.length - offset
                                ).toInt()
                                if (readBlock(input, block, wanted) !=
                                    wanted
                                ) {
                                    throw DeezerFailure("MEDIA_TRUNCATED", retryable = true)
                                }
                                output.write(decoder.decode(block, wanted, offset))
                                offset += wanted
                            }
                        }
                    }
                }
            }
            synchronized(files) {
                if (closed || generation != revision) throw DeezerFailure("SESSION_CHANGED")
                files[key] = file
                while (files.size > 4 ||
                    (files.values.sumOf(File::length) > MAX_BUFFER_CACHE_BYTES && files.size > 1)
                ) {
                    val entry = files.entries.first()
                    entry.value.delete()
                    files.remove(entry.key)
                }
            }
            file
        } catch (failure: Throwable) {
            file.delete()
            if (closed && directory.isInitialized()) directory.value.delete()
            throw failure
        }
    }

    fun clear() = synchronized(files) {
        ++revision
        files.values.forEach { it.delete() }
        files.clear()
    }

    override fun close() {
        closed = true
        clear()
        if (directory.isInitialized()) directory.value.delete()
    }
}
