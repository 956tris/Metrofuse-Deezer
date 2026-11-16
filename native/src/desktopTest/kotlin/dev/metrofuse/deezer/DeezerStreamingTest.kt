package dev.metrofuse.deezer

import dev.metrofuse.api.AudioQuery
import dev.metrofuse.api.StreamTransport
import dev.metrofuse.api.TrackIdentity
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive

class DeezerStreamingTest {
    @Test
    fun decryptsKnownStripesAndLeavesUnencryptedAndPartialStripesIntact() {
        val fixture = AudioFixture()
        val decoder = DeezerStripe("42")
        for (offset in fixture.plain.indices step STRIPE_BYTES) {
            val count = minOf(STRIPE_BYTES, fixture.plain.size - offset)
            assertContentEquals(
                fixture.plain.copyOfRange(offset, offset + count),
                decoder.decode(
                    fixture.encrypted.copyOfRange(
                        offset,
                        offset + count
                    ),
                    count,
                    offset.toLong()
                )
            )
        }
    }

    @Test
    fun servesWholeTracksAndArbitrarySeekRangesWithoutChangingBytes() = runBlocking {
        val fixture = AudioFixture()
        val proxy = DeezerProxy(this, fixture)
        val media = fixtureMedia()
        try {
            val url = proxy.register(
                media,
                MediaLocation(media.sources.single(), fixture.plain.size.toLong())
            ) {
                media
            }
            for (range in listOf(
                null,
                "bytes=1-2070",
                "bytes=6149-9000",
                "bytes=-100",
                "bytes=2048-",
                "bytes=0-0"
            )) {
                val connection = connect(url, range)
                try {
                    assertEquals(if (range == null) 200 else 206, connection.responseCode)
                    assertEquals("bytes", connection.getHeaderField("Accept-Ranges"))
                    val parsed = ByteRange.parse(range, fixture.plain.size.toLong())
                    assertContentEquals(
                        fixture.plain.copyOfRange(
                            parsed.start.toInt(),
                            parsed.end.toInt() + 1
                        ),
                        connection.inputStream.use { it.readBytes() }
                    )
                    assertEquals(
                        parsed.length.toString(),
                        connection.getHeaderField("Content-Length")
                    )
                } finally {
                    connection.disconnect()
                }
            }
            assertTrue(fixture.ranges.filterNotNull().all { it.start % STRIPE_BYTES == 0L })
        } finally {
            proxy.close()
        }
    }

    @Test
    fun supportsHeadAndRejectsInvalidOrMultipleRanges() = runBlocking {
        val fixture = AudioFixture()
        val proxy = DeezerProxy(this, fixture)
        val media = fixtureMedia()
        try {
            val url = proxy.register(
                media,
                MediaLocation(media.sources.single(), fixture.plain.size.toLong())
            ) {
                media
            }
            val head = connect(url, method = "HEAD")
            try {
                assertEquals(200, head.responseCode)
                assertEquals(fixture.plain.size.toString(), head.getHeaderField("Content-Length"))
                assertEquals(0, head.inputStream.readBytes().size)
            } finally {
                head.disconnect()
            }
            for (range in listOf("bytes=0-1,4-5", "bytes=999999-", "bytes=50-1", "bytes=-0")) {
                val connection = connect(url, range)
                try {
                    assertEquals(416, connection.responseCode)
                    assertEquals(
                        "bytes */${fixture.plain.size}",
                        connection.getHeaderField("Content-Range")
                    )
                } finally {
                    connection.disconnect()
                }
            }
        } finally {
            proxy.close()
        }
    }

    @Test
    fun rejectsUpstreamsThatIgnoreSeekingOrReturnAnIncorrectOffset() = runBlocking {
        val fixture = AudioFixture()
        val media = fixtureMedia()
        val proxy = DeezerProxy(this, fixture)
        try {
            val url = proxy.register(
                media,
                MediaLocation(media.sources.single(), fixture.plain.size.toLong())
            ) {
                media
            }
            fixture.ignoreRange = true
            val first = connect(url, "bytes=6149-7000")
            try {
                assertEquals(502, first.responseCode)
            } finally {
                first.disconnect()
            }
            fixture.ignoreRange = false
            fixture.incorrectRange = true
            val second = connect(url, "bytes=6149-7000")
            try {
                assertEquals(502, second.responseCode)
            } finally {
                second.disconnect()
            }
        } finally {
            proxy.close()
        }
    }

