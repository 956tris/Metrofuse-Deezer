package dev.metrofuse.deezer

import dev.metrofuse.api.CatalogAlbum
import dev.metrofuse.api.CatalogArtist
import dev.metrofuse.api.CatalogPlaylist
import dev.metrofuse.api.HomeItem
import dev.metrofuse.api.HomeLayout
import dev.metrofuse.api.HomeShelf
import dev.metrofuse.api.Track
import dev.metrofuse.api.TrackIdentity
import java.net.URI
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal const val OWNER = "metrofuse.deezer"
internal const val TRACK_PREFIX = "deezer:track:"
internal const val ALBUM_PREFIX = "deezer:album:"
internal const val ARTIST_PREFIX = "deezer:artist:"
internal const val PLAYLIST_PREFIX = "deezer:playlist:"
internal val EMPTY_OBJECT = JsonObject(emptyMap())

internal fun JsonObject.text(vararg keys: String): String? = keys.firstNotNullOfOrNull {
    (get(it) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
}

internal fun JsonObject.obj(key: String): JsonObject = get(key) as? JsonObject ?: EMPTY_OBJECT

internal fun JsonElement?.objects(): List<JsonObject> = (this as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

internal fun JsonObject.list(vararg keys: String): List<JsonObject> = keys.firstNotNullOfOrNull { key ->
    when (val value = get(key)) {
        is JsonArray -> value.objects()
        is JsonObject -> (value["data"] as? JsonArray ?: value["DATA"] as? JsonArray)?.objects()
        else -> null
    }
}.orEmpty()

internal fun deezerId(value: String?, prefix: String = ""): String? = value?.removePrefix(prefix)?.takeIf {
    it.matches(Regex("[1-9][0-9]{0,18}")) &&
        it.toLongOrNull() != null
}

internal fun trackId(identity: TrackIdentity): String? {
    val owner = identity.identifiers["pluginOwner"] ?: identity.identifiers["plugin"]
    if (owner != null && owner != OWNER) return null
    return deezerId(identity.identifiers["deezer"])
        ?: identity.canonicalId.takeIf {
            it.startsWith(TRACK_PREFIX)
        }?.let { deezerId(it, TRACK_PREFIX) }
        ?: identity.identifiers["providerTrackId"]?.takeIf {
            owner == OWNER
        }?.let { deezerId(it, TRACK_PREFIX) }
}

internal fun httpsUri(value: String): URI? = runCatching { URI(value) }.getOrNull()?.takeIf {
    it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null &&
        it.fragment == null
}

internal object DeezerModels {
    private val coverHash = Regex("[a-fA-F0-9]{32}")

    private fun artwork(value: JsonObject, kind: String, vararg keys: String): String {
        for (key in keys) {
            val candidate = value.text(key) ?: continue
            if (httpsUri(candidate) != null) return candidate
            if (coverHash.matches(
                    candidate
                )
            ) {
                return "https://cdn-images.dzcdn.net/images/$kind/$candidate/1000x1000-000000-80-0-0.jpg"
            }
        }
        return ""
    }

    fun track(value: JsonObject, liked: Boolean = false): Track? {
        val id = deezerId(value.text("SNG_ID", "id")) ?: return null
        val title = value.text("SNG_TITLE", "title", "title_short") ?: return null
        val version = value.text("VERSION", "title_version")
        val artists = value.list("ARTISTS").mapNotNull { it.text("ART_NAME", "name") }.distinct()
        val album = value.obj("album")
        val artist = value.obj("artist")
        val seconds = value.text("DURATION", "duration")?.toDoubleOrNull()?.takeIf {
            it.isFinite() &&
                it >= 0 &&
                it < 86_400
        }
        val albumId = deezerId(value.text("ALB_ID") ?: album.text("id"))
        val artistId = deezerId(value.text("ART_ID") ?: artist.text("id"))
        val extras = buildMap {
            put("deezer", id)
            albumId?.let { put("albumId", ALBUM_PREFIX + it) }
            artistId?.let { put("artistId", ARTIST_PREFIX + it) }
            value.text("ISRC", "isrc")?.let { put("isrc", it) }
            if (liked) put("likeStatus", "LIKE")
            value.text("EXPLICIT_LYRICS", "explicit_lyrics")?.let { put("explicit", it) }
        }
        return Track(
            id = TRACK_PREFIX + id,
            title = if (version == null ||
                title.contains(version, ignoreCase = true)
            ) {
                title
            } else {
                "$title $version"
            },
            artist = if (artists.isNotEmpty()) {
                artists.joinToString(", ")
            } else {
                value.text("ART_NAME")
                    ?: artist.text("name").orEmpty()
            },
            album = value.text("ALB_TITLE") ?: album.text("title").orEmpty(),
            duration = seconds?.let { (it * 1000).toLong() } ?: 0,
            artwork = artwork(album, "cover", "cover_xl", "cover_big", "cover_medium", "cover")
                .ifEmpty {
                    artwork(value, "cover", "ALB_PICTURE", "cover_xl", "cover_big", "cover")
                },
            extras = extras
        )
    }

    fun album(value: JsonObject, tracks: List<Track> = emptyList()): CatalogAlbum? {
        val id = deezerId(value.text("ALB_ID", "id")) ?: return null
        val title = value.text("ALB_TITLE", "title") ?: return null
        return CatalogAlbum(
            id = ALBUM_PREFIX + id,
            title = title,
            artist = value.text("ART_NAME") ?: value.obj("artist").text("name").orEmpty(),
            artwork = artwork(
                value,
                "cover",
                "cover_xl",
                "cover_big",
                "cover_medium",
                "cover",
                "ALB_PICTURE"
            ),
            tracks = tracks
        )
    }

    fun artist(
        value: JsonObject,
        tracks: List<Track> = emptyList(),
        albums: List<CatalogAlbum> = emptyList()
    ): CatalogArtist? {
        val id = deezerId(value.text("ART_ID", "id")) ?: return null
        val name = value.text("ART_NAME", "name") ?: return null
        return CatalogArtist(
            id = ARTIST_PREFIX + id,
            name = name,
            artwork = artwork(
                value,
                "artist",
                "picture_xl",
                "picture_big",
                "picture_medium",
                "picture",
                "ART_PICTURE"
            ),
            tracks = tracks,
            albums = albums
        )
    }

    fun playlist(
        value: JsonObject,
        tracks: List<Track> = emptyList(),
        accountName: String = ""
    ): CatalogPlaylist? {
        val id = deezerId(value.text("PLAYLIST_ID", "id")) ?: return null
        val title = value.text("TITLE", "title") ?: return null
        return CatalogPlaylist(
            id = PLAYLIST_PREFIX + id,
            title = title,
            author =
                value.text("PARENT_USERNAME", "BLOG_NAME") ?: value.obj("user").text("name")
                    ?: value.obj("creator").text("name")
                    ?: accountName,
            artwork = artwork(
                value,
                "playlist",
                "picture_xl",
                "picture_big",
                "picture_medium",
                "picture",
                "PLAYLIST_PICTURE"
            ),
            tracks = tracks
        )
    }

    fun item(value: JsonObject): HomeItem? {
        val data = (value["data"] as? JsonObject) ?: (value["DATA"] as? JsonObject) ?: value
        return when (value.text("type", "TYPE")?.lowercase()) {
            "track", "song" -> track(data)?.let { HomeItem(track = it) }

            "album" -> album(data)?.let { HomeItem(album = it) }

            "artist" -> artist(data)?.let { HomeItem(artist = it) }

            "playlist" -> playlist(data)?.let { HomeItem(playlist = it) }

            else -> when {
                data.containsKey("SNG_ID") -> track(data)?.let { HomeItem(track = it) }

                data.containsKey("PLAYLIST_ID") -> playlist(data)?.let { HomeItem(playlist = it) }

                data.containsKey("ALB_ID") && data.containsKey("ALB_TITLE") -> album(data)?.let {
                    HomeItem(album = it)
                }

                data.containsKey("ART_ID") && data.containsKey("ART_NAME") -> artist(data)?.let {
                    HomeItem(artist = it)
                }

                else -> null
            }
        }
    }

    fun home(root: JsonObject): List<HomeShelf> {
        val shelves = mutableListOf<HomeShelf>()
        fun visit(value: JsonElement, depth: Int) {
            if (depth > 8 || shelves.size >= 40) return
            when (value) {
                is JsonArray -> value.take(100).forEach { visit(it, depth + 1) }

                is JsonObject -> {
                    val target = value.text("target", "url", "link").orEmpty()
                    if (value.text("type", "TYPE") == "library" ||
                        Regex("/(?:library|favorites|profile)(?:/|$)").containsMatchIn(target)
                    ) {
                        return
                    }
                    val title =
                        value.text("title", "TITLE", "name") ?: value.obj("title").text("text")
                    val items = value.list(
                        "items",
                        "data",
                        "contents",
                        "content",
                        "DATA"
                    ).mapNotNull(::item).distinct()
                    if (title != null && items.isNotEmpty()) {
                        shelves +=
                            HomeShelf(
                                title,
                                items.take(100),
                                if (items.all {
                                        it.track != null
                                    }
                                ) {
                                    HomeLayout.ROWS_GRID
                                } else {
                                    HomeLayout.CARDS
                                }
                            )
                    } else {
                        value.filterKeys {
                            it in
                                setOf("sections", "SECTIONS", "modules", "MODULES", "data", "DATA", "items", "contents", "content", "results")
                        }
                            .values.forEach { visit(it, depth + 1) }
                    }
                }

                else -> Unit
            }
        }
        visit(root, 0)
        return shelves.distinctBy { it.title to it.items }
    }
}
