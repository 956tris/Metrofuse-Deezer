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
    val root =
        runCatching { URI(value.trim().trimEnd('/')) }.getOrNull()
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
    if (request.quality.lossless) return listOf("FLAC")
    if (request.quality.preset == QualityPreset.LOW ||
        request.quality.maxBitrate?.let { it <= 128_000 } == true
    ) {
        return listOf("MP3_128")
    }
    return when (settings.quality) {
        "FLAC" -> listOf("FLAC", "MP3_320", "MP3_128")

        "MP3_128" -> listOf("MP3_128")

        "MP3_320" -> listOf("MP3_320", "MP3_128")

        "AUTO" -> if (request.quality.preferredCodecs.any {
                it.equals("flac", true)
            }
        ) {
            listOf("FLAC", "MP3_320", "MP3_128")
        } else {
            listOf("MP3_320", "MP3_128")
        }

        else -> throw DeezerFailure("INVALID_QUALITY")
    }
}

internal class DeezerMedia(
    val trackId: String,
    val format: String,
    val cipher: String,
    val sources: List<URI>,
    val expiresAt: Long,
    val server: URI? = null
) {
    val mimeType: String get() = if (format == "FLAC") "audio/flac" else "audio/mpeg"
    val codec: String get() = if (format == "FLAC") "flac" else "mp3"
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
        if (candidates.isEmpty()) throw DeezerFailure("MEDIA_UNAVAILABLE")
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
                val sources = entry.list("sources").map { source ->
                    val uri =
                        source.text("url")?.let(::httpsUri)
                            ?: throw DeezerFailure("UNSAFE_MEDIA_URL")
                    if (!uri.host.equals("dzcdn.net", true) &&
                        !uri.host.endsWith(".dzcdn.net", true)
                    ) {
                        throw DeezerFailure("UNSAFE_MEDIA_URL")
                    }
                    if (server ==
                        null
                    ) {
                        uri
                    } else {
                        URI(
                            server.toString() + "/deezer-media-proxy?url=" +
                                uri.toASCIIString().encodeURLParameter()
                        )
                    }
                }
                if (sources.isEmpty()) continue
                val expiries =
                    listOfNotNull(
                        epochMillis(entry.text("expiration", "expires_at", "expiry")),
                        epochMillis(data.text("expiration", "expires_at"))
                    )
                val expiresAt = minOf(expiries.minOrNull() ?: (now + 5 * 60_000), now + 30 * 60_000)
                if (expiresAt <= now + 5000) throw DeezerFailure("MEDIA_EXPIRED", retryable = true)
                return DeezerMedia(trackId, format, cipher, sources, expiresAt, server)
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
    ): DeezerMedia = when (settings.mode) {
        "ACCOUNT" -> accounts.withSession { session ->
            val track =
                session.call(
                    "song.getData",
                    buildJsonObject {
                        put("SNG_ID", id)
                    }
                ) as? JsonObject
                    ?: throw DeezerFailure("INVALID_RESPONSE")
            val token = track.text("TRACK_TOKEN") ?: throw DeezerFailure("TRACK_UNAVAILABLE")
            try {
                DeezerMediaParser.parse(
                    session.fullMedia(token, formats, forceRefresh),
                    id,
                    formats
                )
            } catch (failure: DeezerFailure) {
                if (failure.code !in
                    setOf("MEDIA_UNAVAILABLE", "MEDIA_EXPIRED", "ACCESS_DENIED")
                ) {
                    throw failure
                }
                val fallbackId = deezerId(track.obj("FALLBACK").text("SNG_ID"))
                val fallback = fallbackId?.let {
                    session.call(
                        "song.getData",
                        buildJsonObject {
                            put("SNG_ID", it)
                        }
                    ) as? JsonObject
                }
                val retryId = fallbackId ?: id
                val retryToken = fallback?.text("TRACK_TOKEN") ?: token
                DeezerMediaParser.parse(
                    session.fullMedia(retryToken, formats, forceRefresh = true),
                    retryId,
                    formats
                )
            }
        }

        "DZMEDIA" -> {
            if (id.toLong() > 4_294_967_295L) throw DeezerFailure("DZMEDIA_ID_UNSUPPORTED")
            val server = dzmediaServer(settings.server)
            val body = buildJsonObject {
                put("ids", JsonArray(listOf(JsonPrimitive(id.toLong()))))
                put("formats", JsonArray(formats.map(::JsonPrimitive)))
                put("fast", false)
            }
            val root = http.post(server.toString() + "/get_url") {
                header("User-Agent", USER_AGENT)
                header("Content-Type", "application/json")
                setBody(body.toString())
            }.jsonValue() as? JsonObject ?: throw DeezerFailure("INVALID_MEDIA_RESPONSE")
            DeezerMediaParser.parse(root, id, formats, if (settings.proxy) server else null)
        }

        else -> throw DeezerFailure("INVALID_STREAMING_MODE")
    }
}
