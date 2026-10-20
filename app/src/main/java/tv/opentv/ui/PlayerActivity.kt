// language: Kotlin, file: ui/PlayerActivity.kt, target: Android 14
// *Playback surface: ExoPlayer + failover, track selection, aspect modes, resume.*

package tv.opentv.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.opentv.R
import tv.opentv.data.Prefs
import tv.opentv.data.Repository
import tv.opentv.data.db.ResumeEntity
import tv.opentv.databinding.ActivityPlayerBinding
import tv.opentv.player.AspectRatioLayout
import tv.opentv.player.FailoverController
import tv.opentv.player.PlayerFactory

@UnstableApi
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var player: ExoPlayer
    private lateinit var prefs: Prefs

    private var failover: FailoverController? = null

    /** Ordered candidates, best first. Index 0 is what the user picked. */
    private var candidates: List<String> = emptyList()
    private var title: String = ""
    private var itemId: String = ""
    private var isLive: Boolean = false

    private var hideControlsJob: Job? = null
    private var resumeJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs.get(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        candidates = intent.getStringArrayListExtra(EXTRA_URLS).orEmpty()
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        itemId = intent.getStringExtra(EXTRA_ITEM_ID).orEmpty()
        isLive = intent.getBooleanExtra(EXTRA_IS_LIVE, false)

        if (candidates.isEmpty()) {
            finish()
            return
        }

        binding.playerTitle.text = title
        binding.aspect.mode = AspectRatioLayout.Mode.entries
            .getOrElse(prefs.aspectMode) { AspectRatioLayout.Mode.FIT }

        player = PlayerFactory.build(
            this,
            preferSoftwareDecoders = prefs.decoderMode == "sw",
            bufferMs = prefs.bufferMs,
        )
        binding.playerView.player = player
        binding.playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        binding.playerView.setControllerShowTimeoutMs(4_000)

        setUpControls()

        // Start from the host that worked last time, if we have a record of one.
        val remembered = if (itemId.isNotEmpty()) prefs.rememberedSource(itemId) else 0
        val startIndex = remembered.coerceIn(0, candidates.lastIndex)
        if (startIndex > 0) {
            candidates = candidates.drop(startIndex) + candidates.take(startIndex)
        }

        setUpFailover()

        val startUrl = candidates.first()
        player.setMediaItem(MediaItem.fromUri(startUrl))
        if (!isLive) restoreResume()
        player.prepare()
        player.playWhenReady = true

        hideTitleAfterDelay()
        scheduleControlsHide()
    }

    // ------------------------------------------------------------ failover

    private fun setUpFailover() {
        failover = FailoverController(
            player = player,
            candidates = candidates,
            onSwitch = { index, url, reason ->
                runOnUiThread {
                    binding.switchNotice.text = getString(R.string.switched_source, index + 1, reason)
                    binding.switchNotice.visibility = View.VISIBLE
                    binding.switchNotice.postDelayed(
                        { binding.switchNotice.visibility = View.GONE },
                        4_000,
                    )
                    persistWorkingUrl(url)
                }
            },
            onExhausted = { reason ->
                runOnUiThread {
                    binding.errorOverlay.visibility = View.VISIBLE
                    binding.errorText.text = getString(R.string.all_sources_failed, reason)
                }
            },
        ).also { player.addListener(it) }

        // Remember the URL that actually produced video.
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    binding.errorOverlay.visibility = View.GONE
                    binding.buffering.visibility = View.GONE
                    failover?.let { persistWorkingUrl(it.confirmCurrent()) }
                }
                if (state == Player.STATE_BUFFERING) {
                    binding.buffering.visibility = View.VISIBLE
                }
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    binding.aspect.videoAspect =
                        videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                rebuildTrackMenus(tracks)
            }
        })
    }

    // ------------------------------------------------------------ controls

    private fun setUpControls() {
        binding.aspectButton.setOnClickListener { cycleAspect() }
        binding.audioButton.setOnClickListener { showAudioMenu() }
        binding.subtitleButton.setOnClickListener { showSubtitleMenu() }
        binding.speedButton.setOnClickListener { showSpeedMenu() }
        binding.retryButton.setOnClickListener {
            binding.errorOverlay.visibility = View.GONE
            player.prepare()
            player.playWhenReady = true
        }
        binding.closeButton.setOnClickListener { finish() }
    }

    private fun cycleAspect() {
        val modes = AspectRatioLayout.Mode.entries
        val next = (binding.aspect.mode.ordinal + 1) % modes.size
        binding.aspect.mode = modes[next]
        prefs.aspectMode = next
        binding.aspectLabel.text = getString(
            when (modes[next]) {
                AspectRatioLayout.Mode.FIT -> R.string.aspect_fit
                AspectRatioLayout.Mode.FILL -> R.string.aspect_fill
                AspectRatioLayout.Mode.ZOOM -> R.string.aspect_zoom
                AspectRatioLayout.Mode.STRETCH -> R.string.aspect_stretch
            },
        )
    }

    private var audioTracks: List<TrackChoice> = emptyList()
    private var subtitleTracks: List<TrackChoice> = emptyList()

    private data class TrackChoice(
        val label: String,
        val groupIndex: Int,
        val trackIndex: Int,
        val selected: Boolean,
    )

    private fun rebuildTrackMenus(tracks: Tracks) {
        val audio = ArrayList<TrackChoice>()
        val subs = ArrayList<TrackChoice>()

        tracks.groups.forEachIndexed { groupIndex, group ->
            for (trackIndex in 0 until group.length) {
                if (!group.isTrackSupported(trackIndex)) continue
                val format = group.getTrackFormat(trackIndex)
                val isAudio = MimeTypes.isAudio(format.sampleMimeType)
                val isText = MimeTypes.isText(format.sampleMimeType)
                if (!isAudio && !isText) continue

                val label = buildTrackLabel(format.language, format.label, format.channelCount)
                val choice = TrackChoice(label, groupIndex, trackIndex, group.isTrackSelected(trackIndex))
                if (isAudio) audio += choice else subs += choice
            }
        }

        audioTracks = audio
        subtitleTracks = subs

        binding.audioButton.visibility = if (audio.size > 1) View.VISIBLE else View.GONE
        binding.subtitleButton.visibility = View.VISIBLE // always offer "off"
    }

    private fun buildTrackLabel(language: String?, label: String?, channels: Int): String {
        val langName = language?.let {
            java.util.Locale(it).displayLanguage.replaceFirstChar { c -> c.uppercase() }
        }
        val channelText = when {
            channels >= 8 -> "7.1"
            channels == 6 -> "5.1"
            channels == 2 -> "Stereo"
            channels == 1 -> "Mono"
            else -> null
        }
        return listOfNotNull(langName?.takeIf { it.isNotBlank() }, label, channelText)
            .joinToString(" · ")
            .ifBlank { getString(R.string.track_unknown) }
    }

    private fun showAudioMenu() {
        if (audioTracks.isEmpty()) return
        val labels = audioTracks.map { it.label + if (it.selected) "  ✓" else "" }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.audio_track)
            .setItems(labels) { _, which ->
                val choice = audioTracks[which]
                overrideTrack(choice, C.TRACK_TYPE_AUDIO)
            }
            .show()
    }

    private fun showSubtitleMenu() {
        val labels = buildList {
            add(getString(R.string.subtitles_off) + if (subtitleTracks.none { it.selected }) "  ✓" else "")
            subtitleTracks.forEach { add(it.label + if (it.selected) "  ✓" else "") }
        }.toTypedArray()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.subtitles)
            .setItems(labels) { _, which ->
                if (which == 0) {
                    player.trackSelectionParameters = player.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                } else {
                    overrideTrack(subtitleTracks[which - 1], C.TRACK_TYPE_TEXT)
                }
            }
            .show()
    }

    private fun overrideTrack(choice: TrackChoice, trackType: Int) {
        val tracks = player.currentTracks
        val group = tracks.groups.getOrNull(choice.groupIndex) ?: return
        val builder = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(trackType, false)
            .setOverrideForType(
                TrackSelectionOverride(group.mediaTrackGroup, choice.trackIndex),
            )
        player.trackSelectionParameters = builder.build()
    }

    private fun showSpeedMenu() {
        val speeds = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val labels = speeds.map { "${it}x" }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.playback_speed)
            .setItems(labels) { _, which ->
                player.setPlaybackSpeed(speeds[which])
                binding.speedButton.text = getString(R.string.speed_format, speeds[which])
            }
            .show()
    }

    // ------------------------------------------------------------ resume

    private fun restoreResume() {
        if (itemId.isEmpty()) return
        lifecycleScope.launch {
            val saved = runCatching {
                Repository.get(this@PlayerActivity).db().resume().get(itemId)
            }.getOrNull()
            // Ignore anything in the first or last 2% — those are accidental.
            if (saved != null && saved.fraction in 0.02f..0.98f) {
                player.seekTo(saved.positionMs)
                binding.resumeNotice.visibility = View.VISIBLE
                binding.resumeNotice.text = getString(
                    R.string.resumed_at,
                    formatTime(saved.positionMs),
                )
                binding.resumeNotice.postDelayed(
                    { binding.resumeNotice.visibility = View.GONE },
                    3_500,
                )
            }
        }
    }

    private fun startResumeSaver() {
        if (isLive || itemId.isEmpty()) return
        resumeJob?.cancel()
        resumeJob = lifecycleScope.launch {
            while (true) {
                delay(5_000)
                val dur = player.duration
                val pos = player.currentPosition
                if (dur > 0 && pos > 0) {
                    runCatching {
                        Repository.get(this@PlayerActivity).db().resume()
                            .upsert(ResumeEntity(itemId, pos, dur))
                    }
                }
            }
        }
    }

    /**
     * Remember which candidate actually produced video for this channel, so the
     * next tune-in starts there instead of re-walking dead hosts.
     */
    private fun persistWorkingUrl(url: String) {
        if (itemId.isEmpty() || candidates.isEmpty()) return
        val index = candidates.indexOf(url)
        if (index < 0) return
        prefs.rememberedSource(itemId, index)
    }

    // ------------------------------------------------------------ lifecycle

    override fun onStart() {
        super.onStart()
        startResumeSaver()
    }

    override fun onStop() {
        super.onStop()
        resumeJob?.cancel()
        // Save immediately so a kill from the recents screen doesn't lose position.
        if (!isLive && itemId.isNotEmpty()) {
            val dur = player.duration
            val pos = player.currentPosition
            if (dur > 0 && pos > 0) {
                // Best effort; the app may be going away.
                runCatching {
                    kotlinx.coroutines.runBlocking {
                        Repository.get(this@PlayerActivity).db().resume()
                            .upsert(ResumeEntity(itemId, pos, dur))
                    }
                }
            }
        }
        player.pause()
    }

    override fun onDestroy() {
        resumeJob?.cancel()
        hideControlsJob?.cancel()
        player.release()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        scheduleControlsHide()
        return when (keyCode) {
            // TV remotes: colour/menu buttons are free real estate for our menus.
            KeyEvent.KEYCODE_MENU -> { cycleAspect(); true }
            KeyEvent.KEYCODE_BUTTON_Y -> { showAudioMenu(); true }
            KeyEvent.KEYCODE_BUTTON_X -> { showSubtitleMenu(); true }
            KeyEvent.KEYCODE_INFO -> toggleInfo(); true
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun toggleInfo() {
        binding.infoPanel.visibility =
            if (binding.infoPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        updateInfoPanel()
    }

    private fun updateInfoPanel() {
        val v = player.videoFormat
        val a = player.audioFormat
        val lines = buildList {
            add("Source: ${failover?.currentIndex()?.plus(1) ?: 1} / ${candidates.size}")
            v?.let {
                add("Video: ${it.sampleMimeType?.substringAfter('/')?.uppercase()} ${it.width}x${it.height}" +
                    (it.frameRate?.let { fr -> " @${fr.toInt()}fps" } ?: ""))
                it.bitrate?.let { b -> add("Video bitrate: ${b / 1000} kbps") }
            }
            a?.let {
                add("Audio: ${it.sampleMimeType?.substringAfter('/')?.uppercase()} " +
                    "${it.channelCount}ch ${it.sampleRate}Hz")
            }
            add("Buffer: ${player.bufferedPosition - player.currentPosition} ms")
            player.playerError?.let { add("Last error: ${it.errorCodeName}") }
        }
        binding.infoText.text = lines.joinToString("\n")
    }

    private fun hideTitleAfterDelay() {
        binding.titleBar.postDelayed(
            { if (!isFinishing) binding.titleBar.visibility = View.GONE },
            4_000,
        )
    }

    private fun scheduleControlsHide() {
        hideControlsJob?.cancel()
        binding.titleBar.visibility = View.VISIBLE
        hideControlsJob = lifecycleScope.launch {
            delay(5_000)
            if (!isFinishing) {
                binding.titleBar.visibility = View.GONE
                binding.infoPanel.visibility = View.GONE
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    companion object {
        private const val EXTRA_URLS = "urls"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_ITEM_ID = "item_id"
        private const val EXTRA_IS_LIVE = "is_live"

        fun intentForChannel(
            context: Context,
            channel: tv.opentv.domain.Channel,
            allUrls: List<String>,
        ): Intent {
            // Put the chosen URL first, keep the rest as failover candidates.
            val ordered = buildList {
                add(channel.streamUrl)
                addAll(channel.alternates)
                addAll(allUrls.filter { it != channel.streamUrl })
            }.distinct()

            return Intent(context, PlayerActivity::class.java).apply {
                putStringArrayListExtra(EXTRA_URLS, ArrayList(ordered))
                putExtra(EXTRA_TITLE, channel.name)
                putExtra(EXTRA_ITEM_ID, "live:${channel.id}")
                putExtra(EXTRA_IS_LIVE, true)
            }
        }

        fun intentForVod(
            context: Context,
            item: tv.opentv.domain.VodItem,
            resolvedUrl: String,
            alternates: List<String> = emptyList(),
        ): Intent = Intent(context, PlayerActivity::class.java).apply {
            putStringArrayListExtra(
                EXTRA_URLS,
                ArrayList((listOf(resolvedUrl) + alternates).distinct()),
            )
            putExtra(EXTRA_TITLE, item.title)
            putExtra(EXTRA_ITEM_ID, "vod:${item.id}")
            putExtra(EXTRA_IS_LIVE, false)
        }
    }
}
