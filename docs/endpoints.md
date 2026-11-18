# Deezer endpoint contracts

Verified on 2026-11-16 against the live services and the [Echo Deezer reference at d67083d](https://github.com/LuftVerbot/echo-deezer-extension/tree/d67083dc5c7cc4ca939f17d659ed350d3199bb3d). These checks cover request contracts, public responses and the compiled plugin's media parser/CDN decoder. They do not establish private account access or successful playback in the MetroFuse device application.

## Gateway and music library

Gateway calls POST JSON to `https://www.deezer.com/ajax/gw-light.php` with `method`, `input=3`, `api_version=1.0` and the session's `api_token` query parameter. Results are unwrapped from `results`; gateway errors are converted to safe codes.

| Purpose | Method and JSON arguments | Response used |
| --- | --- | --- |
| Account/session renewal | `deezer.getUserData`, `{}` | `USER.USER_ID`, account name, `checkForm`, `USER.OPTIONS.license_token` |
| Favorite tracks | `favorite_song.getList`, `user_id`, `tab: "loved"`, `start`, `nb: 100` | `data` track objects, `total` |
| Saved albums, artists and playlists | `deezer.pageProfile`, `user_id`, `tab: "albums" / "artists" / "playlists"`, `start`, `nb: 40`, `lang` | `TAB[tab].data`, `total` when supplied |
| Playlist metadata and first track page | `deezer.pagePlaylist`, `playlist_id`, `start: 0`, `nb: 100`, `tags: true`, `lang` | `DATA`, `SONGS.data`, `SONGS.total` |
| Further playlist track pages | `playlist.getSongs`, `PLAYLIST_ID`, `start`, `nb: 100` | `data`, `total` |
| Account track token and alternatives | `song.getData`, `SNG_ID` | `TRACK_TOKEN`, actual `SNG_ID`, optional `FALLBACK.SNG_ID` |
| Home | `page.get`, `PAGE: "home"`, `VERSION: "2.5"`, `LANG`, supported music layouts in `SUPPORT`, `OPTIONS: []` | `sections`, music shelf items |
| Flow | `radio.getUserRadio`, `user_id`, `nb: 30` | `data` track objects |
| Like/unlike a track | `favorite_song.add` / `favorite_song.remove`, `SNG_ID` | Gateway success result |
| Optional lyrics | `song.getLyrics`, `SNG_ID` | `LYRICS_SYNC_JSON` or `LYRICS_TEXT`; `DATA_ERROR` means unavailable |

Profile identifiers use lowercase `user_id`. `count` is the number of objects in the current page, not the collection total. Pagination uses `total`, `TOTAL` or `total_count` when available, otherwise continues while the page is full. Continuations are tied to the account and liked-tracks filter. A repeated favorites/library page or a collection exceeding the 50,000-item safeguard produces an incomplete-library error instead of silently claiming a complete import.

The invalid `playlist.getData` call was removed: the live gateway rejects it with an invalid-output error. `deezer.pagePlaylist` returned usable `DATA` and `SONGS`. Favorite track pages at `start: 0` and `start: 2` each returned two different tracks and `total: 265`. Playlist pages advanced correctly with `total: 100`. The compiled plugin mapped all 100 tracks in the sampled favorites page, including three uploaded songs, and all 100 tracks plus metadata in the playlist response. Anonymous Home mapped to five shelves with 60 music items. `song.getData` and public-user Flow also returned usable responses.

An anonymous `deezer.pageProfile` request returned `false`. Its authenticated argument/response shape was checked against the reference; saved albums/artists/private playlists still need a signed-in account check. Favorite writes were not performed. The sampled anonymous lyrics request returned `DATA_ERROR` and no lyrics.

This provider imports the four music library types supported by MetroFuse API 1.1.0: tracks, albums, artists and playlists. It does not implement Echo's podcast shows or episode bookmarks.

Uploaded songs use negative `SNG_ID` values and remain valid track identities. Account, album, artist and playlist IDs retain positive-only validation. Upload metadata and like/unlike operations use the signed-in gateway instead of the public catalog. Uploaded songs stream through that account as `MP3_MISC`, even if Dzmedia is selected for catalog tracks; private uploads are never submitted to the external resolver. Strict lossless requests reject uploaded MP3 media. This request shape follows the reference's `FILESIZE_MP3_MISC` handling; upload playback still requires account verification.

## Public catalog

GET requests use `https://api.deezer.com`:

- `/search`, `/search/album`, `/search/artist`, `/search/playlist`, with encoded `q` and bounded `limit`.
- `/track/{id}`, `/album/{id}`, `/album/{id}/tracks`.
- `/artist/{id}`, `/artist/{id}/top`, `/artist/{id}/albums`, `/artist/{id}/radio`.
- `/playlist/{id}`, `/playlist/{id}/tracks`, `/chart/0`.

Live checks returned valid items for each route. Two-item album, artist-album and public-playlist pages advanced to distinct subsequent items with consistent totals. Deezer-generated continuation links may use HTTP or HTTPS; the plugin validates the host/path and always makes the next request through the HTTPS public API.

## Account streaming

POST to `https://media.deezer.com/v1/get_url`:

```json
{
  "license_token": "<in-memory account license>",
  "media": [{
    "type": "FULL",
    "formats": [
      {"cipher": "BF_CBC_STRIPE", "format": "FLAC_24"},
      {"cipher": "BF_CBC_STRIPE", "format": "FLAC"},
      {"cipher": "BF_CBC_STRIPE", "format": "MP3_320"},
      {"cipher": "BF_CBC_STRIPE", "format": "MP3_128"}
    ]
  }],
  "track_tokens": ["<in-memory track token>"]
}
```

The request shape matches the reference's media endpoint. The format list is reduced for explicit quality, low-bitrate and strict-lossless requests. Best available requests prefer `FLAC_24`, FLAC, MP3 320, then MP3 128. If rights or an older server reject a higher format, bounded negotiation retries lower allowed formats. Strict lossless requests never fall back to MP3.

For an uploaded track, the format descriptor instead requests `MP3_MISC`. This is the original uploaded audio, so it does not advertise a fixed 128/320 bitrate or lossless quality.

Track/license expiry triggers one renewed account request. A Deezer-provided alternative track can be retried when unavailable; decoding uses the actual returned track ID. Only `FULL` media and supported ciphers are accepted. This flow requires the user's subscription rights and has not been live verified with account credentials.

## Dzmedia and returned audio

The configurable server is normalized from a hostname, base URL or complete `/get_url` endpoint. The resolver posts to `/get_url` below that base path. The only request fields are:

```json
{"ids":[3135556],"formats":["FLAC_24","FLAC","MP3_320","MP3_128"],"fast":false}
```

No user cookie or account license is sent to dzmedia. The server's configured streaming account determines the granted formats. The response contract uses `data[].media[]`, `media_type`, `format`, `cipher.type`, `sources[].url`, `exp` and optional `filesize`. Expiry values are normalized to milliseconds. Returned direct media must use HTTPS on `dzcdn.net` or its subdomains.

Historical resolver-contract checks through the compiled plugin's actual parser, CDN opener and stripe decoder produced:

| Requested formats | Selected format | Media bytes | Decoded FLAC properties |
| --- | --- | ---: | --- |
| `FLAC_24`, `FLAC`, `MP3_320`, `MP3_128` | `FLAC` | 25,275,843 | 44,100 Hz, 16-bit, stereo, 226 seconds |
| `MP3_320`, `MP3_128` | `MP3_320` | 9,059,264 | — |
| `MP3_128` | `MP3_128` | 3,623,705 | — |
| `FLAC_24`, `FLAC`, relay enabled | `FLAC`, direct CDN fallback | 25,275,843 | 44,100 Hz, 16-bit, stereo |
| `FLAC_24` only | No media for the sampled track | — | — |

The current default Worker, `https://metrofuse-dzmedia.amax69420lol.workers.dev/get_url`, returned MP3 128 for the sampled track; its configured account's streaming rights determine the available formats. Its `/deezer-media-proxy` route returns 404, so relay mode falls back to direct CDN sources. For lossless media the plugin decrypts a bounded header range and reads FLAC STREAMINFO, exposing actual sample rate, bit depth, channel count and duration. It does not claim 24-bit audio just because `FLAC_24` was requested.

Desktop playback supplies decoded byte ranges through the plugin's private loopback endpoint; Android supplies a fully decoded temporary file. Both packages were built without running the test suite or building the local host. Browser login, private library contents, subscription access, actual 24-bit availability and device playback remain for the user's device/account verification.
