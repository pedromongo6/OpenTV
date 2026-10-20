// language: Kotlin, file: player/FailoverController.kt, target: Android 14
// *Source failover: on unrecoverable player error, advance to the next known URL,*
// *remembering which one worked so the next tune-in starts there.*

package tv.opentv.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * Wraps a player and swaps the media item when the current URL dies.
 *
 * IPTV panels commonly publish the same channel on several hosts, or a channel
 * may be reachable on a mirror. [candidates] is ordered best-first.
 */
@UnstableApi
class FailoverController(
    private val player: Player,
    private val candidates: List<String>,
    private val onSwitch: (index: Int, url: String, reason: String) -> Unit,
    private val onExhausted: (reason: String) -> Unit,
) : Player.Listener {

    private var index = 0
    private var switching = false
    private var attemptsOnCurrent = 0

    /** Give each candidate a few chances; transient 5xx recover without a switch. */
    private val attemptsPerCandidate = 2

    /** Errors that mean "this URL is wrong", not "the network hiccuped". */
    private fun isFatal(error: PlaybackException): Boolean = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        -> true

        else -> false
    }

    override fun onPlayerError(error: PlaybackException) {
        attemptsOnCurrent++

        val shouldAdvance = when {
            // A malformed container will never fix itself on this URL.
            isFatal(error) -> true
            // Otherwise allow a couple of retries before moving on.
            attemptsOnCurrent >= attemptsPerCandidate -> true
            else -> false
        }

        if (!shouldAdvance) {
            // Re-prepare the same item; the load policy will retry with backoff.
            player.prepare()
            return
        }

        if (switching) return

        val next = index + 1
        if (next >= candidates.size) {
            onExhausted(error.message ?: "Playback failed")
            return
        }

        switching = true
        index = next
        attemptsOnCurrent = 0
        val url = candidates[next]
        onSwitch(next, url, shortReason(error))
        player.setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
        player.prepare()
        switching = false
    }

    /** Call after a successful tune-in so the working URL is the remembered default. */
    fun confirmCurrent(): String = candidates.getOrElse(index) { candidates.first() }

    fun currentIndex(): Int = index

    private fun shortReason(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "404"
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "bad HTTP status"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "connection timeout"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "connection failed"
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "bad manifest"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "bad container"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "decoder init failed"
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "codec unsupported"
        else -> "error ${error.errorCode}"
    }
}
