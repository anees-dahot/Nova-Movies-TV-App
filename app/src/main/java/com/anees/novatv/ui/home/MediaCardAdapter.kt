package com.anees.novatv.ui.home

import android.graphics.Outline
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.anees.novatv.R
import com.anees.novatv.data.model.MediaCard
import com.anees.novatv.data.model.MediaType
import com.anees.novatv.data.model.LangTags
import com.anees.novatv.data.local.LocalStore

/** Poster (2:3) or wide (16:9, backdrop) cards. Focus = zoom only: no shadows, which this GPU draws slowly. */
class MediaCardAdapter(
    private val items: List<MediaCard>,
    private val progress: List<Float>? = null,
    private val subtitles: List<String?>? = null,
    private val wide: Boolean = false,
    private val onFocus: (MediaCard) -> Unit = {},
    private val onLongClick: ((Int, MediaCard) -> Unit)? = null,
    private val onClick: (Int, MediaCard) -> Unit
) : RecyclerView.Adapter<MediaCardAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val frame: FrameLayout = view.findViewById(R.id.card_frame)
        val poster: ImageView = view.findViewById(R.id.card_poster)
        val title: TextView = view.findViewById(R.id.card_title)
        val meta: TextView = view.findViewById(R.id.card_meta)
        val badge: TextView = view.findViewById(R.id.card_badge)
        val bar: ProgressBar = view.findViewById(R.id.card_progress)
        val lang: TextView = view.findViewById(R.id.card_lang)

        init {
            val radius = 10f * view.resources.displayMetrics.density
            frame.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, radius)
            }
            frame.clipToOutline = true
            view.setOnFocusChangeListener { v, has ->
                v.animate().scaleX(if (has) 1.1f else 1f).scaleY(if (has) 1.1f else 1f).setDuration(150).start()
                title.alpha = if (has) 1f else 0.7f
                val pos = bindingAdapterPosition
                if (has && pos != RecyclerView.NO_POSITION) onFocus(items[pos])
            }
            view.setOnClickListener { pos()?.let { onClick(it, items[it]) } }
            view.setOnLongClickListener { pos()?.let { onLongClick?.invoke(it, items[it]) }; onLongClick != null }
        }

        private fun pos() = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
    }

    override fun getItemViewType(position: Int) = if (wide) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(if (viewType == 1) R.layout.card_wide else R.layout.card_media, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val card = items[position]
        // No crossfade on posters: fades cost extra frames while scrolling on this box
        holder.poster.load(if (wide) card.backdropUrl ?: card.posterUrl else card.posterUrl) { crossfade(false) }
        holder.title.text = card.title
        holder.title.alpha = if (holder.itemView.hasFocus()) 1f else 0.7f
        holder.meta.text = subtitles?.getOrNull(position) ?: buildString {
            if (card.year.isNotEmpty()) append(card.year)
            if (card.rating > 0) append("  ★ ${"%.1f".format(card.rating)}")
        }
        holder.badge.visibility = if (wide) View.GONE else View.VISIBLE
        holder.badge.text = when (card.type) { MediaType.MOVIE -> "MOVIE"; MediaType.SERIES -> "SERIES"; MediaType.ANIME -> "ANIME" }
        val tag = LangTags.label(card, LocalStore.audioLangs("${card.type}:${card.id}"))
        holder.lang.text = tag?.uppercase()
        holder.lang.visibility = if (tag != null && progress == null) View.VISIBLE else View.GONE
        val p = progress?.getOrNull(position)
        holder.bar.visibility = if (p != null && p > 0f) View.VISIBLE else View.GONE
        holder.bar.progress = ((p ?: 0f) * 1000).toInt()
    }
}

/** Columns that fit 132dp cards (+16dp gap) in the given width (dp). */
internal fun android.content.Context.posterColumns(sidePaddingDp: Int = 112): Int {
    val dm = resources.displayMetrics
    return ((dm.widthPixels / dm.density - sidePaddingDp) / 148f).toInt().coerceAtLeast(3)
}
