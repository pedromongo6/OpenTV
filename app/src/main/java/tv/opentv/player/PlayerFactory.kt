// language: Kotlin, file: player/PlayerFactory.kt, target: Android 14
// *Builds a configured ExoPlayer. IPTV needs aggressive retry and a tolerant extractor.*

package tv.opentv.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.DefaultHlsPlaylistTracker
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import tv.opentv.data.Http
import tv.opentv.data.Prefs

@UnstableApi
object PlayerFactory {

    /**
     * IPTV servers drop connections constantly. These values trade startup latency
     * for continuity: a deep buffer and many retries rather than a fast failure.
     */
    fun build(
        context: Context,
        preferSoftwareDecoders: Boolean = false,
        bufferMs: Int = Prefs.get(context).bufferMs,
    ): ExoPlayer {
        val renderers = DefaultRenderersFactory(context)
            .setExtensionRendererMode(
                if (preferSoftwareDecoders) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
            )
            // Some panel streams have a broken first frame; let the decoder recover.
            .setEnableDecoderFallback(true)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ bufferMs.coerceAtLeast(15_000),
                /* maxBufferMs = */ (bufferMs * 3).coerceAtLeast(60_000),
                /* bufferForPlaybackMs = */ 2_500,
                /* bufferForPlaybackAfterRebufferMs = */ 5_000,
            )
            .setTargetBufferBytes(C.LENGTH_UNSET)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(30_000, true)
            .build()

        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    // Prefer a sensible default, then let the user override in the player UI.
                    .setPreferredAudioLanguage(null)
                    .setForceHighestSupportedBitrate(false)
                    .setExceedRendererCapabilitiesIfNecessary(true)
                    .setAllowVideoMixedMimeTypeAdaptiveness(true)
                    .setAllowVideoNonSeamlessAdaptiveness(true),
            )
        }

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(UA)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)

        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            .setLiveTargetOffsetMs(5_000)
            .setLiveMinSpeed(0.97f)
            .setLiveMaxSpeed(1.03f)
            .setLoadErrorHandlingPolicy(TolerantErrors())

        return ExoPlayer.Builder(context, renderers)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
    }

    /** Live HLS needs a longer playlist tolerance than VOD. */
    fun hlsSource(dataSourceFactory: androidx.media3.datasource.DataSource.Factory, uri: android.net.Uri) =
        HlsMediaSource.Factory(dataSourceFactory)
            .setAllowChunklessPreparation(true)
            .setPlaylistTrackerFactory { _, _, _ ->
                DefaultHlsPlaylistTracker.FACTORY.createTracker(
                    null,
                    dataSourceFactory,
                )
            }
            .createMediaSource(androidx.media3.common.MediaItem.fromUri(uri))

    /**
     * Nine retries with a widening backoff. Panel streams 502/504 under load and
     * recover on their own; giving up on the first error would break channels
     * that are actually fine.
     */
    private class TolerantErrors : DefaultLoadErrorHandlingPolicy() {
        override fun getMinimumLoadableRetryCount(dataType: Int): Int = 9

        override fun getRetryDelayMsFor(
            loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
        ): Long {
            val attempt = loadErrorInfo.errorCount
            val base = 1_000L * attempt
            return base.coerceIn(1_000L, 15_000L)
        }
    }

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Android TV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
}
