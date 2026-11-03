package com.futo.platformplayer.tv

import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.leanback.widget.VerticalGridView
import com.bumptech.glide.Glide
import com.futo.platformplayer.R
import com.futo.platformplayer.api.media.IPlatformClient
import com.futo.platformplayer.api.media.models.channels.IPlatformChannel
import com.futo.platformplayer.api.media.models.contents.IPlatformContent
import com.futo.platformplayer.api.media.platforms.js.JSClient
import com.futo.platformplayer.logging.Logger
import com.futo.platformplayer.states.StateHistory
import com.futo.platformplayer.states.StatePlatform
import com.futo.platformplayer.states.StateSubscriptions
import com.futo.platformplayer.toHumanNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

abstract class TvScreen(val act: TvActivity) {
    abstract val view: View
    /** Rail item that stays highlighted while this screen shows. */
    abstract val tab: Int
    open fun onShown() {}
    open fun onHidden() {}
    open fun onDestroyed() {}
}

/** A screen made of shelves, with message states for loading, empty and errors. */
abstract class TvRowsScreen(act: TvActivity, private val header: View? = null, newSince: OffsetDateTime? = null) : TvScreen(act) {
    protected val headerView: View get() = header!!
    final override val view: View = LayoutInflater.from(act).inflate(R.layout.tv_screen_rows, null)
    protected val grid: VerticalGridView = view.findViewById(R.id.screen_rows)
    private val message: View = view.findViewById(R.id.screen_message)
    protected val ctx = TvRowsContext(act.lifecycleScope, act::play, { act.openChannel(it.url) }, newSince, onRowLoaded = ::onRowLoaded)
    protected val adapter = TvRowsAdapter(ctx, header)
    private var focusOnLoad = false

    init {
        grid.adapter = adapter
        grid.setVerticalSpacing(view.resources.getDimensionPixelSize(R.dimen.tv_card_gap))
        grid.windowAlignment = VerticalGridView.WINDOW_ALIGN_LOW_EDGE
        grid.windowAlignmentOffset = view.resources.getDimensionPixelSize(R.dimen.tv_overscan_v)
        grid.windowAlignmentOffsetPercent = VerticalGridView.WINDOW_ALIGN_OFFSET_PERCENT_DISABLED
        grid.itemAlignmentOffsetPercent = 0f
    }

    protected fun showRows(rows: List<TvRow>) {
        message.visibility = View.GONE
        grid.visibility = View.VISIBLE
        adapter.setRows(rows)
        focusOnLoad = act.isContentFocusWanted()
    }

    /** Moves focus into the first loaded card when the user was waiting on this screen. */
    private fun onRowLoaded(row: TvRow) {
        if (!focusOnLoad || row.videos.isEmpty() && row.creators.isNullOrEmpty()) return
        focusOnLoad = false
        if (act.isContentFocusWanted()) grid.post {
            // Land on the first shelf, not the header (a search box would trap the D-pad).
            if (header != null) grid.selectedPosition = 1
            if (grid.requestFocus()) act.contentFocused()
        }
    }

    protected fun showMessage(title: String, body: String? = null, action: String? = null, keepHeader: Boolean = false, onAction: (() -> Unit)? = null) {
        if (!keepHeader) grid.visibility = View.GONE
        message.visibility = View.VISIBLE
        message.findViewById<TextView>(R.id.screen_message_title).text = title
        message.findViewById<TextView>(R.id.screen_message_body).apply { text = body; visibility = if (body == null) View.GONE else View.VISIBLE }
        message.findViewById<TextView>(R.id.screen_message_action).apply {
            visibility = if (action == null) View.GONE else View.VISIBLE
            text = action
            setOnClickListener { onAction?.invoke() }
            if (action != null && act.isContentFocusWanted()) post { if (requestFocus()) act.contentFocused() }
        }
    }

    protected fun string(id: Int, vararg args: Any) = act.getString(id, *args)
}

/** Shelves for each source, after one row that mixes them all. */
class TvHomeScreen(act: TvActivity) : TvRowsScreen(act) {
    override val tab = TvActivity.TAB_HOME

    init { load() }

