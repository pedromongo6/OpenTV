// language: Kotlin, file: ui/common/ChannelAdapter.kt, target: Android 14
// *Channel row. Focus changes drive both the guide panel and TV remote handling.*

package tv.opentv.ui.common

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import tv.opentv.R
import tv.opentv.domain.Channel

class ChannelAdapter(
    private val onClick: (Channel) -> Unit,
    private val onFocused: (Channel) -> Unit = {},
    private val onLongClick: (Channel) -> Unit = {},
) : ListAdapter<Channel, ChannelAdapter.VH>(DIFF) {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val logo: ImageView = itemView.findViewById(R.id.logo)
        private val name: TextView = itemView.findViewById(R.id.name)
        private val number: TextView = itemView.findViewById(R.id.number)
        private val archive: TextView = itemView.findViewById(R.id.archiveBadge)

        fun bind(channel: Channel) = with(itemView) {
            number.text = (bindingAdapterPosition + 1).toString()
            name.text = channel.name
            archive.visibility = if (channel.tvArchive) View.VISIBLE else View.GONE

            logo.load(channel.logoUrl) {
                crossfade(true)
                placeholder(R.drawable.ic_channel_placeholder)
                error(R.drawable.ic_channel_placeholder)
            }

            setOnClickListener { onClick(channel) }
            setOnLongClickListener { onLongClick(channel); true }
            setOnFocusChangeListener { _, hasFocus -> if (hasFocus) onFocused(channel) }

            // TV: focus must be visually unmistakable across the room.
            isSelected = false
            alpha = if (isFocused) 1f else 0.86f
            scaleX = if (isFocused) 1.03f else 1f
            scaleY = if (isFocused) 1.03f else 1f
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<Channel>() {
            override fun areItemsTheSame(a: Channel, b: Channel) = a.id == b.id
            override fun areContentsTheSame(a: Channel, b: Channel) = a == b
        }
    }
}
