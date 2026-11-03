package com.futo.platformplayer.tv

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.leanback.widget.VerticalGridView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.futo.platformplayer.R
import com.futo.platformplayer.api.http.ManagedHttpClient
import com.futo.platformplayer.api.media.platforms.js.SourcePluginConfig
import com.futo.platformplayer.logging.Logger
import com.futo.platformplayer.states.StatePlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object TvOnboarding {
    private const val PREFS = "tv"
    private const val KEY_DONE = "onboarded"
    fun done(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DONE, false)
    fun markDone(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_DONE, true) }
}

/** FUTO's official source list, read from plugins.grayjay.app. */
object TvSourceCatalog {
    const val SITE = "https://plugins.grayjay.app/"
    private const val MAX_PAGE_BYTES = 2L * 1024 * 1024
    private const val MAX_CONFIG_BYTES = 256L * 1024
    private val LINK = Regex("""grayjay://plugin/(https://plugins\.grayjay\.app/[A-Za-z0-9._/-]+\.json)""")

    class Entry(val url: String, val config: SourcePluginConfig)

    /** Config URLs from the site's "grayjay://plugin/<url>" links; only FUTO's own host, in page order. */
    fun parseConfigUrls(html: String): List<String> = LINK.findAll(html).map { it.groupValues[1] }.distinct().toList()