    fun load() {
        val clients = StatePlatform.instance.getSortedEnabledClient().filter { (it as? JSClient)?.enableInHome ?: true }
        if (clients.isEmpty()) {
            showMessage(string(R.string.tv_no_sources_title), string(R.string.tv_no_sources_body), string(R.string.tv_phone)) { act.openCompanion() }
            return
        }
        val firstPages = clients.associateWith { c ->
            act.lifecycleScope.async(Dispatchers.IO) { c.getHome().let { it to it.getResults().toList() } }
        }
        // First load shares one request per source; a retry after a failure asks again.
        suspend fun page(c: IPlatformClient) = try { firstPages.getValue(c).await() } catch (e: Throwable) {
            withContext(Dispatchers.IO) { c.getHome().let { it to it.getResults().toList() } }
        }
        val mixed = clients.size > 1
        fun lead(first: List<IPlatformContent>) = first.filter(TvRowsAdapter::playable).take(PER_SOURCE_IN_MIX)
        val across = TvRow(string(R.string.tv_row_across), hero = true, load = {
            val pages = clients.mapNotNull { c -> runCatching { lead(page(c).second) }.getOrNull() }
            check(pages.isNotEmpty()) { "every source failed" }
            interleave(pages)
        })
        // Shelves skip what the mixed row already shows, so nothing appears twice.
        val shelves = clients.map { c ->
            lateinit var row: TvRow
            row = TvRow(c.name, icon = c.icon, hero = !mixed, load = {
                val (pager, first) = page(c)
                if (mixed) row.exclude = lead(first).map { it.url }.toSet()
                pager
            })
            row
        }
        // With one source the mixed row would only repeat its shelf.
        val rows = if (clients.size == 1) shelves else listOf(across) + shelves
        act.lifecycleScope.launch {
            val resume = withContext(Dispatchers.IO) { runCatching { continueWatching() }.getOrDefault(listOf()) }
            showRows(if (resume.isEmpty()) rows else listOf(TvRow(string(R.string.tv_row_continue), load = { resume })) + rows)
        }
    }

    /** Started but unfinished videos, newest first. */
    private fun continueWatching(): List<IPlatformContent> = StateHistory.instance.getHistoryPager().getResults()
        .filter { it.position > 30 && (it.video.duration <= 0 || it.position < it.video.duration * 0.9) }
        .map { it.video }
        .take(20)

    companion object {
        private const val PER_SOURCE_IN_MIX = 4

        /** Round-robin so no single source dominates the mixed row. */
        fun <T> interleave(lists: List<List<T>>): List<T> {
            val out = mutableListOf<T>()
            val max = lists.maxOfOrNull { it.size } ?: 0
            for (i in 0 until max) for (l in lists) if (i < l.size) out.add(l[i])
            return out
        }
    }
}

/** Subscriptions as shelves: everything new, then per source, then the people you follow. */
class TvSubscriptionsScreen(act: TvActivity) : TvRowsScreen(act, newSince = lastSeen(act)) {
    override val tab = TvActivity.TAB_SUBSCRIPTIONS
    /** Follows changed while this screen was hidden; reload when it shows again. */
    var dirty = false

    override fun onShown() {
        if (dirty) { dirty = false; load() }
    }

    init { load() }

    fun load() {
        val subs = StateSubscriptions.instance.getSubscriptions()
        if (subs.isEmpty()) {
            showMessage(string(R.string.tv_no_subs_title), string(R.string.tv_no_subs_body), string(R.string.tv_phone)) { act.openCompanion() }
            return
        }
        showMessage(string(R.string.tv_row_loading))
        act.lifecycleScope.launch {
            try {
                val feed = withContext(Dispatchers.IO) {
                    val pager = StateSubscriptions.instance.getGlobalSubscriptionFeed(act.lifecycleScope, false)
                    val items = pager.getResults().toMutableList()
                    while (items.size < 150 && pager.hasMorePages()) { pager.nextPage(); items.addAll(pager.getResults()) }
                    items.filter(TvRowsAdapter::playable)
                }
                val newAuthors = feed.filter { TvRowsAdapter.isNew(it, ctx) }.map { it.author.url }.toSet()
                val following = subs.sortedBy { it.channel.name.lowercase() }
                    .map { TvCreator(it.channel.name, it.channel.url, it.channel.thumbnail, it.channel.url in newAuthors, it.channel.id.pluginId) }
                    .sortedByDescending { it.hasNew }
                // Latest leads; per-source shelves continue with what it doesn't show.
                val latest = feed.take(LATEST_COUNT)
                showRows(
                    listOf(TvRow(string(R.string.tv_row_latest), hero = true, load = { latest })) +
                        bySource(feed.drop(LATEST_COUNT)) +
                        TvRow(string(R.string.tv_row_following), creators = following)
                )
            } catch (e: Throwable) {
                Logger.e(TAG, "Subscriptions failed", e)
                showMessage(string(R.string.tv_load_failed_title), string(R.string.tv_load_failed_body), string(R.string.tv_retry)) { load() }
            }
        }
    }

