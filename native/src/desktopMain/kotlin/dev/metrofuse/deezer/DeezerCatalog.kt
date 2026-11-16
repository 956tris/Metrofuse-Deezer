package dev.metrofuse.deezer

import dev.metrofuse.api.CatalogProvider
import dev.metrofuse.api.HomeItem
import dev.metrofuse.api.HomeLayout
import dev.metrofuse.api.HomeRequest
import dev.metrofuse.api.HomeShelf
import dev.metrofuse.api.LibraryPage
import dev.metrofuse.api.LibraryProvider
import dev.metrofuse.api.LibraryRequest
import dev.metrofuse.api.LyricsLine
import dev.metrofuse.api.LyricsProvider
import dev.metrofuse.api.LyricsRequest
import dev.metrofuse.api.LyricsResult
import dev.metrofuse.api.MetadataContribution
import dev.metrofuse.api.MetadataRequest
import dev.metrofuse.api.MetadataSource
import dev.metrofuse.api.RecommendationProvider
import dev.metrofuse.api.RecommendationRequest
import dev.metrofuse.api.RecommendationResult
import dev.metrofuse.api.SearchProvider
import dev.metrofuse.api.SearchRequest
import dev.metrofuse.api.SearchResult
import dev.metrofuse.api.SongVoteRequest
import dev.metrofuse.api.Track
import dev.metrofuse.api.TrackVote
import io.ktor.client.HttpClient
import io.ktor.http.encodeURLParameter
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class DeezerCatalog(
    private val http: HttpClient,
    private val accounts: DeezerAccounts,
    private val labels: Map<String, String>
) : SearchProvider,
    CatalogProvider,
    MetadataSource,
    LibraryProvider,
    LyricsProvider,
    RecommendationProvider {
    override val id = "deezer.catalog"
    override val supportsLikedSongs = true
    override val supportsSongVotes = true

    override suspend fun search(request: SearchRequest): SearchResult = coroutineScope {
        val query = request.query.trim().take(2048)
        if (query.isEmpty()) return@coroutineScope SearchResult(emptyList())
        val limit = request.limit.coerceIn(1, 100)
        val parameter = "?q=${query.encodeURLParameter()}&limit=$limit"
        val tracks =
            async {
                http.publicGet("/search$parameter").list("data").mapNotNull {
                    DeezerModels.track(it)
                }
            }
        val albums =
            async {
                http.publicGet("/search/album$parameter").list("data").mapNotNull {
                    DeezerModels.album(it)
                }
            }
        val artists =
            async {
                http.publicGet("/search/artist$parameter").list("data").mapNotNull {
                    DeezerModels.artist(it)
                }
            }
        val playlists =
            async {
                http.publicGet("/search/playlist$parameter").list("data").mapNotNull {
                    DeezerModels.playlist(it)
                }
            }
        SearchResult(
            tracks.await().take(limit),
            albums.await().take(limit),
            artists.await().take(limit),
            playlists = playlists.await().take(limit)
        )
    }

    override suspend fun album(id: String) = deezerId(id, ALBUM_PREFIX)?.let { albumId ->
        val data = http.publicGet("/album/$albumId")
        val album = DeezerModels.album(data) ?: return@let null
        val tracks = publicCollection("/album/$albumId/tracks").mapNotNull {
            DeezerModels.track(it)
        }.map {
            it.copy(
                album = it.album.ifEmpty {
                    album.title
                },
                artwork = it.artwork.ifEmpty { album.artwork },
                extras =
                    it.extras + ("albumId" to album.id)
            )
        }
        album.copy(tracks = tracks)
    }

    override suspend fun artist(id: String) = deezerId(id, ARTIST_PREFIX)?.let { artistId ->
        coroutineScope {
            val data = async { http.publicGet("/artist/$artistId") }
            val tracks =
                async {
                    http.publicGet("/artist/$artistId/top?limit=100").list("data").mapNotNull {
                        DeezerModels.track(it)
                    }
                }
            val albums =
                async {
                    publicCollection("/artist/$artistId/albums").mapNotNull {
                        DeezerModels.album(it)
                    }
                }
            DeezerModels.artist(data.await(), tracks.await(), albums.await())
        }
    }

    override suspend fun playlist(id: String) = deezerId(id, PLAYLIST_PREFIX)?.let { playlistId ->
        if (accounts.current() != null) {
            accounts.withSession { session ->
                val root =
                    session.call(
                        "playlist.getData",
                        buildJsonObject {
                            put("PLAYLIST_ID", playlistId)
                        }
                    ) as? JsonObject
                        ?: throw DeezerFailure("INVALID_RESPONSE")
                val playlist =
                    DeezerModels.playlist(root, accountName = session.accountName)
                        ?: return@withSession null
                val tracks = mutableListOf<Track>()
                var index = 0
                while (true) {
                    val page = session.call(
                        "playlist.getSongs",
                        buildJsonObject {
                            put("PLAYLIST_ID", playlistId)
                            put("start", index)
                            put("nb", PAGE_SIZE)
                        }
                    ) as? JsonObject ?: throw DeezerFailure("INVALID_RESPONSE")
                    val entries = requireData(page)
                    tracks += entries.mapNotNull { DeezerModels.track(it) }
                    if (!hasMore(page, index, entries.size)) break
                    if (entries.isEmpty() ||
                        index + entries.size >= MAX_COLLECTION_SIZE
                    ) {
                        throw DeezerFailure("COLLECTION_INCOMPLETE")
                    }
                    index += entries.size
                }
                playlist.copy(tracks = tracks)
            }
        } else {
            val data = http.publicGet("/playlist/$playlistId")
            val playlist = DeezerModels.playlist(data) ?: return@let null
            playlist.copy(
                tracks = publicCollection("/playlist/$playlistId/tracks").mapNotNull {
                    DeezerModels.track(it)
                }
            )
        }
    }

    private suspend fun publicCollection(path: String): List<JsonObject> {
        var next: String? = "$path?limit=$PAGE_SIZE"
        val seen = mutableSetOf<String>()
        val entries = mutableListOf<JsonObject>()
        while (next != null) {
            if (!seen.add(next)) throw DeezerFailure("COLLECTION_INCOMPLETE")
            val root = http.publicGet(next)
            entries += requireData(root)
            val value = root.text("next")
            next = value?.let {
                val uri =
                    runCatching { URI(it) }.getOrNull()
                        ?: throw DeezerFailure("INVALID_CONTINUATION")
                if (uri.scheme != "https" || uri.host != "api.deezer.com" || uri.userInfo != null ||
                    uri.port !in setOf(-1, 443) ||
                    uri.path != path ||
                    uri.fragment != null
                ) {
                    throw DeezerFailure("INVALID_CONTINUATION")
                }
                uri.rawPath + (uri.rawQuery?.let { query -> "?$query" } ?: "")
            }
            if (entries.size > MAX_COLLECTION_SIZE ||
                (entries.size == MAX_COLLECTION_SIZE && next != null)
            ) {
                throw DeezerFailure("COLLECTION_INCOMPLETE")
            }
        }
        return entries
    }

    suspend fun home(request: HomeRequest): List<HomeShelf> {
        val shelves = mutableListOf<HomeShelf>()
        if (accounts.current() != null) {
            accounts.withSession { session ->
                try {
                    val root = session.call(
                        "page.get",
                        buildJsonObject {
                            put("PAGE", "home")
                            put("VERSION", "2.5")
                            put("LANG", request.language.replace('_', '-').substringBefore('-'))
                            put("OPTIONS", JsonArray(emptyList()))
                            put(
                                "SUPPORT",
                                buildJsonObject {
                                    for (type in listOf(
                                        "grid",
                                        "horizontal-grid",
                                        "slideshow",
                                        "grid-preview-one",
                                        "grid-preview-two",
                                        "filterable-grid",
                                        "large-card"
                                    )) {
                                        put(
                                            type,
                                            JsonArray(
                                                listOf(
                                                    "album",
                                                    "playlist",
                                                    "artist",
                                                    "channel"
                                                ).map(::JsonPrimitive)
                                            )
                                        )
                                    }
                                    put("list", JsonArray(listOf(JsonPrimitive("song"))))
                                    put("horizontal-list", JsonArray(listOf(JsonPrimitive("song"))))
                                }
                            )
                        }
                    ) as? JsonObject ?: throw DeezerFailure("INVALID_RESPONSE")
                    shelves += DeezerModels.home(root)
                } catch (failure: DeezerFailure) {
                    if (failure.code != "DATA_ERROR" &&
                        failure.code != "GATEWAY_ERROR"
                    ) {
                        throw failure
                    }
                }
                try {
                    val flow = session.call(
                        "radio.getUserRadio",
                        buildJsonObject {
                            put("user_id", session.userId)
                            put("nb", 30)
                        }
                    ) as? JsonObject ?: throw DeezerFailure("INVALID_RESPONSE")
                    val tracks = flow.list("data").mapNotNull { DeezerModels.track(it) }
                    if (tracks.isNotEmpty()) {
                        shelves.add(
                            0,
                            HomeShelf(
                                labels.getValue("flow"),
                                tracks.map {
                                    HomeItem(track = it)
                                },
                                HomeLayout.ROWS_GRID
                            )
                        )
                    }
                } catch (failure: DeezerFailure) {
                    if (failure.code !in setOf("DATA_ERROR", "GATEWAY_ERROR")) throw failure
                }
            }
        }
        if (shelves.isNotEmpty()) return shelves
        val root = http.publicGet("/chart/0?limit=30", request.language)
        for ((key, label) in listOf(
            "tracks" to "chartTracks",
            "albums" to "chartAlbums",
            "artists" to "chartArtists",
            "playlists" to "chartPlaylists"
        )) {
            val entries = root.obj(key).list("data")
            val items = entries.mapNotNull { value ->
                when (key) {
                    "tracks" -> DeezerModels.track(value)?.let { HomeItem(track = it) }
                    "albums" -> DeezerModels.album(value)?.let { HomeItem(album = it) }
                    "artists" -> DeezerModels.artist(value)?.let { HomeItem(artist = it) }
                    else -> DeezerModels.playlist(value)?.let { HomeItem(playlist = it) }
                }
            }
            if (items.isNotEmpty()) {
                shelves +=
                    HomeShelf(
                        labels.getValue(label),
                        items,
                        if (key ==
                            "tracks"
                        ) {
                            HomeLayout.ROWS_GRID
                        } else {
                            HomeLayout.CARDS
                        }
                    )
            }
        }
        return shelves
    }

    override suspend fun library(request: LibraryRequest): LibraryPage {
        if (accounts.current() == null) return LibraryPage()
        return accounts.withSession { session ->
            val categories = if (request.likedSongsOnly) listOf("tracks") else CATEGORIES
            val cursor =
                decodeCursor(
                    request.continuation,
                    session.userId,
                    request.likedSongsOnly,
                    categories.size
                )
            val category = categories[cursor.category]
            val page: JsonObject
            val items: List<HomeItem>
            if (category == "tracks") {
                page = session.call(
                    "song.getFavoriteIds",
                    buildJsonObject {
                        put("start", cursor.index)
                        put("nb", PAGE_SIZE)
                    }
                ) as? JsonObject ?: throw DeezerFailure("INVALID_RESPONSE")
                val ids = requireData(page).mapNotNull { deezerId(it.text("SNG_ID", "id")) }
                val tracks = if (ids.isEmpty()) {
                    emptyList()
                } else {
                    val songs =
                        session.call(
                            "song.getListData",
                            buildJsonObject {
                                put("SNG_IDS", JsonArray(ids.map(::JsonPrimitive)))
                            }
                        ) as? JsonObject
                            ?: throw DeezerFailure("INVALID_RESPONSE")
                    val byId = requireData(songs).associateBy { it.text("SNG_ID", "id") }
                    ids.mapNotNull {
                        byId[it]?.let { value -> DeezerModels.track(value, liked = true) }
                    }
                }
                items = tracks.map { HomeItem(track = it) }
            } else {
                val profile = session.call(
                    "deezer.pageProfile",
                    buildJsonObject {
                        put("USER_ID", session.userId)
                        put("tab", category)
                        put("start", cursor.index)
                        put("nb", PAGE_SIZE)
                    }
                ) as? JsonObject ?: throw DeezerFailure("INVALID_RESPONSE")
                page =
                    profile.obj("TAB")[category] as? JsonObject
                        ?: throw DeezerFailure("INVALID_RESPONSE")
                items = requireData(page).mapNotNull {
                    when (category) {
                        "albums" -> DeezerModels.album(it)?.let { album -> HomeItem(album = album) }

                        "artists" -> DeezerModels.artist(it)?.let { artist ->
                            HomeItem(artist = artist)
                        }

                        else -> DeezerModels.playlist(
                            it,
                            accountName = session.accountName
                        )?.let { playlist ->
                            HomeItem(playlist = playlist)
                        }
                    }
                }
            }
            val count = requireData(page).size
            val first = requireData(
                page
            ).firstOrNull()?.text("SNG_ID", "ALB_ID", "ART_ID", "PLAYLIST_ID", "id")
            if (cursor.index > 0 && first != null &&
                first == cursor.first
            ) {
                throw DeezerFailure("LIBRARY_DID_NOT_ADVANCE")
            }
            val next = if (hasMore(page, cursor.index, count)) {
                if (count == 0 ||
                    cursor.index + count >= MAX_COLLECTION_SIZE
                ) {
                    throw DeezerFailure("LIBRARY_INCOMPLETE")
                }
                Cursor(cursor.category, cursor.index + count, first)
            } else {
                (cursor.category + 1).takeIf { it < categories.size }?.let { Cursor(it, 0, null) }
            }
            LibraryPage(
                items,
                next?.let {
                    encodeCursor(it, session.userId, request.likedSongsOnly)
                },
                complete = true
            )
        }
    }

    override suspend fun setSongVote(request: SongVoteRequest): Boolean {
        if (request.vote == TrackVote.DISLIKE || accounts.current() == null) return false
        val songId = deezerId(request.trackId, TRACK_PREFIX) ?: return false
        return accounts.withSession { session ->
            val response = session.call(
                if (request.vote ==
                    TrackVote.LIKE
                ) {
                    "favorite_song.add"
                } else {
                    "favorite_song.remove"
                },
                buildJsonObject { put("SNG_ID", songId) }
            )
            response != JsonPrimitive(false) && response != JsonPrimitive(0)
        }
    }

    override suspend fun resolve(request: MetadataRequest): MetadataContribution? {
        val id = trackId(request.identity) ?: return null
        val data = http.publicGet("/track/$id")
        val track = DeezerModels.track(data) ?: return null
        return MetadataContribution(
            request.identity.copy(
                isrc =
                    track.extras["isrc"] ?: request.identity.isrc
            ),
            buildMap {
                put("title", JsonPrimitive(track.title))
                if (track.artist.isNotEmpty()) put("artist", JsonPrimitive(track.artist))
                if (track.album.isNotEmpty()) put("album", JsonPrimitive(track.album))
                if (track.artwork.isNotEmpty()) put("artwork", JsonPrimitive(track.artwork))
                if (track.duration > 0) put("duration", JsonPrimitive(track.duration))
            },
            confidence = 1.0
        )
    }

    override suspend fun lyrics(request: LyricsRequest): LyricsResult? {
        val id = trackId(request.identity) ?: return null
        if (accounts.current() == null) return null
        return accounts.withSession { session ->
            val root = try {
                session.call("song.getLyrics", buildJsonObject { put("SNG_ID", id) }) as? JsonObject
                    ?: return@withSession null
            } catch (failure: DeezerFailure) {
                if (failure.code == "DATA_ERROR") return@withSession null
                throw failure
            }
            val sync = root.list("LYRICS_SYNC_JSON").mapNotNull { line ->
                val text = line.text("line") ?: return@mapNotNull null
                val time =
                    line.text("milliseconds")?.toLongOrNull()?.takeIf { it >= 0 }
                        ?: return@mapNotNull null
                LyricsLine(text, time)
            }
            val lines = sync.ifEmpty {
                root.text("LYRICS_TEXT")?.lines()?.map { LyricsLine(it) }.orEmpty()
            }
            if (lines.isEmpty()) {
                null
            } else {
                LyricsResult(
                    lines,
                    source = root.text("LYRICS_COPYRIGHTS", "LYRICS_WRITERS").orEmpty(),
                    language = root.text("LYRICS_LANGUAGE")
                )
            }
        }
    }

    override suspend fun recommendations(request: RecommendationRequest): RecommendationResult {
        val id = trackId(request.identity) ?: return RecommendationResult(emptyList())
        require(request.continuation == null)
        val data = http.publicGet("/track/$id")
        val artistId =
            deezerId(data.obj("artist").text("id")) ?: return RecommendationResult(emptyList())
        val tracks = http.publicGet(
            "/artist/$artistId/radio?limit=${request.limit.coerceIn(1, 100)}"
        ).list("data").mapNotNull {
            DeezerModels.track(it)
        }
        return RecommendationResult(tracks.filter { it.id != TRACK_PREFIX + id })
    }

    private data class Cursor(val category: Int, val index: Int, val first: String?)

    private fun decodeCursor(
        value: String?,
        user: String,
        liked: Boolean,
        categories: Int
    ): Cursor {
        if (value == null) return Cursor(0, 0, null)
        require(value.startsWith(CURSOR_PREFIX) && value.length <= 1024)
        val data =
            Json.parseToJsonElement(value.removePrefix(CURSOR_PREFIX)) as? JsonObject
                ?: throw DeezerFailure("INVALID_CONTINUATION")
        require(data.text("user") == user && data["liked"] == JsonPrimitive(liked))
        val category =
            data.text("category")?.toIntOrNull() ?: throw DeezerFailure("INVALID_CONTINUATION")
        val index = data.text("index")?.toIntOrNull() ?: throw DeezerFailure("INVALID_CONTINUATION")
        require(category in 0 until categories && index in 0 until MAX_COLLECTION_SIZE)
        return Cursor(category, index, data.text("first"))
    }

    private fun encodeCursor(cursor: Cursor, user: String, liked: Boolean): String = CURSOR_PREFIX + buildJsonObject {
        put("user", user)
        put("liked", liked)
        put("category", cursor.category)
        put("index", cursor.index)
        cursor.first?.let { put("first", it) }
    }

    private fun requireData(page: JsonObject): List<JsonObject> = (page["data"] as? JsonArray)?.objects() ?: throw DeezerFailure("INVALID_RESPONSE")

    private fun hasMore(page: JsonObject, index: Int, count: Int): Boolean {
        val total = page.text("total", "count", "TOTAL")?.toIntOrNull()
        return if (total != null && total >= 0) index + count < total else count == PAGE_SIZE
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val MAX_COLLECTION_SIZE = 50_000
        const val CURSOR_PREFIX = "deezer-library:"
        val CATEGORIES = listOf("tracks", "albums", "artists", "playlists")
    }
}
