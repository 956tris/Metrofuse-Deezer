package dev.metrofuse.deezer

import dev.metrofuse.api.HomeItem
import dev.metrofuse.api.HomeRequest
import dev.metrofuse.api.LibraryRequest
import dev.metrofuse.api.SearchRequest
import dev.metrofuse.api.SongVoteRequest
import dev.metrofuse.api.TrackIdentity
import dev.metrofuse.api.TrackVote
import io.ktor.http.HttpHeaders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray

class DeezerCatalogTest {
    @Test
    fun mapsPublicAndGatewayTracksWithStableNavigationIdsAndMillisecondDurations() {
        val track =
            assertNotNull(
                DeezerModels.track(
                    objectJson(
                        """{"SNG_ID":"42","SNG_TITLE":"Fixture","VERSION":"(Live)","DURATION":"210","ART_ID":"5","ART_NAME":"Artist","ALB_ID":"6","ALB_TITLE":"Album","ALB_PICTURE":"0123456789abcdef0123456789abcdef","ISRC":"GBFIX0000042"}"""
                    ),
                    true
                )
            )
        assertEquals(TRACK_PREFIX + "42", track.id)
        assertEquals("Fixture (Live)", track.title)
        assertEquals(210_000L, track.duration)
        assertEquals(ALBUM_PREFIX + "6", track.extras["albumId"])
        assertEquals("LIKE", track.extras["likeStatus"])
        assertTrue(track.artwork.startsWith("https://cdn-images.dzcdn.net/images/cover/"))
        assertEquals("42", trackId(TrackIdentity(track.id)))
        assertNull(trackId(TrackIdentity(track.id, mapOf("pluginOwner" to "another.provider"))))
        assertNull(trackId(TrackIdentity("42")))
    }

    @Test
    fun parsesMixedHomeShelvesAndExcludesLibraryModules() {
        val shelves = DeezerModels.home(
            objectJson(
                """{"sections":[{"modules":[{"title":"For you","data":[{"type":"album","data":{"ALB_ID":"6","ALB_TITLE":"Album","ART_NAME":"Artist"}},{"type":"song","data":{"SNG_ID":"42","SNG_TITLE":"Track","ART_NAME":"Artist"}}]},{"title":"Library","target":"/library/tracks","data":[{"SNG_ID":"44","SNG_TITLE":"Saved"}]}]}]}"""
            )
        )
        assertEquals(1, shelves.size)
        assertEquals("For you", shelves.single().title)
        assertNotNull(shelves.single().items[0].album)
        assertNotNull(shelves.single().items[1].track)
    }

    @Test
    fun searchesAllCatalogTypesAndPreservesTheRequestedQuery() = runBlocking {
        val paths = mutableSetOf<String>()
        val http = mockHttp { request ->
            assertEquals("A & B", request.url.parameters["q"])
            assertEquals("5", request.url.parameters["limit"])
            synchronized(paths) { paths += request.url.encodedPath }
            when (request.url.encodedPath) {
                "/search" -> """{"data":[{"id":42,"title":"Song","duration":120,"artist":{"id":5,"name":"Artist"},"album":{"id":6,"title":"Album"}}]}"""
                "/search/album" -> """{"data":[{"id":6,"title":"Album","artist":{"name":"Artist"}}]}"""
                "/search/artist" -> """{"data":[{"id":5,"name":"Artist"}]}"""
                else -> """{"data":[{"id":8,"title":"Playlist","user":{"name":"Owner"}}]}"""
            }
        }
        try {
            val result = DeezerCatalog(
                http,
                DeezerAccounts(invalidateStreams = {
                }),
                TEST_LABELS
            ).search(SearchRequest("A & B", 5))
            assertEquals(1, result.tracks.size)
            assertEquals(1, result.albums.size)
            assertEquals(1, result.artists.size)
            assertEquals(1, result.playlists.size)
            assertEquals(4, paths.size)
        } finally {
            http.close()
        }
    }

