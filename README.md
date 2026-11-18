# Metrofuse Deezer

A separate Deezer provider for MetroFuse on Windows desktop and Android. Install the JAR or APK inside MetroFuse's Plugins screen, enable it, and select Deezer as the music source. The plugin uses the existing host API and does not require changes to the MetroFuse application.

## Features

- Deezer browser sign-in, verified account identity, session restoration and logout.
- Personalized Home and Flow when signed in, with public charts for guest browsing.
- Music library with paginated favorite tracks, uploaded songs, albums, artists and playlists, including private playlists.
- Search for tracks, albums, artists and playlists; album, artist and playlist navigation.
- Like and unlike songs, metadata, synchronized or plain lyrics when supplied by Deezer, and artist radio.
- Full-track streaming through the signed-in Deezer account or a configurable dzmedia server.
- MP3 at 128 or 320 kbps, FLAC and Hi-res `FLAC_24` when available, BF_CBC_STRIPE decoding, desktop byte-range seeking, signed-URL renewal and alternate-CDN selection.
- Actual FLAC sample rate, bit depth and channels are read from the decoded audio and supplied to the host.

The library covers MetroFuse's four music types. Podcast shows and episode bookmarks from the Echo reference are outside this provider's scope.

## Install and sign in

Download `deezer.jar` for desktop or `deezer.apk` for Android from [Releases](https://github.com/956tris/Metrofuse-Deezer/releases/latest). Import it through MetroFuse's Plugins screen; the APK is a plugin container, not a separate Android application. Enable Deezer, select its Home source and use the account button to sign in directly on Deezer's website. Windows sign-in opens the browser bundled in the plugin and requires Microsoft Edge WebView2. A session-token option is available if the browser cannot open; it uses the same account verification and encrypted host credential store.

Use [MetroFuse UI SDK preview 003](https://github.com/956tris/MetroFuse/releases/tag/v10.0.0-ui-sdk-preview-003) or a compatible host with API 1.1.0, the platform dialog bridge, and the native ABI `kotlin-2.4.21_compose-1.13.0-beta02_api-1.1.0`. The host supplies WebView sign-in, Kotlin, Compose, coroutines, serialization and Ktor. Accepted sessions use the host's encrypted credential store where supported. The plugin does not store cookies or license tokens in ordinary settings or files.

## Streaming settings

Open Deezer's settings in the Plugins screen and tap **Streaming source** to show the **Deezer account** and **Dzmedia** options. **Preferred Deezer quality** opens the same kind of picker. These selections are saved in the plugin's own storage. After upgrading from 1.0.0, select your source and quality once. The saved server URL and switches are retained.

**Deezer account** is the default streaming source. Sign in to Deezer and use a subscription that permits the requested quality. The plugin obtains a track token and the account's license token and requests `FULL` media from Deezer. Playback errors are returned when the service does not grant full-track access.

To use dzmedia, choose **Dzmedia** and enter your server in **Dzmedia server URL**, then apply the URL field. The default for new installations is `https://metrofuse-dzmedia.amax69420lol.workers.dev/get_url`; you can replace it with your own server. A hostname, base URL, or full HTTPS `/get_url` endpoint is accepted. A base path is supported, for example `https://your-server.example/deezer`; the plugin posts to `/get_url` beneath that path. The request contains the Deezer track ID and requested formats. It is compatible with the dzmedia-metrofuse resolver's `formats`, `ids` and `fast` request contract and Deezer's media response format, including its `exp` and `filesize` fields.

**Prefer the dzmedia server relay** tries the server's `/deezer-media-proxy` endpoint first, then the direct Deezer CDN if the relay is unavailable. It is off by default; servers such as the supplied deployment that only implement `/get_url` work without a relay. The plugin performs the decoding. Your signed-in Deezer account supplies your library and Home; dzmedia uses the streaming account configured on that server. Account cookies and license tokens are never sent to dzmedia. Plain HTTP is accepted only for a development server at `127.0.0.1`.

Uploaded songs have negative Deezer track IDs. They remain in your library and stream as original `MP3_MISC` through your signed-in account, including when Dzmedia is selected for catalog music. A strict lossless request cannot play an uploaded MP3.

**Preferred Deezer quality** selects Best available, MP3 128, MP3 320, FLAC or Hi-res FLAC. Best available and Hi-res try `FLAC_24`, then standard FLAC, MP3 320 and MP3 128. A FLAC preference starts with standard FLAC. The plugin negotiates only formats granted by the service; unsupported Hi-res requests also fall back for older servers. A strict lossless request from the host permits only FLAC variants. Low-quality and bitrate-limited requests select the appropriate MP3 format.

In account mode, availability depends on your Deezer subscription, region and track. In dzmedia mode it depends on the account configured on your server. Selecting Hi-res does not turn a 16-bit track into 24-bit audio; the host receives the actual FLAC properties.

Desktop streams decoded bytes as they arrive through a private loopback endpoint and supports arbitrary byte ranges for seeking. Android buffers the current decoded track into the application's temporary directory before returning a seekable file. This works with the host's existing cleartext-network restrictions. Android startup therefore depends on download speed and the host's audio-resolution deadline. Buffers and stream handles are cleared on account changes, logout, settings changes and plugin stop.

## Build

Use Java 25 and the included Gradle 9.8.2 wrapper. Provide the matching, already-built MetroFuse API and plugin SDK JARs. The extension build does not build or modify the host. Android packaging additionally needs build tools `37.0.0` and platform `android-37.0`, selected through `ANDROID_HOME`, `ANDROID_SDK_ROOT` or an untracked `local.properties` file.

```powershell
./gradlew.bat :native:packagePluginJar :native:packagePluginApk `
  "-PmetrofuseApiJar=C:\path\plugin-api-desktop-1.1.0.jar" `
  "-PmetrofuseSdkJar=C:\path\plugin-sdk-desktop-1.1.0.jar" `
  --no-daemon --max-workers=1 --no-configuration-cache
python tooling/package-release.py
```

Native outputs are in `native/build/outputs/plugins/`. Release files and `SHA256SUMS` are copied to `dist/`. Host API/SDK classes and host libraries are compile-only and excluded from the plugin artifacts. The build does not run account or device checks. Validate browser sign-in, personalized content, your dzmedia deployment and actual playback on your devices before relying on them.

The desktop JAR also packages the SDK's self-contained Windows browser executable. Android uses the host's Android WebView and does not include the Windows executable. A custom build can set `-PpluginVersion=1.0.2`; pass the same value to `python tooling/package-release.py --version 1.0.2`.

## Endpoint verification

The library and media contracts were compared with [LuftVerbot's Echo Deezer extension](https://github.com/LuftVerbot/echo-deezer-extension). Live checks confirmed public catalog/search/pagination, public favorites pagination, playlist metadata and track pages, anonymous Home/Flow, and dzmedia resolution through the compiled plugin's parser and CDN decoder. The default Worker returned MP3 128 for the sampled track; formats depend on the streaming rights of its configured account. Its optional relay returned 404 and the plugin successfully used the direct CDN.

Private library access, browser sign-in and subscribed account streaming require device/account verification. `FLAC_24` requests were accepted by dzmedia, but the checked track offered standard FLAC. See [endpoint contracts and verification details](docs/endpoints.md) for the exact calls and limits.

## Automatic releases

Every push to `main` builds the JAR and APK and publishes them with `SHA256SUMS` through [GitHub Actions](https://github.com/956tris/Metrofuse-Deezer/actions/workflows/release-plugin.yml). Manual runs are also supported. The manifest sets the base release series; its patch number plus the workflow run number gives each build a new stable version. The first automatic release is 1.0.1. Reruns preserve published releases, and older commits do not replace the latest release while a newer commit is being built.

The workflow exports only the API and SDK from a pinned MetroFuse source checkout in the isolated GitHub runner, then builds this extension. It does not build the MetroFuse application or run the test suite.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE) and [SOURCE](native/SOURCE). Build and login-screen integration follow the MetroFuse YouTube Music reference. Gateway and media request shapes were inspected in deezer-py 1.3.7, dzmedia-metrofuse and the Echo Deezer extension. No dzmedia server or shared account credentials are included.
