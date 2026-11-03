package com.futo.platformplayer.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.leanback.widget.HorizontalGridView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.futo.platformplayer.R
import com.futo.platformplayer.api.media.models.contents.IPlatformContent
import com.futo.platformplayer.api.media.models.video.IPlatformVideo
import com.futo.platformplayer.api.media.structures.IPager
import com.futo.platformplayer.engine.exceptions.ScriptLoginRequiredException
import com.futo.platformplayer.logging.Logger
import com.futo.platformplayer.models.ImageVariable
import com.futo.platformplayer.states.StateHistory
import com.futo.platformplayer.states.StatePlatform
import com.futo.platformplayer.toHumanNowDiffString
import com.futo.platformplayer.toHumanNumber
import com.futo.platformplayer.toHumanTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.OffsetDateTime

/** A creator tile: a followed channel or a creator search result. */
data class TvCreator(val name: String, val url: String, val thumbnail: String?, val hasNew: Boolean = false, val pluginId: String? = null)

/**
 * One shelf. Either [items] are known up front, or [load] fills them; a [pager]
 * returned by [load] is paged further as focus nears the end of the row.
 */
class TvRow(
    val title: String,
    val icon: ImageVariable? = null,
    val hero: Boolean = false,
    /** Per-card source badges; off for single-source shelves, whose header already says it. */
    val showSource: Boolean = icon == null,
    val creators: List<TvCreator>? = null,
    val load: (suspend () -> Any)? = null,
    /** The source this shelf shows, so a shelf that needs a login can offer one. */
    val clientId: String? = null,
) {
    enum class State { LOADING, READY, FAILED }

    val videos = mutableListOf<IPlatformContent>()
    var pager: IPager<IPlatformContent>? = null
    var state = if (load == null) State.READY else State.LOADING
    var started = false
    var loadingMore = false
    /** Last focused card, restored when the shelf scrolls back into view. */
    var selected = 0
    /** URLs another row already shows; set by [load] before it returns. */
    var exclude: Set<String> = emptySet()
    /** The source had videos, but the mixed row shows all of them. */
    var allShownElsewhere = false
    /** The last next-page request failed; the shelf ends in a "Try again" tile. */
    var moreFailed = false
    /** The source needs its account; logging in from the TV isn't possible yet. */
    var needsLogin = false
    /** A long run of shorts/posts hit the skip limit with pages left; the shelf ends in "Load more". */
    var moreStalled = false
    val hasTail get() = moreFailed || moreStalled
    internal var adapter: RecyclerView.Adapter<*>? = null
}

/** Shared knobs the rows need from their screen. */
class TvRowsContext(
    val scope: CoroutineScope,
    /** Opens the source's login; null where logging in isn't offered. */
    val onLogin: ((clientId: String) -> Unit)? = null,
    val onVideo: (IPlatformContent, TvRow) -> Unit,
    val onCreator: (TvCreator) -> Unit,
    /** Items newer than this get the "new" dot until they are focused once. */
    val newSince: OffsetDateTime? = null,
    val seen: MutableSet<String> = mutableSetOf(),
    val onRowLoaded: (TvRow) -> Unit = {},
)