    /** Called when the viewer leaves the Subscriptions tab (not when a channel opens on top of it). */
    fun markSeen() {
        act.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putLong(KEY_SEEN, System.currentTimeMillis()) }
    }

    companion object {
        private const val TAG = "TvSubscriptions"
        private const val PREFS = "tv"
        private const val KEY_SEEN = "subs_seen_at"
        private const val LATEST_COUNT = 24

        fun lastSeen(c: Context): OffsetDateTime {
            val ms = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_SEEN, 0)
            // First visit: mark the last two days as new rather than the whole history.
            return if (ms == 0L) OffsetDateTime.now().minusDays(2) else OffsetDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC)
        }
    }
}

/** Splits mixed content into one shelf per source; nothing when it all came from one source. */
fun bySource(items: List<IPlatformContent>): List<TvRow> {
    val clients = StatePlatform.instance.getAvailableClients().associateBy { it.id }
    val groups = items.groupBy { it.id.pluginId }.filter { (id, list) -> id != null && list.size > 1 && clients[id] != null }
    if (groups.size < 2) return listOf()
    return groups.map { (id, list) -> clients.getValue(id!!).let { c -> TvRow(c.name, icon = c.icon, load = { list }) } }
}

class TvSearchScreen(act: TvActivity) : TvRowsScreen(act, header = LayoutInflater.from(act).inflate(R.layout.tv_header_search, null)) {
    override val tab = TvActivity.TAB_SEARCH
    private val input: EditText = headerView.findViewById(R.id.search_input)
    private var lastQuery: String? = null

    init {
        input.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) { search(input.text.toString()); true } else false
        }
        headerView.findViewById<View>(R.id.search_phone).setOnClickListener { act.openCompanion() }
        showRows(listOf())
        showMessage(string(R.string.tv_search_empty_title), string(R.string.tv_search_empty_body), keepHeader = true)
    }

    fun search(raw: String) {
        val q = raw.trim()
        if (q.isEmpty() || q == lastQuery) return
        lastQuery = q
        input.setText(q)
        act.hideKeyboard(input)
        showRows(listOf())
        showMessage(string(R.string.tv_row_loading), keepHeader = true)
        act.lifecycleScope.launch {
            try {
                val (creators, pager) = withContext(Dispatchers.IO) {
                    val creators = async { runCatching { StatePlatform.instance.searchChannels(q).getResults().take(20) }.getOrDefault(listOf()) }
                    val videos = StatePlatform.instance.search(q)
                    creators.await() to videos
                }
                if (q != lastQuery) return@launch
                val first = pager.getResults().filter(TvRowsAdapter::playable)
                val rows = mutableListOf<TvRow>()
                if (first.isNotEmpty()) rows.add(TvRow(string(R.string.tv_row_results), hero = true, load = { pager }))
                if (creators.isNotEmpty()) rows.add(TvRow(string(R.string.tv_row_creators), creators = creators.map { TvCreator(it.name, it.url, it.thumbnail, pluginId = it.id.pluginId) }))
                rows.addAll(bySource(first))
                if (rows.isEmpty()) {
                    showRows(listOf())
                    showMessage(string(R.string.tv_search_none, q), keepHeader = true)
                } else showRows(rows)
            } catch (e: Throwable) {
                Logger.e(TAG, "Search failed", e)
                lastQuery = null // let the same query run again
                showMessage(string(R.string.tv_load_failed_title), string(R.string.tv_search_failed), string(R.string.tv_retry), keepHeader = true) { search(q) }
            }
        }
    }

    override fun onShown() {
        if (lastQuery == null) input.post { input.requestFocus() }
    }

    companion object { private const val TAG = "TvSearch" }
}

class TvChannelScreen(act: TvActivity, private val url: String) : TvRowsScreen(act, header = LayoutInflater.from(act).inflate(R.layout.tv_header_channel, null)) {
    // Fixed at open time: the tab this channel was opened from.
    override val tab = act.currentTab
    private val follow: TextView = headerView.findViewById(R.id.channel_follow)
    private var channel: IPlatformChannel? = null