    @Test
    fun refreshesExpiringUrlsAndRevokesOldHandlesOnLogout() = runBlocking {
        val fixture = AudioFixture()
        val media = fixtureMedia(System.currentTimeMillis() + 10_000)
        val proxy = DeezerProxy(this, fixture)
        var refreshed = 0
        try {
            val url = proxy.register(
                media,
                MediaLocation(media.sources.single(), fixture.plain.size.toLong())
            ) {
                refreshed++
                fixtureMedia()
            }
            val connection = connect(url, "bytes=0-1")
            try {
                assertEquals(206, connection.responseCode)
                assertContentEquals(
                    fixture.plain.copyOfRange(0, 2),
                    connection.inputStream.readBytes()
                )
            } finally {
                connection.disconnect()
            }
            assertEquals(1, refreshed)
            proxy.clear()
            val stale = connect(url)
            try {
                assertEquals(410, stale.responseCode)
            } finally {
                stale.disconnect()
            }
        } finally {
            proxy.close()
        }
    }

    @Test
    fun androidBufferProducesTheSameSeekableBytesAndIsRemovedOnClear() = runBlocking {
        val fixture = AudioFixture()
        val media = fixtureMedia()
        val buffer = DeezerBuffer(fixture)
        try {
            val file = buffer.resolve(
                media,
                MediaLocation(media.sources.single(), fixture.plain.size.toLong())
            )
            assertContentEquals(fixture.plain, file.readBytes())
            assertEquals(
                file,
                buffer.resolve(
                    media,
                    MediaLocation(media.sources.single(), fixture.plain.size.toLong())
                )
            )
            buffer.clear()
            assertFalse(file.exists())
        } finally {
            buffer.close()
        }
    }

    @Test
    fun audioCallbacksReturnPlayableDesktopAndAndroidDescriptors() = runBlocking {
        val fixture = AudioFixture()
        val media = fixtureMedia()
        val accounts = DeezerAccounts(invalidateStreams = {})
        val http = mockHttp { mediaJson().toString() }
        val proxy = DeezerProxy(this, fixture)
        val buffer = DeezerBuffer(fixture)
        val settings = TestSettings(
            mapOf(
                "streamingMode" to JsonPrimitive("DZMEDIA"),
                "dzmediaUrl" to JsonPrimitive("https://resolver.example")
            )
        )
        val audio =
            DeezerAudio(settings, DeezerMediaResolver(http, accounts), fixture, proxy, buffer)
        try {
            val desktop =
                assertNotNull(
                    audio.resolve(AudioQuery(TrackIdentity(TRACK_PREFIX + "42"), "desktop")).stream
                )
            assertEquals(StreamTransport.RANGE_HTTP, desktop.transport)
            val connection = connect(desktop.locator, "bytes=6149-7000")
            try {
                assertContentEquals(
                    fixture.plain.copyOfRange(6149, 7001),
                    connection.inputStream.readBytes()
                )
            } finally {
                connection.disconnect()
            }
            val android =
                assertNotNull(
                    audio.resolve(AudioQuery(TrackIdentity(TRACK_PREFIX + "42"), "android")).stream
                )
            assertEquals(StreamTransport.DIRECT, android.transport)
            assertEquals("file", URI(android.locator).scheme)
            assertContentEquals(fixture.plain, File(URI(android.locator)).readBytes())
            assertFalse(android.toString().contains(android.locator))
            settings.set("audioEnabled", JsonPrimitive(false))
            assertEquals(
                "AUDIO_DISABLED",
                audio.resolve(AudioQuery(TrackIdentity(TRACK_PREFIX + "42"), "desktop")).error?.code
            )
        } finally {
            proxy.close()
            buffer.close()
            http.close()
        }
    }

    @Test
    fun normalizesOpenAndSuffixRangesAndRejectsOverflow() {
        assertEquals(ByteRange(90, 99), ByteRange.parse("bytes=-10", 100))
        assertEquals(ByteRange(20, 99), ByteRange.parse("bytes=20-", 100))
        assertEquals(ByteRange(20, 99), ByteRange.parse("bytes=20-900", 100))
        assertFailsWith<DeezerFailure> { ByteRange.parse("bytes=999999999999999999999999-", 100) }
    }

    private fun connect(url: String, range: String? = null, method: String = "GET") = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 3000
        readTimeout = 5000
        range?.let { setRequestProperty("Range", it) }
    }
}