/** Vertical list of shelves, with an optional fixed header view at the top. */
class TvRowsAdapter(private val ctx: TvRowsContext, private val header: View? = null) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    val rows = mutableListOf<TvRow>()

    fun setRows(newRows: List<TvRow>) {
        rows.clear(); rows.addAll(newRows)
        notifyDataSetChanged()
    }

    private val offset get() = if (header != null) 1 else 0
    override fun getItemCount() = rows.size + offset
    override fun getItemViewType(position: Int) = if (position < offset) TYPE_HEADER else TYPE_ROW

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == TYPE_HEADER) {
            (header!!.parent as? ViewGroup)?.removeView(header)
            // Plain params: the leanback layout manager swaps in its own.
            header.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            return object : RecyclerView.ViewHolder(header) {}
        }
        return RowHolder(LayoutInflater.from(parent.context).inflate(R.layout.tv_row, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is RowHolder) holder.bind(rows[position - offset])
    }

    inner class RowHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.row_icon)
        private val title: TextView = view.findViewById(R.id.row_title)
        private val status: TextView = view.findViewById(R.id.row_status)
        private val list: HorizontalGridView = view.findViewById(R.id.row_list)

        init {
            list.setHorizontalSpacing(view.resources.getDimensionPixelSize(R.dimen.tv_card_gap))
            // Leanback rows measure 0 tall unless told to wrap their cards.
            list.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
            // Keep the focused card pinned at the overscan margin, under the row title.
            list.windowAlignment = HorizontalGridView.WINDOW_ALIGN_NO_EDGE
            list.windowAlignmentOffset = view.resources.getDimensionPixelSize(R.dimen.tv_overscan_h)
            list.windowAlignmentOffsetPercent = HorizontalGridView.WINDOW_ALIGN_OFFSET_PERCENT_DISABLED
            list.itemAlignmentOffsetPercent = 0f
            list.setOnChildSelectedListener { _, _, position, _ -> (bound as? TvRow)?.selected = position }
        }

        private var bound: TvRow? = null

        fun bind(row: TvRow) {
            title.text = row.title
            if (row.icon != null) { icon.visibility = View.VISIBLE; row.icon.setImageView(icon) } else icon.visibility = View.GONE

            bound = row
            val adapter = when {
                row.state == TvRow.State.FAILED && row.needsLogin && row.clientId != null && ctx.onLogin != null ->
                    RetryAdapter(R.string.tv_log_in) { ctx.onLogin.invoke(row.clientId) }
                row.state == TvRow.State.FAILED -> RetryAdapter(R.string.tv_retry) { retry(row) }
                row.creators != null -> CreatorAdapter(row.creators, ctx)
                else -> VideoAdapter(row, ctx, this@TvRowsAdapter)
            }
            row.adapter = adapter
            list.adapter = adapter
            if (adapter.itemCount > 0) list.selectedPosition = row.selected.coerceIn(0, adapter.itemCount - 1)
            updateStatus(row)
            if (!row.started) start(row)
        }

        private fun retry(row: TvRow) {
            row.started = false
            row.moreFailed = false
            row.moreStalled = false
            row.state = TvRow.State.LOADING
            row.selected = 0
            val i = rows.indexOf(row)
            if (i >= 0) notifyItemChanged(i + offset)
        }

        private fun updateStatus(row: TvRow) {
            val r = itemView.resources
            status.text = when (row.state) {
                TvRow.State.LOADING -> r.getString(R.string.tv_row_loading)
                TvRow.State.FAILED -> r.getString(when {
                    !row.needsLogin -> R.string.tv_row_failed
                    row.clientId != null && ctx.onLogin != null -> R.string.tv_row_login
                    else -> R.string.tv_row_needs_login
                })
                TvRow.State.READY -> {
                    val newCount = row.videos.count { isNew(it, ctx) }
                    when {
                        row.creators == null && row.videos.isEmpty() && !row.moreStalled -> r.getString(R.string.tv_row_empty)
                        newCount > 0 && ctx.newSince != null -> r.getString(R.string.tv_new_count, newCount)
                        else -> ""
                    }
                }
            }
        }

        private fun start(row: TvRow) {
            val load = row.load ?: return
            row.started = true
            // Always dispatch: never notify the adapter from inside a bind or layout pass.
            ctx.scope.launch(Dispatchers.Main) {
                try {
                    val result = withContext(Dispatchers.IO) {
                        when (val r = load()) {
                            is IPager<*> -> {
                                @Suppress("UNCHECKED_CAST")
                                val p = r as IPager<IPlatformContent>
                                row.pager = p
                                val (items, sawPlayable) = nextVideos(p, row, fetchFirst = false)
                                row.allShownElsewhere = items.isEmpty() && sawPlayable && !p.hasMorePages()
                                row.moreStalled = items.isEmpty() && p.hasMorePages()
                                items
                            }
                            is List<*> -> r.filterIsInstance<IPlatformContent>().filter { playable(it) && it.url !in row.exclude }
                            else -> listOf()
                        }
                    }
                    row.videos.clear(); row.videos.addAll(result)
                    row.state = TvRow.State.READY
                } catch (e: Throwable) {
                    Logger.w(TAG, "Row '${row.title}' failed", e)
                    row.state = TvRow.State.FAILED
                    row.needsLogin = generateSequence(e) { it.cause }.any { it is ScriptLoginRequiredException }
                }
                ctx.onRowLoaded(row)
                val i = rows.indexOf(row)
                if (i < 0) return@launch
                // A shelf whose videos all sit in the mixed row drops out; a truly empty source stays and says so.
                if (row.state == TvRow.State.READY && row.allShownElsewhere && rows.size > 1) {
                    rows.removeAt(i); notifyItemRemoved(i + offset)
                } else notifyItemChanged(i + offset)
            }
        }
    }

    internal fun loadMore(row: TvRow) {
        val pager = row.pager ?: return
        if (row.loadingMore || row.hasTail || !pager.hasMorePages()) return
        row.loadingMore = true
        ctx.scope.launch(Dispatchers.Main) {
            try {
                val (more, _) = withContext(Dispatchers.IO) { nextVideos(pager, row, fetchFirst = true) }
                val start = row.videos.size
                row.videos.addAll(more)
                row.adapter?.notifyItemRangeInserted(start, more.size)
                if (more.isEmpty() && pager.hasMorePages()) {
                    row.moreStalled = true
                    row.adapter?.notifyItemInserted(row.videos.size)
                }
            } catch (e: Throwable) {
                Logger.w(TAG, "Row '${row.title}' next page failed", e)
                row.moreFailed = true
                row.adapter?.notifyItemInserted(row.videos.size)
            } finally {
                row.loadingMore = false
            }
        }
    }

    internal fun retryMore(row: TvRow) {
        row.moreFailed = false
        row.moreStalled = false
        row.adapter?.notifyItemRemoved(row.videos.size)
        loadMore(row)
    }

    /**
     * Returns the next playable, not-yet-shown videos, paging past pages that were all shorts,
     * posts or duplicates. Also reports whether any playable video was seen at all.
     */
    private fun nextVideos(p: IPager<IPlatformContent>, row: TvRow, fetchFirst: Boolean): Pair<List<IPlatformContent>, Boolean> {
        if (fetchFirst) p.nextPage()
        var sawPlayable = false
        var pages = 0
        while (true) {
            val page = p.getResults().filter(::playable)
            sawPlayable = sawPlayable || page.isNotEmpty()
            val fresh = page.filter { it.url !in row.exclude }
            if (fresh.isNotEmpty() || !p.hasMorePages() || ++pages > MAX_SKIP_PAGES) return fresh to sawPlayable
            p.nextPage()
        }
    }

    companion object {
        private const val TAG = "TvRows"
        private const val TYPE_HEADER = 0
        private const val TYPE_ROW = 1
        private const val MAX_SKIP_PAGES = 10

        /** Videos and streams only; posts and articles have no TV view yet. */
        fun playable(c: IPlatformContent) = c is IPlatformVideo && !c.isShort

        fun isNew(c: IPlatformContent, ctx: TvRowsContext): Boolean {
            val since = ctx.newSince ?: return false
            val at = c.datetime ?: return false
            return at.isAfter(since) && c.url !in ctx.seen
        }
    }
}

