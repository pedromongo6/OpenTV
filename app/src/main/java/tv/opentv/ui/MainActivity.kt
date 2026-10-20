// language: Kotlin, file: ui/MainActivity.kt, target: Android 14
// *Single-activity shell. Bottom nav on touch, rail on TV; screens are fragments.*

package tv.opentv.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import tv.opentv.R
import tv.opentv.data.Repository
import tv.opentv.databinding.ActivityMainBinding
import tv.opentv.ui.live.LiveFragment
import tv.opentv.ui.movies.MoviesFragment
import tv.opentv.ui.series.SeriesFragment
import tv.opentv.ui.settings.SettingsFragment
import tv.opentv.ui.sources.SourcesActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val vm: MainViewModel by viewModels()

    private var currentTag: String = TAG_LIVE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (Repository.get(this).sources.value.isEmpty()) {
            startActivity(Intent(this, SourcesActivity::class.java))
        }

        binding.nav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_live -> { show(TAG_LIVE) { LiveFragment() }; true }
                R.id.nav_movies -> { show(TAG_MOVIES) { MoviesFragment() }; true }
                R.id.nav_series -> { show(TAG_SERIES) { SeriesFragment() }; true }
                R.id.nav_settings -> { show(TAG_SETTINGS) { SettingsFragment() }; true }
                else -> false
            }
        }

        if (savedInstanceState == null) {
            binding.nav.selectedItemId = R.id.nav_live
        } else {
            currentTag = savedInstanceState.getString(STATE_TAG) ?: TAG_LIVE
            show(currentTag) {
                when (currentTag) {
                    TAG_MOVIES -> MoviesFragment()
                    TAG_SERIES -> SeriesFragment()
                    TAG_SETTINGS -> SettingsFragment()
                    else -> LiveFragment()
                }
            }
        }

        observeAccount()

        binding.sourcesButton.setOnClickListener {
            startActivity(Intent(this, SourcesActivity::class.java))
        }
    }

    private fun observeAccount() {
        lifecycleScope.launch {
            Repository.get(this@MainActivity).account.collectLatest { info ->
                binding.accountLabel.text = when {
                    info == null -> getString(R.string.no_account)
                    !info.active -> getString(R.string.account_inactive)
                    else -> {
                        val days = info.daysRemaining
                        if (days == null) info.username
                        else getString(R.string.account_days, info.username, days)
                    }
                }
            }
        }
    }

    private fun show(tag: String, create: () -> Fragment) {
        if (tag == currentTag && supportFragmentManager.findFragmentByTag(tag) != null) return
        currentTag = tag
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            supportFragmentManager.fragments.forEach { hide(it) }
            val existing = supportFragmentManager.findFragmentByTag(tag)
            if (existing == null) {
                add(R.id.container, create(), tag)
            } else {
                show(existing)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TAG, currentTag)
    }

    /**
     * TV remotes send MEDIA keys globally. Route them to the fragment that owns
     * playback context, otherwise let the system handle volume.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
            keyCode == KeyEvent.KEYCODE_MEDIA_STOP
        ) {
            val f = supportFragmentManager.findFragmentByTag(currentTag)
            if (f is KeyHandler && f.onMediaKey(keyCode)) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    interface KeyHandler {
        fun onMediaKey(keyCode: Int): Boolean
    }

    private companion object {
        const val TAG_LIVE = "live"
        const val TAG_MOVIES = "movies"
        const val TAG_SERIES = "series"
        const val TAG_SETTINGS = "settings"
        const val STATE_TAG = "current_tag"
    }
}