    @Test
    fun libraryPaginatesFavoritesAndIncludesAlbumsArtistsAndPrivatePlaylists() = runBlocking {
        val calls = mutableListOf<Pair<String, Int>>()
        val accounts = DeezerAccounts(createClient = { arl ->
            mockHttp(arl) { request ->
                val method = request.url.parameters["method"].orEmpty()
                if (method == "deezer.getUserData") return@mockHttp userData()
                val body = request.jsonBody()
                val index = body.text("start")?.toInt() ?: 0
                calls += method to index
                when (method) {
                    "song.getFavoriteIds" -> gatewayResult(
                        """{"count":101,"data":[${(if (index == 0) 1..100 else 101..101).joinToString(
                            ","
                        ) {
                            "{\"SNG_ID\":\"$it\"}"
                        }}]}"""
                    )

                    "song.getListData" -> gatewayResult(
                        """{"data":[${(body["SNG_IDS"] as JsonArray).joinToString(
                            ","
                        ) {
                            "{\"SNG_ID\":$it,\"SNG_TITLE\":\"Favorite\",\"ART_NAME\":\"Artist\"}"
                        }}]}"""
                    )

                    "deezer.pageProfile" -> when (body.text("tab")) {
                        "albums" -> gatewayResult(
                            """{"TAB":{"albums":{"count":1,"data":[{"ALB_ID":"6","ALB_TITLE":"Album","ART_NAME":"Artist"}]}}}"""
                        )

                        "artists" -> gatewayResult(
                            """{"TAB":{"artists":{"count":1,"data":[{"ART_ID":"5","ART_NAME":"Artist"}]}}}"""
                        )

                        else -> gatewayResult(
                            """{"TAB":{"playlists":{"count":1,"data":[{"PLAYLIST_ID":"8","TITLE":"Private playlist"}]}}}"""
                        )
                    }

                    else -> error("Unexpected method $method")
                }
            }
        }, invalidateStreams = {})
        val http = mockHttp { error("Library must use the signed-in account") }
        try {
            accounts.login(TEST_CREDENTIALS)
            val catalog = DeezerCatalog(http, accounts, TEST_LABELS)
            var token: String? = null
            val items = mutableListOf<HomeItem>()
            var pages = 0
            do {
                val page = catalog.library(LibraryRequest("fr", "FR", token))
                assertTrue(page.complete)
                items += page.items
                token = page.continuation
                assertTrue(++pages <= 6)
            } while (token != null)
            assertEquals(101, items.count { it.track != null })
            assertEquals(1, items.count { it.album != null })
            assertEquals(1, items.count { it.artist != null })
            assertEquals(1, items.count { it.playlist != null })
            assertTrue(items.mapNotNull { it.track }.all { it.extras["likeStatus"] == "LIKE" })
            assertTrue("song.getFavoriteIds" to 100 in calls)
            val liked = catalog.library(LibraryRequest("fr", "FR", likedSongsOnly = true))
            assertNotNull(liked.continuation)
            assertFailsWith<IllegalArgumentException> {
                catalog.library(LibraryRequest("fr", "FR", liked.continuation))
            }
        } finally {
            accounts.logout()
            http.close()
        }
    }

    @Test
    fun likeAndUnlikeUseTheSelectedAccountAndRejectUnsupportedDislikes() = runBlocking {
        val methods = mutableListOf<String>()
        val accounts = DeezerAccounts(createClient = { arl ->
            mockHttp(arl) { request ->
                val method = request.url.parameters["method"].orEmpty()
                if (method == "deezer.getUserData") {
                    userData()
                } else {
                    assertEquals("42", request.jsonBody().text("SNG_ID"))
                    assertTrue(request.headers[HttpHeaders.Cookie].orEmpty().contains("arl="))
                    methods += method
                    gatewayResult("true")
                }
            }
        }, invalidateStreams = {})
        val http = mockHttp { error("unused") }
        try {
            accounts.login(TEST_CREDENTIALS)
            val catalog = DeezerCatalog(http, accounts, TEST_LABELS)
            assertTrue(catalog.setSongVote(SongVoteRequest(TRACK_PREFIX + "42", TrackVote.LIKE)))
            assertTrue(
                catalog.setSongVote(SongVoteRequest(TRACK_PREFIX + "42", TrackVote.INDIFFERENT))
            )
            assertEquals(
                false,
                catalog.setSongVote(SongVoteRequest(TRACK_PREFIX + "42", TrackVote.DISLIKE))
            )
            assertEquals(listOf("favorite_song.add", "favorite_song.remove"), methods)
        } finally {
            accounts.logout()
            http.close()
        }
    }

    @Test
    fun publicHomeUsesTheHostLanguageAndAlbumPagingRejectsAnotherOrigin() = runBlocking {
        val http = mockHttp { request ->
            when (request.url.encodedPath) {
                "/chart/0" -> {
                    assertEquals("pt-BR", request.headers[HttpHeaders.AcceptLanguage])
                    """{"tracks":{"data":[{"id":42,"title":"Fixture"}]}}"""
                }

                "/album/6" -> """{"id":6,"title":"Album"}"""

                else -> """{"data":[],"next":"https://attacker.example/album/6/tracks"}"""
            }
        }
        try {
            val catalog = DeezerCatalog(http, DeezerAccounts(invalidateStreams = {}), TEST_LABELS)
            assertEquals(1, catalog.home(HomeRequest("pt-BR", "BR")).size)
            assertFailsWith<DeezerFailure> { catalog.album(ALBUM_PREFIX + "6") }
        } finally {
            http.close()
        }
    }
}