/** One focusable "Try again" in place of a shelf that failed to load. */
private class RetryAdapter(private val label: Int, private val onRetry: () -> Unit) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    override fun getItemCount() = 1
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.tv_row_retry, parent, false)) {}
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        (holder.itemView as TextView).setText(label)
        holder.itemView.setOnClickListener { onRetry() }
    }
}

private class VideoAdapter(private val row: TvRow, private val ctx: TvRowsContext, private val rows: TvRowsAdapter) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    override fun getItemCount() = row.videos.size + if (row.hasTail) 1 else 0
    override fun getItemViewType(position: Int) = if (position >= row.videos.size) TYPE_RETRY else TYPE_VIDEO

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == TYPE_RETRY) return object : RecyclerView.ViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.tv_row_retry, parent, false)) {}
        val v = LayoutInflater.from(parent.context).inflate(R.layout.tv_card_video, parent, false)
        val w = parent.resources.getDimensionPixelSize(if (row.hero) R.dimen.tv_card_hero else R.dimen.tv_card)
        v.layoutParams = v.layoutParams.apply { width = w }
        val inset = 2 * parent.resources.getDimensionPixelSize(R.dimen.tv_ring_inset)
        v.findViewById<View>(R.id.card_frame).apply { layoutParams = layoutParams.apply { height = (w - inset) * 9 / 16 + inset } }
        v.findViewById<View>(R.id.card_thumb).clipToOutline = true
        return Holder(v)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder !is Holder) {
            (holder.itemView as TextView).setText(if (row.moreFailed) R.string.tv_retry else R.string.tv_load_more)
            holder.itemView.setOnClickListener { rows.retryMore(row) }
            return
        }
        holder.bind(row.videos[position])
        if (position >= row.videos.size - 4) rows.loadMore(row)
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is Holder) Glide.with(holder.thumb).clear(holder.thumb)
    }

    companion object {
        private const val TYPE_VIDEO = 0
        private const val TYPE_RETRY = 1
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.card_thumb)
        private val badge: View = view.findViewById(R.id.card_badge)
        private val badgeIcon: ImageView = view.findViewById(R.id.card_badge_icon)
        private val badgeText: TextView = view.findViewById(R.id.card_badge_text)
        private val duration: TextView = view.findViewById(R.id.card_duration)
        private val newDot: View = view.findViewById(R.id.card_new)
        private val progress: ProgressBar = view.findViewById(R.id.card_progress)
        private val title: TextView = view.findViewById(R.id.card_title)
        private val meta: TextView = view.findViewById(R.id.card_meta)

        fun bind(c: IPlatformContent) {
            val video = c as IPlatformVideo
            title.text = c.name
            meta.text = listOfNotNull(
                c.author.name,
                if (video.isLive) null else c.datetime?.toHumanNowDiffString()?.let { "$it ago" },
                video.viewCount.takeIf { it > 0 }?.let { "${it.toHumanNumber()} views" },
            ).joinToString(" · ")

            Glide.with(thumb).load(video.thumbnails.getHQThumbnail()).into(thumb)

            val client = c.id.pluginId?.let { id -> StatePlatform.instance.getAvailableClients().find { it.id == id } }
            if (client != null && row.showSource) {
                badge.visibility = View.VISIBLE
                badgeText.text = client.name
                client.icon?.setImageView(badgeIcon) ?: run { badgeIcon.visibility = View.GONE }
            } else badge.visibility = View.GONE

            when {
                video.isLive -> {
                    duration.visibility = View.VISIBLE
                    duration.setText(R.string.tv_live)
                    duration.setBackgroundResource(R.drawable.tv_chip_live)
                }
                video.duration > 0 -> {
                    duration.visibility = View.VISIBLE
                    duration.text = video.duration.toHumanTime(false)
                    duration.setBackgroundResource(R.drawable.tv_chip)
                }
                else -> duration.visibility = View.GONE
            }

            val watched = if (video.duration > 0) StateHistory.instance.getHistoryPosition(c.url) else 0L
            if (watched > 0) {
                progress.visibility = View.VISIBLE
                progress.progress = (watched * 1000 / video.duration).toInt().coerceIn(0, 1000) // history stores seconds
            } else progress.visibility = View.GONE

            newDot.visibility = if (TvRowsAdapter.isNew(c, ctx)) View.VISIBLE else View.GONE

            itemView.setOnClickListener { ctx.onVideo(c, row) }
            // Hold OK for the creator's channel.
            itemView.setOnLongClickListener { ctx.onCreator(TvCreator(c.author.name, c.author.url, c.author.thumbnail, pluginId = c.id.pluginId)); true }
            itemView.setOnFocusChangeListener { v, focused ->
                TvFocus.scale(v, focused)
                // "New" clears once the viewer has looked and moved on, not when focus merely lands.
                if (!focused && newDot.visibility == View.VISIBLE) {
                    ctx.seen.add(c.url)
                    newDot.animate().alpha(0f).setDuration(400).withEndAction { newDot.visibility = View.GONE; newDot.alpha = 1f }
                }
            }
        }
    }
}

