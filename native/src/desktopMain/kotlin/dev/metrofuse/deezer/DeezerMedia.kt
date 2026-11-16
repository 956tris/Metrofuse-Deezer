package dev.metrofuse.deezer

import dev.metrofuse.api.AudioQuery
import dev.metrofuse.api.QualityPreset
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.encodeURLParameter
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal data class StreamingSettings(
    val enabled: Boolean,
    val mode: String,
    val server: String,
    val proxy: Boolean,
    val quality: String
) {
    companion object {
        fun from(values: Map<String, JsonElement>) = StreamingSettings(
            enabled = (values["audioEnabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
            mode = (values["streamingMode"] as? JsonPrimitive)?.contentOrNull ?: "ACCOUNT",
            server = (values["dzmediaUrl"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            proxy = (values["dzmediaProxy"] as? JsonPrimitive)?.booleanOrNull ?: false,
            quality = (values["audioQuality"] as? JsonPrimitive)?.contentOrNull ?: "AUTO"
        )
    }

    override fun toString() = "StreamingSettings(mode=$mode, quality=$quality)"
}

internal fun dzmediaServer(value: String): URI {
    if (value.isBlank()) throw DeezerFailure("DZMEDIA_URL_REQUIRED")
    val address = value.trim().trimEnd('/')
    val absolute = if ("://" in address) address else "https://$address"
    val root =
        runCatching { URI(absolute).normalize() }.getOrNull()
            ?: throw DeezerFailure("INVALID_DZMEDIA_URL")
    if (root.host.isNullOrBlank() || root.userInfo != null || root.rawQuery != null ||
        root.fragment != null ||
        root.port == 0 ||
        root.port > 65535 ||
        (root.scheme != "https" && !(root.scheme == "http" && root.host == "127.0.0.1"))
    ) {
        throw DeezerFailure("INVALID_DZMEDIA_URL")
    }
    if (root.path.endsWith("/get_url")) return URI(root.toString().removeSuffix("/get_url"))
    return root
}

internal fun mediaFormats(request: AudioQuery, settings: StreamingSettings): List<String> {
    if (request.quality.lossless) return if (settings.quality == "FLAC") listOf("FLAC") else listOf("FLAC_24", "FLAC")
    if (request.quality.preset == QualityPreset.LOW ||
        request.quality.maxBitrate?.let { it <= 128_000 } == true
    ) {
        return listOf("MP3_128")
    }
    if (settings.quality == "MP3_128") return listOf("MP3_128")
    if (request.quality.maxBitrate?.let { it <= 320_000 } == true) {
        return if (request.quality.maxBitrate!! < 320_000) listOf("MP3_128") else listOf("MP3_320", "MP3_128")
    }
    return when (settings.quality) {
        "FLAC_24" -> listOf("FLAC_24", "FLAC", "MP3_320", "MP3_128")

        "FLAC" -> listOf("FLAC", "MP3_320", "MP3_128")

        "MP3_128" -> listOf("MP3_128")

        "MP3_320" -> listOf("MP3_320", "MP3_128")

        "AUTO" -> listOf("FLAC_24", "FLAC", "MP3_320", "MP3_128")

        else -> throw DeezerFailure("INVALID_QUALITY")
    }
}

internal class DeezerMedia(
    val trackId: String,
    val format: String,
    val cipher: String,
    val sources: List<URI>,
    val expiresAt: Long,
    val server: URI? = null,
    val contentLength: Long? = null
) {
    val lossless: Boolean get() = format == "FLAC" || format == "FLAC_24"
    val mimeType: String get() = if (lossless) "audio/flac" else "audio/mpeg"
    val codec: String get() = if (lossless) "flac" else "mp3"
    val bitrate: Int? get() = when (format) {
        "MP3_128" -> 128_000
        "MP3_320" -> 320_000
        else -> null
    }

    override fun toString() = "DeezerMedia(format=$format, cipher=$cipher, sources=[redacted])"
}

internal object DeezerMediaParser {
    fun parse(
        root: JsonObject,
        trackId: String,
        requested: List<String>,
        server: URI? = null,
        now: Long = System.currentTimeMillis()
    ): DeezerMedia {
        val data =
            (root["data"] as? JsonArray)?.singleOrNull() as? JsonObject
                ?: throw DeezerFailure("INVALID_MEDIA_RESPONSE")
        val candidates = data.list("media")
        if (candidates.isEmpty()) throw mediaFailure(data)
        for (format in requested) {
            for (entry in candidates.filter { it.text("format") == format }) {
                if (entry.text("media_type", "type") !=
                    "FULL"
                ) {
                    throw DeezerFailure("FULL_TRACK_REQUIRED")
                }
                val cipher =
                    entry.obj("cipher").text("type") ?: entry.text("cipher")
                        ?: throw DeezerFailure("UNSUPPORTED_CIPHER")
                if (cipher !in
                    setOf("BF_CBC_STRIPE", "NONE")
                ) {
                    throw DeezerFailure("UNSUPPORTED_CIPHER")
                }
                val directSources = entry.list("sources").map { source ->
                    val uri =
                        source.text("url")?.let(::httpsUri)
                            ?: throw DeezerFailure("UNSAFE_MEDIA_URL")
                    if (!uri.host.equals("dzcdn.net", true) &&
                        !uri.host.endsWith(".dzcdn.net", true)
                    ) {
                        throw DeezerFailure("UNSAFE_MEDIA_URL")
                    }
                    uri
                }.distinct()
                val sources = if (server == null) directSources else {
                    directSources.map { uri ->
                        URI(
                            server.toString() + "/deezer-media-proxy?url=" +
                                uri.toASCIIString().encodeURLParameter()
                        )
                    } + directSources
                }
                if (sources.isEmpty()) continue
                val expiries =
                    listOfNotNull(
                        epochMillis(entry.text("exp", "expiration", "expires_at", "expiry")),
                        epochMillis(data.text("exp", "expiration", "expires_at"))
                    )
                val expiresAt = minOf(expiries.minOrNull() ?: (now + 5 * 60_000), now + 30 * 60_000)
                if (expiresAt <= now + 5000) throw DeezerFailure("MEDIA_EXPIRED", retryable = true)
                val size = entry.text("filesize", "file_size")?.toLongOrNull()
                if (size != null && size !in 1..MAX_MEDIA_BYTES) throw DeezerFailure("INVALID_MEDIA_SIZE")
                return DeezerMedia(trackId, format, cipher, sources, expiresAt, server, size)
            }
        }
        throw DeezerFailure("QUALITY_UNAVAILABLE")
    }

    private fun epochMillis(value: String?): Long? = value?.toDoubleOrNull()?.takeIf {
        it.isFinite() &&
            it > 0 &&
            it < Long.MAX_VALUE / 1000
    }
        ?.let { if (it < 10_000_000_000) (it * 1000).toLong() else it.toLong() }

    private fun mediaFailure(data: JsonObject): DeezerFailure {
        val messages = data.list("errors").mapNotNull { it.text("message", "MESSAGE") }.joinToString(" ").lowercase()
        return when {
            "track token" in messages && ("expired" in messages || "decoding" in messages) -> DeezerFailure("TRACK_TOKEN_EXPIRED", retryable = true)
            "expired" in messages -> DeezerFailure("MEDIA_EXPIRED", retryable = true)
            "license token" in messages && "sufficient rights" in messages -> DeezerFailure("SUBSCRIPTION_REQUIRED")
            else -> DeezerFailure("MEDIA_UNAVAILABLE")
        }
    }
}

internal class DeezerMediaResolver(
    private val http: HttpClient,
    private val accounts: DeezerAccounts
) {
    suspend fun resolve(
        id: String,
        formats: List<String>,
        settings: StreamingSettings,
        forceRefresh: Boolean = false
    ): DeezerMedia = when {
        settings.mode == "ACCOUNT" || (settings.mode == "DZMEDIA" && id.startsWith("-")) -> accounts.withSession { session ->
            val track = accountTrack(session, id)
            try {
                accountMedia(session, track, id, formats, forceRefresh)
            } catch (failure: DeezerFailure) {
                val retryId = when (failure.code) {
                    "MEDIA_EXPIRED", "TRACK_TOKEN_EXPIRED", "ACCESS_DENIED", "SUBSCRIPTION_REQUIRED" -> id
                    "MEDIA_UNAVAILABLE", "TRACK_UNAVAILABLE" -> deezerTrackId(track.obj("FALLBACK").text("SNG_ID")) ?: throw failure
                    else -> throw failure
                }
                accountMedia(session, accountTrack(session, retryId), retryId, formats, forceRefresh = true)
            }
        }

        settings.mode == "DZMEDIA" -> {
            val server = dzmediaServer(settings.server)
            try {
                serverMedia(id, formats, server, settings.proxy)
            } catch (failure: DeezerFailure) {
                if (failure.code !in setOf("MEDIA_UNAVAILABLE", "TRACK_UNAVAILABLE", "NOT_FOUND")) throw failure
                val fallbackId = fallbackTrackId(id) ?: throw failure
                if (fallbackId.startsWith("-")) resolve(fallbackId, formats, settings, forceRefresh = true)
                else serverMedia(fallbackId, formats, server, settings.proxy)
            }
        }

        else -> throw DeezerFailure("INVALID_STREAMING_MODE")
    }

    private suspend fun accountTrack(session: DeezerSession, id: String): JsonObject =
        (session.call("song.getData", buildJsonObject { put("SNG_ID", id) }) as? JsonObject)?.entity()
            ?: throw DeezerFailure("TRACK_UNAVAILABLE")

    private suspend fun accountMedia(
        session: DeezerSession,
        track: JsonObject,
        requestedId: String,
        formats: List<String>,
        forceRefresh: Boolean
    ): DeezerMedia {
        val token = track.text("TRACK_TOKEN") ?: throw DeezerFailure("TRACK_UNAVAILABLE")
        val actualId = deezerTrackId(track.text("SNG_ID")) ?: requestedId
        val uploaded = actualId.startsWith("-") || track.text("FILESIZE_MP3_MISC")?.toLongOrNull()?.let { it > 0 } == true
        val allowedFormats = if (uploaded) {
            if (formats.none { it.startsWith("MP3_") }) throw DeezerFailure("QUALITY_UNAVAILABLE")
            listOf("MP3_MISC")
        } else formats
        var renewLicense = forceRefresh
        return negotiate(allowedFormats) { requested ->
            val renew = renewLicense
            renewLicense = false
            DeezerMediaParser.parse(session.fullMedia(token, requested, renew), actualId, requested)
        }
    }

    private suspend fun serverMedia(id: String, formats: List<String>, server: URI, proxy: Boolean): DeezerMedia {
        if (id.toLong() !in 1..4_294_967_295L) throw DeezerFailure("DZMEDIA_ID_UNSUPPORTED")
        return negotiate(formats) { requested ->
            val body = buildJsonObject {
                put("ids", JsonArray(listOf(JsonPrimitive(id.toLong()))))
                put("formats", JsonArray(requested.map(::JsonPrimitive)))
                put("fast", false)
            }
            val root = http.post(server.toString() + "/get_url") {
                header("User-Agent", USER_AGENT)
                header("Content-Type", "application/json")
                setBody(body.toString())
            }.jsonValue() as? JsonObject ?: throw DeezerFailure("INVALID_MEDIA_RESPONSE")
            DeezerMediaParser.parse(root, id, requested, if (proxy) server else null)
        }
    }

    private suspend fun negotiate(formats: List<String>, request: suspend (List<String>) -> DeezerMedia): DeezerMedia {
        var remaining = formats.distinct()
        if (remaining.isEmpty()) throw DeezerFailure("QUALITY_UNAVAILABLE")
        while (true) {
            try {
                return request(remaining)
            } catch (failure: DeezerFailure) {
                val canFallback = failure.code in setOf("MEDIA_UNAVAILABLE", "QUALITY_UNAVAILABLE", "SUBSCRIPTION_REQUIRED", "FULL_TRACK_REQUIRED") ||
                    (failure.code == "INVALID_REQUEST" && remaining.first() == "FLAC_24")
                if (!canFallback || remaining.size == 1) throw failure
                remaining = remaining.drop(1)
            }
        }
    }

    private suspend fun fallbackTrackId(id: String): String? {
        return try {
            val alternative = if (accounts.current() != null) {
                accounts.withSession { session -> accountTrack(session, id).obj("FALLBACK").text("SNG_ID") }
            } else {
                val track = http.publicGet("/track/$id")
                track.obj("alternative").text("id") ?: track.text("id")?.takeIf { it != id }
            }
            deezerTrackId(alternative)?.takeIf { it != id }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: DeezerFailure) {
            null
        }
    }
}
