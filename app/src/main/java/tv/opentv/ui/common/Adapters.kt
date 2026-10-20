// language: Kotlin, file: ui/common/Adapters.kt, target: Android 14
// *Category rail, EPG guide, and VOD poster adapters.*

package tv.opentv.ui.common

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import tv.opentv.R
import tv.opentv.domain.EpgProgramme
import tv.opentv.domain.LiveCategory
import tv.opentv.domain.VodCategory
import tv.opentv.domain.VodItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------- categories

/** Shared by live categories and VOD categories; both are id+name pairs. */
class CategoryAdapter(
    private val onSelected: (String?) -> Unit,
    private val allLabel: String,
) : RecyclerView.Adapter<CategoryAdapter.VH>() {

    private data class Row(val id: String?, val label: String)

    private var rows: List<Row> = listOf(Row(null, allLabel))
    private var selectedId: String? = null

    fun submit(categories: List<LiveCategory>, selected: String?) {
        rows = buildList {
            add(Row(null, allLabel))
            categories.forEach { add(Row(it.id, it.name)) }
        }
        selectedId = selected
        notifyDataSetChanged()
    }

    fun submitVod(categories: List<VodCategory>, selected: String?) {
        rows = buildList {
            add(Row(null, allLabel))
            categories.forEach { add(Row(it.id, it.name)) }
        }
        selectedId = selected
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_category_chip, parent, false)
        return VH(v)
    }

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = rows[position]
        holder.bind(row.label, row.id == selectedId) { onSelected(row.id) }
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val label: TextView = itemView.findViewById(R.id.chipLabel)

        fun bind(text: String, selected: Boolean, onClick: () -> Unit) = with(itemView) {
            label.text = text
            isSelected = selected
            setOnClickListener { onClick() }
            alpha = if (isSelected) 1f else 0.8f
        }
    }
}

// ---------------------------------------------------------------- EPG

class EpgAdapter : ListAdapter<EpgProgramme, EpgAdapter.VH>(EPG_DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_epg, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val time: TextView = itemView.findViewById(R.id.epgTime)
        private val title: TextView = itemView.findViewById(R.id.epgTitle)
        private val desc: TextView = itemView.findViewById(R.id.epgDesc)
        private val progress: ProgressBar = itemView.findViewById(R.id.epgProgress)

        private val hhmm = SimpleDateFormat("HH:mm", Locale.getDefault())

        fun bind(p: EpgProgramme) = with(itemView) {
            val start = hhmm.format(Date(p.startUtc * 1000))
            val end = hhmm.format(Date(p.endUtc * 1000))
            time.text = "$start – $end"
            title.text = p.title
            desc.text = p.description.orEmpty()
            desc.isVisible = !p.description.isNullOrBlank()

            val now = System.currentTimeMillis() / 1000
            val live = now in p.startUtc..p.endUtc
            progress.isVisible = live
            if (live) {
                progress.progress = (p.progressAt(now) * 100).toInt()
            }
            alpha = if (p.endUtc < now) 0.5f else 1f
        }
    }

    private companion object {
        val EPG_DIFF = object : DiffUtil.ItemCallback<EpgProgramme>() {
            override fun areItemsTheSame(a: EpgProgramme, b: EpgProgramme) =
                a.channelId == b.channelId && a.startUtc == b.startUtc

            override fun areContentsTheSame(a: EpgProgramme, b: EpgProgramme) = a == b
        }
    }
}

// ---------------------------------------------------------------- posters

class VodAdapter(
    private val onClick: (VodItem) -> Unit,
    private val onLongClick: (VodItem) -> Unit = {},
) : ListAdapter<VodItem, VodAdapter.VH>(VOD_DIFF) {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_poster, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val poster: ImageView = itemView.findViewById(R.id.poster)
        private val title: TextView = itemView.findViewById(R.id.posterTitle)
        private val rating: TextView = itemView.findViewById(R.id.posterRating)

        fun bind(item: VodItem) = with(itemView) {
            title.text = item.title
            rating.isVisible = !item.rating.isNullOrBlank()
            rating.text = item.rating

            poster.load(item.posterUrl) {
                crossfade(true)
                placeholder(R.drawable.ic_poster_placeholder)
                error(R.drawable.ic_poster_placeholder)
            }

            setOnClickListener { onClick(item) }
            setOnLongClickListener { onLongClick(item); true }

            scaleX = if (isFocused) 1.06f else 1f
            scaleY = if (isFocused) 1.06f else 1f
            alpha = if (isFocused) 1f else 0.9f
        }
    }

    private companion object {
        val VOD_DIFF = object : DiffUtil.ItemCallback<VodItem>() {
            override fun areItemsTheSame(a: VodItem, b: VodItem) = a.id == b.id
            override fun areContentsTheSame(a: VodItem, b: VodItem) = a == b
        }
    }
}