private class CreatorAdapter(private val creators: List<TvCreator>, private val ctx: TvRowsContext) : RecyclerView.Adapter<CreatorAdapter.Holder>() {
    override fun getItemCount() = creators.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.tv_card_creator, parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(creators[position])

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val avatar: ImageView = view.findViewById(R.id.creator_avatar)
        private val name: TextView = view.findViewById(R.id.creator_name)
        private val newDot: View = view.findViewById(R.id.creator_new)
        private val source: ImageView = view.findViewById(R.id.creator_source)

        fun bind(c: TvCreator) {
            name.text = c.name
            // Same name on several platforms is common; the source disc tells them apart.
            val client = c.pluginId?.let { id -> StatePlatform.instance.getAvailableClients().find { it.id == id } }
            source.visibility = if (client?.icon != null) View.VISIBLE else View.GONE
            client?.icon?.setImageView(source)
            itemView.contentDescription = listOfNotNull(c.name, client?.name).joinToString(", ")
            newDot.visibility = if (c.hasNew) View.VISIBLE else View.GONE
            Glide.with(avatar).load(c.thumbnail).placeholder(R.drawable.placeholder_channel_thumbnail).circleCrop().into(avatar)
            itemView.setOnClickListener { ctx.onCreator(c) }
            itemView.setOnFocusChangeListener { v, focused -> TvFocus.scale(v, focused) }
        }
    }
}

object TvFocus {
    fun scale(v: View, focused: Boolean) {
        val s = if (focused) 1.06f else 1f
        v.animate().scaleX(s).scaleY(s).setDuration(150).start()
        v.translationZ = if (focused) 8f else 0f
    }
}
