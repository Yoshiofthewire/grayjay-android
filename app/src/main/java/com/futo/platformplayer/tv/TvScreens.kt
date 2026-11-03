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
import com.futo.platformplayer.api.media.structures.IPager
import com.futo.platformplayer.logging.Logger
import com.futo.platformplayer.states.StateHistory
import com.futo.platformplayer.states.StatePlatform
import com.futo.platformplayer.states.StateSubscriptions
import com.futo.platformplayer.toHumanNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
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
        val rows = mixedSourceRows(act, string(R.string.tv_row_across), clients) { it.getHome() }
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
                showRows(leadAndShelves(string(R.string.tv_row_latest), feed, chronological = true) + TvRow(string(R.string.tv_row_following), creators = following))
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

        fun lastSeen(c: Context): OffsetDateTime {
            val ms = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_SEEN, 0)
            // First visit: mark the last two days as new rather than the whole history.
            return if (ms == 0L) OffsetDateTime.now().minusDays(2) else OffsetDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC)
        }
    }
}

/**
 * A lead row, then one shelf per source holding everything the lead row doesn't show, so every
 * loaded video stays reachable and none appears twice. With a single source it is one row.
 * [chronological] keeps the lead in feed order (Subscriptions); otherwise it round-robins sources.
 */
fun leadAndShelves(title: String, items: List<IPlatformContent>, chronological: Boolean): List<TvRow> {
    val groups = items.groupBy { it.id.pluginId }
    if (groups.size <= 1) return listOf(TvRow(title, hero = true, load = { items }))
    val lead = if (chronological) items.take(LEAD_COUNT) else TvHomeScreen.interleave(groups.values.map { it.take(PER_SOURCE_IN_MIX) })
    val leadUrls = lead.map { it.url }.toSet()
    val clients = StatePlatform.instance.getAvailableClients().associateBy { it.id }
    return listOf(TvRow(title, hero = true, load = { lead })) + groups.mapNotNull { (id, list) ->
        val rest = list.filter { it.url !in leadUrls }
        val c = id?.let { clients[it] }
        if (rest.isEmpty()) null else TvRow(c?.name ?: "More", icon = c?.icon, load = { rest })
    }
}

private const val LEAD_COUNT = 24

private const val PER_SOURCE_IN_MIX = 4

typealias SourcePage = Pair<IPager<IPlatformContent>, List<IPlatformContent>>

/** Starts one first-page request per source. */
fun fetchFirstPages(act: TvActivity, clients: List<IPlatformClient>, fetch: (IPlatformClient) -> IPager<IPlatformContent>) =
    clients.associateWith { c -> act.lifecycleScope.async(Dispatchers.IO) { fetch(c).let { it to it.getResults().toList() } } }

/**
 * A mixed row of the first few videos per source, then one paging shelf per source that skips
 * what the mixed row shows. With one source it is just that source's shelf. A row's first load
 * shares the per-source request; only "Try again" asks the source again.
 */
fun mixedSourceRows(
    act: TvActivity,
    mixedTitle: String,
    clients: List<IPlatformClient>,
    firstPages: Map<IPlatformClient, Deferred<SourcePage>>? = null,
    fetch: (IPlatformClient) -> IPager<IPlatformContent>,
): List<TvRow> {
    val first = firstPages ?: fetchFirstPages(act, clients, fetch)
    suspend fun page(c: IPlatformClient, retry: Boolean): SourcePage =
        if (!retry) first.getValue(c).await()
        else withContext(Dispatchers.IO) { fetch(c).let { it to it.getResults().toList() } }
    fun lead(items: List<IPlatformContent>) = items.filter(TvRowsAdapter::playable).take(PER_SOURCE_IN_MIX)
    val mixed = clients.size > 1
    // What the mixed row actually displays. Replaced whole, never mutated, so other threads read a complete set.
    val shown = java.util.concurrent.atomic.AtomicReference(emptySet<String>())
    var mixedAttempts = 0
    lateinit var mixedRow: TvRow
    mixedRow = TvRow(mixedTitle, hero = true, load = {
        val retry = mixedAttempts++ > 0
        val pages = clients.mapNotNull { c -> runCatching { lead(page(c, retry).second) }.getOrNull() }
        check(pages.isNotEmpty()) { "every source failed" }
        TvHomeScreen.interleave(pages).also { items ->
            shown.set(items.map { it.url }.toSet())
            // Every first page was shorts or posts: the shelves below carry on, so the empty mixed row drops out.
            mixedRow.allShownElsewhere = items.isEmpty()
        }
    })
    val shelves = clients.map { c ->
        lateinit var row: TvRow
        var attempts = 0
        row = TvRow(c.name, icon = c.icon, hero = !mixed, load = {
            val (pager, items) = page(c, attempts++ > 0)
            // Before the mixed row has loaded, its lead for this source is the same first page.
            if (mixed) row.exclude = shown.get().ifEmpty { lead(items).map { it.url }.toSet() }
            pager
        })
        row
    }
    return if (mixed) listOf(mixedRow) + shelves else shelves
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
                val clients = StatePlatform.instance.getSortedEnabledClient()
                val firstPages = fetchFirstPages(act, clients) { StatePlatform.instance.search(q, clientIds = listOf(it.id)) }
                val creators = withContext(Dispatchers.IO) {
                    runCatching { StatePlatform.instance.searchChannels(q).getResults().take(20) }.getOrDefault(listOf())
                }
                // Wait for every source so "no results" is only said when it is true.
                val pages = firstPages.mapValues { (_, d) -> runCatching { d.await() }.getOrNull() }
                if (q != lastQuery) return@launch
                // A first page of only shorts or posts still counts when more pages follow.
                val withResults = clients.filter { c -> pages[c]?.let { (pager, items) -> items.any(TvRowsAdapter::playable) || pager.hasMorePages() } == true }
                val failed = clients.filter { pages[it] == null }
                // Each source keeps its own pager, so every shelf pages on as you scroll.
                val results = mixedSourceRows(act, string(R.string.tv_row_results), withResults + failed, firstPages) {
                    StatePlatform.instance.search(q, clientIds = listOf(it.id))
                }
                val rows = mutableListOf<TvRow>()
                rows.addAll(results.take(1))
                if (creators.isNotEmpty()) rows.add(TvRow(string(R.string.tv_row_creators), creators = creators.map { TvCreator(it.name, it.url, it.thumbnail, pluginId = it.id.pluginId) }))
                rows.addAll(results.drop(1))
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
        // Checked when the post runs: a phone search may arrive in between and must win.
        input.post { if (lastQuery == null) input.requestFocus() }
    }

    companion object { private const val TAG = "TvSearch" }
}

class TvChannelScreen(act: TvActivity, val url: String) : TvRowsScreen(act, header = LayoutInflater.from(act).inflate(R.layout.tv_header_channel, null)) {
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
