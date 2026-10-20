// language: Kotlin, file: ui/sources/SourceAdapter.kt, target: Android 14
package tv.opentv.ui.sources

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import tv.opentv.R
import tv.opentv.domain.Source
import tv.opentv.domain.SourceKind

class SourceAdapter(
    private val onPick: (Source) -> Unit,
    private val onDelete: (Source) -> Unit,
) : ListAdapter<Source, SourceAdapter.VH>(DIFF) {

    private var activeId: Long = -1

    fun submit(list: List<Source>, active: Long) {
        activeId = active
        submitList(list)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_source, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.sourceName)
        private val detail: TextView = itemView.findViewById(R.id.sourceDetail)
        private val kind: TextView = itemView.findViewById(R.id.sourceKind)
        private val active: TextView = itemView.findViewById(R.id.activeBadge)
        private val remove: ImageButton = itemView.findViewById(R.id.removeButton)

        fun bind(source: Source) = with(itemView) {
            name.text = source.name
            detail.text = when (val k = source.kind) {
                is SourceKind.Xtream -> "${k.username}@${shortHost(k.host)}"
                is SourceKind.M3u -> shortHost(k.url)
                is SourceKind.Single -> shortHost(k.url)
            }
            kind.text = when (source.kind) {
                is SourceKind.Xtream -> context.getString(R.string.kind_xtream)
                is SourceKind.M3u -> context.getString(R.string.kind_m3u)
                is SourceKind.Single -> context.getString(R.string.kind_single)
            }
            active.visibility = if (source.id == activeId) View.VISIBLE else View.GONE

            setOnClickListener { onPick(source) }
            remove.setOnClickListener { onDelete(source) }
        }

        private fun shortHost(url: String): String =
            url.removePrefix("https://").removePrefix("http://").trimEnd('/').take(48)
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<Source>() {
            override fun areItemsTheSame(a: Source, b: Source) = a.id == b.id
            override fun areContentsTheSame(a: Source, b: Source) = a == b
        }
    }
}
