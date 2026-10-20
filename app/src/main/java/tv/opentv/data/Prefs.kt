// language: Kotlin, file: data/Prefs.kt, target: Android 14
// *Credential storage. Panel passwords go in EncryptedSharedPreferences, never plain prefs.*

package tv.opentv.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Two stores:
 *  - [secure] holds the encrypted source records (they contain credentials).
 *  - [plain] holds UI preferences, which are not sensitive.
 */
class Prefs private constructor(context: Context) {

    private val app = context.applicationContext

    private val secure: SharedPreferences by lazy {
        val key = MasterKey.Builder(app)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            app,
            "opentv_secure",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val plain: SharedPreferences by lazy {
        app.getSharedPreferences("opentv_prefs", Context.MODE_PRIVATE)
    }

    // ---- sources ----
    var sourcesJson: String
        get() = secure.getString(KEY_SOURCES, "[]") ?: "[]"
        set(v) = secure.edit().putString(KEY_SOURCES, v).apply()

    var activeSourceId: Long
        get() = secure.getLong(KEY_ACTIVE, -1L)
        set(v) = secure.edit().putLong(KEY_ACTIVE, v).apply()

    // ---- playback preferences ----
    /** "auto" | "hw" | "sw" — surfaces as a user choice in settings. */
    var decoderMode: String
        get() = plain.getString(KEY_DECODER, "auto") ?: "auto"
        set(v) = plain.edit().putString(KEY_DECODER, v).apply()

    /** Media3 buffer, milliseconds. Larger survives flaky IPTV links. */
    var bufferMs: Int
        get() = plain.getInt(KEY_BUFFER, 30_000)
        set(v) = plain.edit().putInt(KEY_BUFFER, v).apply()

    var aspectMode: Int
        get() = plain.getInt(KEY_ASPECT, 0)   // 0 fit, 1 fill, 2 zoom, 3 stretch
        set(v) = plain.edit().putInt(KEY_ASPECT, v).apply()

    var subtitleScale: Float
        get() = plain.getFloat(KEY_SUB_SCALE, 1.0f)
        set(v) = plain.edit().putFloat(KEY_SUB_SCALE, v).apply()

    var parentalEnabled: Boolean
        get() = plain.getBoolean(KEY_PARENTAL, false)
        set(v) = plain.edit().putBoolean(KEY_PARENTAL, v).apply()

    /** Stored as a salted hash, never the PIN itself. */
    var parentalPinHash: String?
        get() = plain.getString(KEY_PARENTAL_PIN, null)
        set(v) = plain.edit().putString(KEY_PARENTAL_PIN, v).apply()

    var dnsOverHttps: Boolean
        get() = plain.getBoolean(KEY_DOH, false)
        set(v) = plain.edit().putBoolean(KEY_DOH, v).apply()

    var rememberAudioTrack: Boolean
        get() = plain.getBoolean(KEY_REMEMBER_AUDIO, true)
        set(v) = plain.edit().putBoolean(KEY_REMEMBER_AUDIO, v).apply()

    var lastSourceId: Long
        get() = plain.getLong(KEY_LAST_SOURCE, -1L)
        set(v) = plain.edit().putLong(KEY_LAST_SOURCE, v).apply()

    /**
     * Per-item record of the stream index that last worked, so failover progress
     * survives a restart instead of resetting to a dead host every time.
     */
    fun rememberedSource(itemId: String, index: Int) {
        plain.edit().putInt(REMEMBER_PREFIX + itemId, index).apply()
    }

    fun rememberedSource(itemId: String): Int = plain.getInt(REMEMBER_PREFIX + itemId, 0)

    fun forgetRememberedSources() {
        plain.edit().apply {
            plain.all.keys.filter { it.startsWith(REMEMBER_PREFIX) }.forEach { remove(it) }
        }.apply()
    }

    fun clearCredentials() {
        secure.edit().clear().apply()
    }

    companion object {
        private const val REMEMBER_PREFIX = "src_idx:"
        private const val KEY_SOURCES = "sources_json"
        private const val KEY_ACTIVE = "active_source_id"
        private const val KEY_DECODER = "decoder_mode"
        private const val KEY_BUFFER = "buffer_ms"
        private const val KEY_ASPECT = "aspect_mode"
        private const val KEY_SUB_SCALE = "subtitle_scale"
        private const val KEY_PARENTAL = "parental_enabled"
        private const val KEY_PARENTAL_PIN = "parental_pin_hash"
        private const val KEY_DOH = "dns_over_https"
        private const val KEY_REMEMBER_AUDIO = "remember_audio_track"
        private const val KEY_LAST_SOURCE = "last_source_id"

        @Volatile
        private var instance: Prefs? = null

        fun get(context: Context): Prefs = instance ?: synchronized(this) {
            instance ?: Prefs(context).also { instance = it }
        }
    }
}
