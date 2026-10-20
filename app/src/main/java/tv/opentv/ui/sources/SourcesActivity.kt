// language: Kotlin, file: ui/sources/SourcesActivity.kt, target: Android 14
// *Add and manage playlists: Xtream panel, M3U URL, or a single stream.*

package tv.opentv.ui.sources

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import tv.opentv.R
import tv.opentv.data.Repository
import tv.opentv.databinding.ActivitySourcesBinding
import tv.opentv.domain.LoadResult
import tv.opentv.domain.Source
import tv.opentv.domain.SourceKind

class SourcesActivity : AppCompatActivity() {

    private lateinit var b: ActivitySourcesBinding
    private lateinit var adapter: SourceAdapter
    private val repo by lazy { Repository.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySourcesBinding.inflate(layoutInflater)
        setContentView(b.root)

        adapter = SourceAdapter(
            onPick = { source ->
                lifecycleScope.launch {
                    repo.selectSource(source.id)
                    finish()
                }
            },
            onDelete = { source -> confirmDelete(source) },
        )
        b.list.apply {
            layoutManager = LinearLayoutManager(this@SourcesActivity)
            adapter = this@SourcesActivity.adapter
        }

        b.addXtream.setOnClickListener { showXtreamDialog() }
        b.addM3u.setOnClickListener { showM3uDialog() }
        b.back.setOnClickListener { finish() }

        lifecycleScope.launch {
            repo.sources.collect { list ->
                adapter.submit(list, repo.activeSource.value?.id ?: -1L)
                b.empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showXtreamDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_add_source, null)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_xtream)
            .setView(view)
            .setPositiveButton(R.string.add, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = view.findViewById<android.widget.EditText>(R.id.fieldName).text.toString().trim()
                val host = view.findViewById<android.widget.EditText>(R.id.fieldHost).text.toString().trim()
                val user = view.findViewById<android.widget.EditText>(R.id.fieldUser).text.toString().trim()
                val pass = view.findViewById<android.widget.EditText>(R.id.fieldPass).text.toString()

                if (host.isBlank() || user.isBlank()) {
                    view.findViewById<android.widget.TextView>(R.id.fieldError).apply {
                        text = getString(R.string.host_and_user_required)
                        visibility = View.VISIBLE
                    }
                    return@setOnClickListener
                }

                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = false
                lifecycleScope.launch {
                    val result = repo.addSource(
                        name.ifBlank { user },
                        SourceKind.Xtream(host = normaliseHost(host), username = user, password = pass),
                    )
                    when (result) {
                        is LoadResult.Ok -> {
                            dialog.dismiss()
                            finish()
                        }
                        is LoadResult.Err -> {
                            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            view.findViewById<android.widget.TextView>(R.id.fieldError).apply {
                                text = describe(result)
                                visibility = View.VISIBLE
                            }
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showM3uDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_add_m3u, null)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_m3u)
            .setView(view)
            .setPositiveButton(R.string.add, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = view.findViewById<android.widget.EditText>(R.id.fieldName).text.toString().trim()
                val url = view.findViewById<android.widget.EditText>(R.id.fieldUrl).text.toString().trim()
                val epg = view.findViewById<android.widget.EditText>(R.id.fieldEpg).text.toString().trim()

                if (url.isBlank() || !url.startsWith("http")) {
                    view.findViewById<android.widget.TextView>(R.id.fieldError).apply {
                        text = getString(R.string.url_required)
                        visibility = View.VISIBLE
                    }
                    return@setOnClickListener
                }

                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = false
                lifecycleScope.launch {
                    val result = repo.addSource(
                        name.ifBlank { "Playlist" },
                        SourceKind.M3u(url = url, name = name.ifBlank { "Playlist" }, epgUrl = epg.ifBlank { null }),
                    )
                    when (result) {
                        is LoadResult.Ok -> { dialog.dismiss(); finish() }
                        is LoadResult.Err -> {
                            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            view.findViewById<android.widget.TextView>(R.id.fieldError).apply {
                                text = describe(result)
                                visibility = View.VISIBLE
                            }
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun confirmDelete(source: Source) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.remove_source)
            .setMessage(source.name)
            .setPositiveButton(R.string.remove) { _, _ ->
                lifecycleScope.launch { repo.removeSource(source.id) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Accept host with or without scheme; panels differ. */
    private fun normaliseHost(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.contains(':') -> "http://$trimmed"
            else -> "http://$trimmed"
        }
    }

    private fun describe(err: LoadResult.Err): String = when (err.reason) {
        tv.opentv.domain.FailureReason.Network -> getString(R.string.err_network)
        tv.opentv.domain.FailureReason.Timeout -> getString(R.string.err_timeout)
        tv.opentv.domain.FailureReason.Unauthorized -> getString(R.string.err_auth)
        tv.opentv.domain.FailureReason.Expired -> getString(R.string.err_expired)
        tv.opentv.domain.FailureReason.ParseError -> getString(R.string.err_parse)
        else -> err.detail ?: getString(R.string.err_unknown)
    }
}