    suspend fun load(): List<Entry> = withContext(Dispatchers.IO) {
        val client = ManagedHttpClient().apply { setTimeout(10_000) }
        val html = client.get(SITE).use { resp ->
            check(resp.isOk) { "plugins.grayjay.app answered ${resp.code}" }
            resp.body?.byteStream()?.use { TvActivity.readCapped(it, MAX_PAGE_BYTES) }?.toString(Charsets.UTF_8)
        } ?: error("plugins.grayjay.app sent nothing")
        val urls = parseConfigUrls(html)
        check(urls.isNotEmpty()) { "no sources listed" }
        coroutineScope {
            urls.map { url ->
                async {
                    runCatching {
                        client.get(url).use { resp ->
                            val json = resp.body?.byteStream()?.use { TvActivity.readCapped(it, MAX_CONFIG_BYTES) }?.toString(Charsets.UTF_8)
                            if (!resp.isOk || json.isNullOrEmpty()) null else Entry(url, SourcePluginConfig.fromJson(json, url))
                        }
                    }.onFailure { Logger.w("TvSourceCatalog", "Skipping $url", it) }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }
    }
}

/**
 * Pick official sources with the remote. Step 1 of first-run setup, and reachable later from an
 * empty Home. Choosing a source and pressing "Add" on the TV is the consent; each one is still
 * checked to be FUTO-signed before it installs.
 */
class TvSourcesScreen(act: TvActivity, private val onboarding: Boolean) : TvScreen(act) {
    override val tab = TvActivity.TAB_HOME
    override val view: View = LayoutInflater.from(act).inflate(R.layout.tv_screen_sources, null)
    private val grid: VerticalGridView = view.findViewById(R.id.sources_grid)
    private val status: TextView = view.findViewById(R.id.sources_status)
    private val add: TextView = view.findViewById(R.id.sources_add)
    private val skip: TextView = view.findViewById(R.id.sources_skip)

    private enum class State { IDLE, ADDING, ADDED, FAILED }
    private class Item(val entry: TvSourceCatalog.Entry, val installed: Boolean) {
        var picked = false
        var state = if (installed) State.ADDED else State.IDLE
    }

    private val items = mutableListOf<Item>()
    private val adapter = SourceAdapter()
    private var busy = false

    init {
        grid.adapter = adapter
        grid.setNumColumns(4)
        val gap = act.resources.getDimensionPixelSize(R.dimen.tv_card_gap)
        grid.setHorizontalSpacing(gap); grid.setVerticalSpacing(gap)
        skip.setText(if (onboarding) R.string.tv_sources_skip else R.string.tv_sources_back)
        skip.setOnClickListener { if (!busy) act.sourcesDone(onboarding) }
        add.setOnClickListener { addPicked() }
        load()
    }

    private fun load() {
        status.visibility = View.VISIBLE
        status.setText(R.string.tv_sources_loading)
        add.visibility = View.GONE
        act.lifecycleScope.launch {
            try {
                // The app ships these sources bundled but switched off; "Added" means switched on.
                val installed = StatePlatform.instance.getEnabledClients().map { it.id }.toSet()
                val entries = TvSourceCatalog.load()
                items.clear()
                items.addAll(entries.map { Item(it, it.config.id in installed) })
                status.visibility = View.GONE
                add.visibility = View.VISIBLE
                updateAdd()
                adapter.notifyDataSetChanged()
                grid.post { grid.requestFocus() }
            } catch (e: Throwable) {
                Logger.e(TAG, "Source list failed", e)
                status.setText(R.string.tv_sources_failed)
                add.visibility = View.VISIBLE
                add.setText(R.string.tv_retry)
                add.setOnClickListener { add.setOnClickListener { addPicked() }; load() }
                add.post { add.requestFocus() }
            }
        }
    }

    private fun updateAdd() {
        val n = items.count { it.picked && it.state != State.ADDED }
        add.text = if (n == 0) act.getString(R.string.tv_sources_pick) else act.resources.getQuantityString(R.plurals.tv_sources_add, n, n)
        add.isEnabled = n > 0 && !busy
        add.alpha = if (add.isEnabled) 1f else 0.5f
    }

    private fun addPicked() {
        val queue = items.filter { it.picked && it.state != State.ADDED }
        if (queue.isEmpty() || busy) return
        busy = true
        updateAdd()
        act.lifecycleScope.launch {
            var failed = 0
            for (item in queue) {
                item.state = State.ADDING; adapter.notifyItemChanged(items.indexOf(item))
                val error = try {
                    val (config, script) = withContext(Dispatchers.IO) { act.fetchPlugin(item.entry.url) }
                    // This list promises FUTO's own sources: anything not verifiably signed by FUTO is refused.
                    require(config.isOfficialAuthor() && config.validate(script)) { "not signed by FUTO" }
                    act.installQuietly(config, script)
                } catch (e: Throwable) { e }
                if (error != null) Logger.e(TAG, "Adding ${item.entry.config.name} failed", error)
                item.state = if (error == null) State.ADDED else State.FAILED
                if (error != null) { failed++; item.picked = false }
                adapter.notifyItemChanged(items.indexOf(item))
            }
            busy = false
            updateAdd()
            if (failed == 0) act.sourcesDone(onboarding)
            else {
                act.toast(act.resources.getQuantityString(R.plurals.tv_sources_some_failed, failed, failed))
                skip.setText(R.string.tv_sources_continue)
                skip.requestFocus()
            }
        }
    }

    private inner class SourceAdapter : RecyclerView.Adapter<SourceAdapter.Holder>() {
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.tv_card_source, parent, false))
        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            private val icon: ImageView = v.findViewById(R.id.source_icon)
            private val name: TextView = v.findViewById(R.id.source_name)
            private val desc: TextView = v.findViewById(R.id.source_desc)
            private val note: TextView = v.findViewById(R.id.source_note)
            private val check: ImageView = v.findViewById(R.id.source_check)

            fun bind(item: Item) {
                val c = item.entry.config
                name.text = c.name
                desc.text = c.description
                Glide.with(icon).load(c.absoluteIconUrl).into(icon)
                itemView.isSelected = item.picked || item.state == State.ADDED
                check.visibility = if (itemView.isSelected) View.VISIBLE else View.INVISIBLE
                // Permission warnings stay visible on the card, so picking a source is informed consent.
                val warnings = c.getWarnings().map { it.first }
                note.setTextColor(ContextCompat.getColor(act, if (item.state == State.FAILED || warnings.isNotEmpty() && item.state == State.IDLE) R.color.tv_warn else R.color.tv_muted))
                note.text = when (item.state) {
                    State.ADDED -> act.getString(R.string.tv_sources_added)
                    State.ADDING -> act.getString(R.string.tv_sources_adding)
                    State.FAILED -> act.getString(R.string.tv_sources_couldnt)
                    State.IDLE -> warnings.joinToString(" · ")
                }
                // Warnings get the room they need; the description gives way.
                val longNote = item.state == State.IDLE && warnings.isNotEmpty()
                desc.maxLines = if (longNote) 1 else 2
                note.maxLines = if (longNote) 2 else 1
                itemView.contentDescription = listOf(c.name, note.text).filter { it.isNotBlank() }.joinToString(", ")
                itemView.setOnClickListener {
                    if (busy || item.state == State.ADDED) return@setOnClickListener
                    item.picked = !item.picked
                    if (item.state == State.FAILED) item.state = State.IDLE
                    bind(item); updateAdd()
                }
                itemView.setOnFocusChangeListener { fv, focused -> TvFocus.scale(fv, focused) }
            }
        }
    }

    companion object { private const val TAG = "TvSources" }
}
