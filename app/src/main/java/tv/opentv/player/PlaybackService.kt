// language: Kotlin, file: player/PlaybackService.kt, target: Android 14
// *Media3 session service. Keeps audio alive when the user leaves the app.*

package tv.opentv.player

import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

@UnstableApi
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var player: ExoPlayer? = null

    override fun onCreate() {
        super.onCreate()
        val p = PlayerFactory.build(this)
        player = p
        session = MediaSession.Builder(this, p).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Match the common IPTV expectation: swiping the task away stops playback.
        player?.let { if (!it.playWhenReady || it.mediaItemCount == 0) stopSelf() }
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        player = null
        super.onDestroy()
    }
}
