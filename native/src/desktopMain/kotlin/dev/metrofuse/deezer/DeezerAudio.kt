package dev.metrofuse.deezer

import dev.metrofuse.api.AudioCapabilities
import dev.metrofuse.api.AudioQuery
import dev.metrofuse.api.AudioResult
import dev.metrofuse.api.AudioSource
import dev.metrofuse.api.PluginSettings
import dev.metrofuse.api.ProviderError
import dev.metrofuse.api.StreamDescriptor
import dev.metrofuse.api.StreamTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class DeezerAudio(
    private val settings: PluginSettings,
    private val resolver: DeezerMediaResolver,
    private val opener: MediaOpener,
    private val proxy: DeezerProxy,
    private val buffer: DeezerBuffer
) : AudioSource {
    override val id = "deezer.audio"
    override val capabilities =
        AudioCapabilities(
            setOf(StreamTransport.RANGE_HTTP, StreamTransport.DIRECT),
            codecs = setOf("mp3", "flac"),
            formats = setOf("audio/mpeg", "audio/flac")
        )

    override suspend fun resolve(request: AudioQuery): AudioResult {
        val snapshot = StreamingSettings.from(settings.values.value)
        if (!snapshot.enabled) return AudioResult(error = ProviderError("AUDIO_DISABLED"))
        val id =
            trackId(request.identity) ?: return AudioResult(error = ProviderError("WRONG_PROVIDER"))
        return try {
            val formats = mediaFormats(request, snapshot)
            val media = resolver.resolve(id, formats, snapshot)
            val location = withContext(Dispatchers.IO) { probeMedia(media, opener) }
            val android = request.platform.equals("android", true)
            val locator = if (android) {
                buffer.resolve(media, location).toURI().toASCIIString()
            } else {
                proxy.register(media, location) {
                    if (snapshot !=
                        StreamingSettings.from(settings.values.value)
                    ) {
                        throw CancellationException("Deezer streaming settings changed")
                    }
                    resolver.resolve(id, listOf(media.format), snapshot, forceRefresh = true)
                }
            }
            if (snapshot !=
                StreamingSettings.from(settings.values.value)
            ) {
                throw CancellationException("Deezer streaming settings changed")
            }
            AudioResult(
                stream = StreamDescriptor(
                    transport = if (android) StreamTransport.DIRECT else StreamTransport.RANGE_HTTP,
                    locator = locator,
                    mimeType = media.mimeType,
                    codec = media.codec,
                    bitrate = media.bitrate,
                    contentLength = location.length,
                    sampleRate = location.flac?.sampleRate,
                    durationSeconds = location.flac?.durationSeconds,
                    expiresAtMillis = media.expiresAt - 30_000,
                    attributes = buildMap {
                        put("deezer.format", media.format)
                        put("deezer.source", if (media.format == "MP3_MISC") "ACCOUNT" else snapshot.mode)
                        if (media.format == "MP3_MISC") put("deezer.uploaded", "true")
                        put("deezer.lossless", media.lossless.toString())
                        location.flac?.let {
                            put("deezer.bitDepth", it.bitDepth.toString())
                            put("deezer.sampleRate", it.sampleRate.toString())
                            put("deezer.channels", it.channels.toString())
                        }
                    }
                )
            )
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: DeezerFailure) {
            AudioResult(error = ProviderError(failure.code, retryable = failure.retryable))
        } catch (_: Exception) {
            AudioResult(error = ProviderError("DEEZER_STREAM_FAILED", retryable = true))
        }
    }
}