    init {
        follow.visibility = View.INVISIBLE
        follow.setOnClickListener { toggleFollow() }
        load()
    }

    private fun load() {
        showRows(listOf())
        act.lifecycleScope.launch {
            try {
                val c = withContext(Dispatchers.IO) { StatePlatform.instance.getChannel(url).await() }
                channel = c
                headerView.findViewById<TextView>(R.id.channel_name).text = c.name
                val client = c.id.pluginId?.let { id -> StatePlatform.instance.getAvailableClients().find { it.id == id } }
                headerView.findViewById<TextView>(R.id.channel_meta).text = listOfNotNull(
                    client?.name, c.subscribers.takeIf { it > 0 }?.let { string(R.string.tv_subscribers, it.toHumanNumber()) }
                ).joinToString(" · ")
                client?.icon?.setImageView(headerView.findViewById(R.id.channel_source_icon))
                Glide.with(act).load(c.thumbnail).placeholder(R.drawable.placeholder_channel_thumbnail).circleCrop()
                    .into(headerView.findViewById<ImageView>(R.id.channel_avatar))
                updateFollow()
                follow.visibility = View.VISIBLE
                showRows(listOf(TvRow(string(R.string.tv_row_videos), hero = true, showSource = false, load = { StatePlatform.instance.getChannelContent(c.url) })))
                if (act.isContentFocusWanted()) follow.post { if (follow.requestFocus()) act.contentFocused() }
            } catch (e: Throwable) {
                Logger.e(TAG, "Channel failed", e)
                showMessage(string(R.string.tv_load_failed_title), string(R.string.tv_load_failed_body), string(R.string.tv_retry)) { load() }
            }
        }
    }

    private fun updateFollow() {
        val on = channel?.let { StateSubscriptions.instance.isSubscribed(it) } ?: false
        follow.setText(if (on) R.string.tv_following else R.string.tv_follow)
        follow.setBackgroundResource(if (on) R.drawable.tv_button else R.drawable.tv_button_primary)
    }

    private fun toggleFollow() {
        val c = channel ?: return
        if (StateSubscriptions.instance.isSubscribed(c)) {
            StateSubscriptions.instance.removeSubscription(c.url, true)
            act.toast(string(R.string.tv_unfollowed_toast, c.name))
        } else {
            StateSubscriptions.instance.addSubscription(c)
            act.toast(string(R.string.tv_followed_toast, c.name))
        }
        updateFollow()
    }

    companion object { private const val TAG = "TvChannel" }
}

class TvCompanionScreen(act: TvActivity) : TvScreen(act) {
    override val tab = TvActivity.TAB_PHONE
    override val view: View = LayoutInflater.from(act).inflate(R.layout.tv_screen_companion, null)
    private val status: TextView = view.findViewById(R.id.companion_status)
    private val dot: View = view.findViewById(R.id.companion_dot)

    init {
        view.findViewById<View>(R.id.companion_stop).setOnClickListener { act.stopCompanion(); act.showTab(TvActivity.TAB_HOME) }
    }

    override fun onShown() {
        val url = act.startCompanion()
        val qr = view.findViewById<ImageView>(R.id.companion_qr)
        val urlText = view.findViewById<TextView>(R.id.companion_url)
        if (url == null) {
            qr.setImageDrawable(null)
            urlText.text = ""
            status.setText(R.string.tv_companion_no_network)
        } else {
            qr.setImageBitmap(TvQr.encode(url, 512))
            urlText.text = act.getString(R.string.tv_companion_open_hint, url)
            setStatus(act.companionLastAction)
        }
        view.findViewById<View>(R.id.companion_stop).post { view.findViewById<View>(R.id.companion_stop).requestFocus() }
    }

    fun setStatus(lastAction: String?, connected: Boolean = act.phoneConnected) {
        status.text = when {
            lastAction != null -> act.getString(R.string.tv_companion_active, lastAction)
            connected -> act.getString(R.string.tv_companion_connected)
            else -> act.getString(R.string.tv_companion_waiting)
        }
        // Grey while waiting, green once a phone has the page open.
        dot.setBackgroundResource(if (connected) R.drawable.tv_status_on else R.drawable.tv_status_off)
    }
}
