// language: Kotlin, file: ui/settings/SettingsFragment.kt, target: Android 14
// *Playback tuning, parental PIN, source management, cache tools.*

package tv.opentv.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import tv.opentv.R
import tv.opentv.data.Prefs
import tv.opentv.data.Repository
import tv.opentv.databinding.FragmentSettingsBinding
import tv.opentv.player.AspectRatioLayout
import tv.opentv.ui.sources.SourcesActivity
import java.security.MessageDigest
import java.security.SecureRandom

class SettingsFragment : Fragment() {

    private var _b: FragmentSettingsBinding? = null
    private val b get() = _b!!
    private lateinit var prefs: Prefs

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentSettingsBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs.get(requireContext())

        render()

        b.decoderGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            prefs.decoderMode = when (checkedId) {
                R.id.decoderHw -> "hw"
                R.id.decoderSw -> "sw"
                else -> "auto"
            }
        }

        b.bufferSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                prefs.bufferMs = value.toInt()
                b.bufferLabel.text = getString(R.string.buffer_seconds, value.toInt() / 1000)
            }
        }

        b.subtitleSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                prefs.subtitleScale = value
                b.subtitleLabel.text = getString(R.string.subtitle_scale, value)
            }
        }

        b.aspectGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            prefs.aspectMode = when (checkedId) {
                R.id.aspectFill -> AspectRatioLayout.Mode.FILL.ordinal
                R.id.aspectZoom -> AspectRatioLayout.Mode.ZOOM.ordinal
                R.id.aspectStretch -> AspectRatioLayout.Mode.STRETCH.ordinal
                else -> AspectRatioLayout.Mode.FIT.ordinal
            }
        }

        b.parentalSwitch.isChecked = prefs.parentalEnabled
        b.parentalSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked && prefs.parentalPinHash == null) {
                b.parentalSwitch.isChecked = false
                promptSetPin()
            } else {
                prefs.parentalEnabled = checked
            }
        }
        b.changePin.setOnClickListener { promptSetPin() }

        b.dohSwitch.isChecked = prefs.dnsOverHttps
        b.dohSwitch.setOnCheckedChangeListener { _, checked -> prefs.dnsOverHttps = checked }

        b.sourcesRow.setOnClickListener {
            startActivity(Intent(requireContext(), SourcesActivity::class.java))
        }

        b.clearEpg.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                runCatching {
                    Repository.get(requireContext()).db().epg().prune(Long.MAX_VALUE)
                }
                toast(getString(R.string.cache_cleared))
            }
        }

        b.clearAll.setOnClickListener { confirmWipe() }

        b.aboutVersion.text = getString(R.string.version_format, appVersion())
    }

    private fun render() {
        b.decoderGroup.check(
            when (prefs.decoderMode) {
                "hw" -> R.id.decoderHw
                "sw" -> R.id.decoderSw
                else -> R.id.decoderAuto
            },
        )
        b.bufferSlider.value = prefs.bufferMs.toFloat()
        b.bufferLabel.text = getString(R.string.buffer_seconds, prefs.bufferMs / 1000)

        b.subtitleSlider.value = prefs.subtitleScale
        b.subtitleLabel.text = getString(R.string.subtitle_scale, prefs.subtitleScale)

        b.aspectGroup.check(
            when (prefs.aspectMode) {
                AspectRatioLayout.Mode.FILL.ordinal -> R.id.aspectFill
                AspectRatioLayout.Mode.ZOOM.ordinal -> R.id.aspectZoom
                AspectRatioLayout.Mode.STRETCH.ordinal -> R.id.aspectStretch
                else -> R.id.aspectFit
            },
        )
    }

    // ------------------------------------------------------------ parental

    /**
     * PIN is stored as a salted SHA-256, never in the clear — a locked device
     * should not hand the PIN to anyone reading app data.
     */
    private fun promptSetPin() {
        val view = layoutInflater.inflate(R.layout.dialog_pin, null)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.set_pin)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val pin = view.findViewById<EditText>(R.id.pinField).text.toString()
                val confirm = view.findViewById<EditText>(R.id.pinConfirm).text.toString()
                when {
                    pin.length != 4 || pin.any { !it.isDigit() } ->
                        toast(getString(R.string.pin_four_digits))
                    pin != confirm -> toast(getString(R.string.pin_mismatch))
                    else -> {
                        prefs.parentalPinHash = hashPin(pin)
                        prefs.parentalEnabled = true
                        b.parentalSwitch.isChecked = true
                        toast(getString(R.string.pin_saved))
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun hashPin(pin: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(pin.toByteArray())
        return salt.joinToString("") { "%02x".format(it) } +
            ":" + digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------ misc

    private fun confirmWipe() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.clear_all)
            .setMessage(R.string.clear_all_warning)
            .setPositiveButton(R.string.clear) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val repo = Repository.get(requireContext())
                    runCatching {
                        repo.db().clearAllTables()
                    }
                    prefs.clearCredentials()
                    repo.loadSources()
                    toast(getString(R.string.cache_cleared))
                    activity?.finish()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun appVersion(): String = runCatching {
        requireContext().packageManager
            .getPackageInfo(requireContext().packageName, 0).versionName
            .orEmpty()
    }.getOrDefault("?")

    private fun toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() {
        super.onDestroyView(); _b = null
    }
}
