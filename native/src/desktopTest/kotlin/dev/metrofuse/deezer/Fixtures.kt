package dev.metrofuse.deezer

import dev.metrofuse.api.LoginCredentials
import dev.metrofuse.api.PluginSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.HttpRequestData
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import java.io.ByteArrayInputStream
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal val TEST_ARL = "a".repeat(192)
internal val TEST_CREDENTIALS = LoginCredentials(mapOf("cookie" to "arl=$TEST_ARL; sid=synthetic"))
internal val TEST_LABELS = mapOf(
    "flow" to "Flow",
    "chartTracks" to "Tracks",
    "chartAlbums" to "Albums",
    "chartArtists" to "Artists",
    "chartPlaylists" to "Playlists"
)

internal fun objectJson(value: String) = Json.parseToJsonElement(value) as JsonObject

internal fun gatewayResult(body: String) = """{"error":{},"results":$body}"""

internal fun userData(id: Int = 7, name: String = "Fixture Account") = gatewayResult(
    """{"checkForm":"fixture-csrf","USER":{"USER_ID":$id,"BLOG_NAME":"$name","LOVEDTRACKS_ID":"8","OPTIONS":{"license_token":"fixture-license"}}}"""
)

internal fun HttpRequestData.jsonBody() = objectJson((body as OutgoingContent.ByteArrayContent).bytes().toString(Charsets.UTF_8))

internal fun mockHttp(
    arl: String? = null,
    handler: suspend (HttpRequestData) -> String
): HttpClient = HttpClient(
    MockEngine { request ->
        respond(
            handler(request),
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "application/json")
        )
    }
) {
    install(HttpCookies) {
        storage = AcceptAllCookiesStorage()
        if (arl !=
            null
        ) {
            default {
                addCookie(
                    Url("https://www.deezer.com/"),
                    Cookie("arl", arl, domain = ".deezer.com", path = "/", secure = true)
                )
            }
        }
    }
}

internal class TestSettings(values: Map<String, JsonElement> = emptyMap()) : PluginSettings {
    override val values = MutableStateFlow(values)
    override suspend fun set(key: String, value: JsonElement) {
        values.value =
            values.value + (key to value)
    }
}

internal fun mediaJson(
    format: String = "MP3_320",
    cipher: String = "BF_CBC_STRIPE",
    type: String = "FULL",
    url: String = "https://fixture.dzcdn.net/audio?token=synthetic",
    expiration: Long = System.currentTimeMillis() /
        1000 +
        3600
) = objectJson(
    """{"data":[{"media":[{"media_type":"$type","format":"$format","cipher":{"type":"$cipher"},"sources":[{"url":"$url"}],"expiration":$expiration}]}]}"""
)

internal fun fixtureMedia(expiry: Long = System.currentTimeMillis() + 3_600_000) = DeezerMedia(
    "42",
    "MP3_320",
    "BF_CBC_STRIPE",
    listOf(URI("https://fixture.dzcdn.net/audio?token=synthetic")),
    expiry
)

internal class AudioFixture : MediaOpener {
    val plain = ByteArray(STRIPE_BYTES * 11 + 113) { ((it * 31) % 251).toByte() }
    val encrypted = plain.copyOf()
    val ranges = mutableListOf<ByteRange?>()
    var ignoreRange = false
    var incorrectRange = false

    init {
        // Independent known key for track 42; this catches incorrect production key derivation.
        val key = "623d356a673e2468652827373b653230".chunked(2).map {
            it.toInt(16).toByte()
        }.toByteArray()
        val cipher = Cipher.getInstance("Blowfish/CBC/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "Blowfish"),
            IvParameterSpec(
                ByteArray(8) {
                    it.toByte()
                }
            )
        )
        for (offset in plain.indices step 6144) {
            if (offset + 2048 <=
                plain.size
            ) {
                cipher.doFinal(plain, offset, 2048).copyInto(encrypted, offset)
            }
        }
    }

    override fun open(
        media: DeezerMedia,
        uri: URI,
        method: String,
        range: ByteRange?
    ): MediaResponse {
        synchronized(ranges) { ranges += range }
        val actual = if (ignoreRange) null else range
        val bytes = if (method ==
            "HEAD"
        ) {
            byteArrayOf()
        } else if (actual ==
            null
        ) {
            encrypted
        } else {
            encrypted.copyOfRange(
                actual.start.toInt(),
                actual.end.toInt() + 1
            )
        }
        return object : MediaResponse {
            override val status = if (actual == null) 200 else 206
            override val length = if (method ==
                "HEAD"
            ) {
                encrypted.size.toLong()
            } else {
                bytes.size.toLong()
            }
            override val contentRange = actual?.let {
                "bytes ${if (incorrectRange) 1 else it.start}-${it.end}/${encrypted.size}"
            }
            override val body = ByteArrayInputStream(bytes)
            override fun close() = Unit
        }
    }
}
