package dev.metrofuse.deezer

import dev.metrofuse.api.AudioQuery
import dev.metrofuse.api.QualityPreference
import dev.metrofuse.api.QualityPreset
import dev.metrofuse.api.TrackIdentity
import io.ktor.http.HttpHeaders
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

class DeezerMediaTest {
    @Test
    fun requestsOnlyFullMediaWithTheVerifiedAccountsLicenseAndTrackToken() = runBlocking {
        var mediaCalls = 0
        val accounts = DeezerAccounts(createClient = { arl ->
            mockHttp(arl) { request ->
                if (request.url.host == "media.deezer.com") {
                    val body = request.jsonBody()
                    assertEquals("fixture-license", body.text("license_token"))
                    assertEquals("FULL", body.list("media").single().text("type"))
                    assertEquals(
                        "BF_CBC_STRIPE",
                        body.list("media").single().list("formats").first().text("cipher")
                    )
                    assertEquals(
                        JsonArray(listOf(JsonPrimitive("fixture-track"))),
                        body["track_tokens"]
                    )
                    mediaCalls++
                    mediaJson().toString()
                } else {
                    when (request.url.parameters["method"]) {
                        "deezer.getUserData" -> userData()
                        else -> gatewayResult("""{"SNG_ID":"42","TRACK_TOKEN":"fixture-track"}""")
                    }
                }
            }
        }, invalidateStreams = {})
        val public = mockHttp { error("Account media must use the account client") }
        try {
            accounts.login(TEST_CREDENTIALS)
            val media = DeezerMediaResolver(
                public,
                accounts
            ).resolve(
                "42",
                listOf("MP3_320", "MP3_128"),
                StreamingSettings(true, "ACCOUNT", "", false, "AUTO")
            )
            assertEquals("42", media.trackId)
            assertEquals(1, mediaCalls)
        } finally {
            accounts.logout()
            public.close()
        }
    }

    @Test
    fun dzmediaUsesItsConfiguredBasePathAndNeverReceivesAccountCookiesOrLicenseTokens() = runBlocking {
        val accounts =
            DeezerAccounts(createClient = { arl ->
                mockHttp(arl) { userData() }
            }, invalidateStreams = {})
        val http = mockHttp { request ->
            assertEquals("https://resolver.example/api/get_url", request.url.toString())
            assertEquals(null, request.headers[HttpHeaders.Cookie])
            assertEquals(null, request.headers[HttpHeaders.Authorization])
            assertEquals(JsonArray(listOf(JsonPrimitive(42))), request.jsonBody()["ids"])
            assertFalse(request.jsonBody().containsKey("license_token"))
            mediaJson().toString()
        }
        try {
            accounts.login(TEST_CREDENTIALS)
            val media = DeezerMediaResolver(
                http,
                accounts
            ).resolve(
                "42",
                listOf("MP3_320"),
                StreamingSettings(
                    true,
                    "DZMEDIA",
                    "https://resolver.example/api/",
                    true,
                    "AUTO"
                )
            )
            assertEquals(listOf("resolver.example", "fixture.dzcdn.net"), media.sources.map { it.host })
            assertEquals("/api/deezer-media-proxy", media.sources.first().path)
            assertFalse(media.toString().contains("token="))
        } finally {
            accounts.logout()
            http.close()
        }
    }

    @Test
    fun rejectsPreviewsUnknownCiphersAndUntrustedMediaOrigins() {
        for (fixture in listOf(
            mediaJson(type = "PREVIEW"),
            mediaJson(cipher = "AES"),
            mediaJson(url = "http://fixture.dzcdn.net/audio"),
            mediaJson(url = "https://dzcdn.net.attacker.example/audio"),
            mediaJson(url = "https://user:pass@fixture.dzcdn.net/audio")
        )) {
            assertFailsWith<DeezerFailure> {
                DeezerMediaParser.parse(fixture, "42", listOf("MP3_320"))
            }
        }
        assertFailsWith<DeezerFailure> {
            DeezerMediaParser.parse(mediaJson(expiration = 1), "42", listOf("MP3_320"))
        }
    }

    @Test
    fun honorsLowBitrateAndStrictLosslessRequests() {
        val settings = StreamingSettings(true, "ACCOUNT", "", false, "FLAC")
        assertEquals(
            listOf("MP3_128"),
            mediaFormats(
                AudioQuery(
                    TrackIdentity("42"),
                    "desktop",
                    QualityPreference(preset = QualityPreset.LOW)
                ),
                settings
            )
        )
        assertEquals(
            listOf("FLAC"),
            mediaFormats(
                AudioQuery(TrackIdentity("42"), "desktop", QualityPreference(lossless = true)),
                settings
            )
        )
        assertEquals(
            listOf("FLAC", "MP3_320", "MP3_128"),
            mediaFormats(AudioQuery(TrackIdentity("42"), "desktop"), settings)
        )
        assertFailsWith<DeezerFailure> {
            DeezerMediaParser.parse(mediaJson(), "42", listOf("FLAC"))
        }
    }

    @Test
    fun validatesDzmediaConfigurationWithoutSilentlySelectingAnotherServer() {
        assertEquals(
            URI("https://resolver.example/api"),
            dzmediaServer("https://resolver.example/api/get_url/")
        )
        assertEquals(URI("http://127.0.0.1:7860"), dzmediaServer("http://127.0.0.1:7860"))
        for (value in listOf(
            "",
            "http://resolver.example",
            "https://user:pass@resolver.example",
            "https://resolver.example?token=secret",
            "https://resolver.example/#fragment",
            "file:///tmp/server"
        )) {
            assertFailsWith<DeezerFailure> {
                dzmediaServer(value)
            }
        }
    }

    @Test
    fun fallbackTracksUseTheirOwnTokenAndDecryptionIdentity() = runBlocking {
        var calls = 0
        val accounts = DeezerAccounts(createClient = { arl ->
            mockHttp(arl) { request ->
                if (request.url.host == "media.deezer.com") {
                    if (++calls == 1) {
                        """{"data":[{"errors":[{"code":2002}]}]}"""
                    } else {
                        assertEquals(
                            JsonArray(listOf(JsonPrimitive("fallback-token"))),
                            request.jsonBody()["track_tokens"]
                        )
                        mediaJson().toString()
                    }
                } else {
                    when (request.url.parameters["method"]) {
                        "deezer.getUserData" -> userData()

                        else -> if (request.jsonBody().text("SNG_ID") ==
                            "42"
                        ) {
                            gatewayResult(
                                """{"SNG_ID":"42","TRACK_TOKEN":"original-token","FALLBACK":{"SNG_ID":"99"}}"""
                            )
                        } else {
                            gatewayResult("""{"SNG_ID":"99","TRACK_TOKEN":"fallback-token"}""")
                        }
                    }
                }
            }
        }, invalidateStreams = {})
        val http = mockHttp { error("unused") }
        try {
            accounts.login(TEST_CREDENTIALS)
            val media = DeezerMediaResolver(
                http,
                accounts
            ).resolve(
                "42",
                listOf("MP3_320"),
                StreamingSettings(true, "ACCOUNT", "", false, "AUTO")
            )
            assertEquals("99", media.trackId)
            assertEquals(2, calls)
        } finally {
            accounts.logout()
            http.close()
        }
    }
}
