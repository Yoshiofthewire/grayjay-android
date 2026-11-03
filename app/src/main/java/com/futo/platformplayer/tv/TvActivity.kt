package com.futo.platformplayer.tv

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.futo.platformplayer.R
import com.futo.platformplayer.api.media.models.contents.IPlatformContent
import com.futo.platformplayer.api.media.platforms.js.SourcePluginConfig
import com.futo.platformplayer.api.http.ManagedHttpClient
import com.futo.platformplayer.logging.Logger
import com.futo.platformplayer.states.StateApp
import com.futo.platformplayer.states.StatePlatform
import com.futo.platformplayer.states.StatePlugins
import com.futo.platformplayer.states.StateSubscriptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.UUID

/**
 * Lean-back entry point for Android TV. Shares app state with the phone UI
 * (MainActivity), but owns its own navigation, D-pad focus and player.
 */
class TvActivity : AppCompatActivity() {
    private val mainId = "TV-" + UUID.randomUUID()
    private lateinit var content: FrameLayout
    private lateinit var rail: LinearLayout
    private lateinit var dim: View
    private lateinit var toastView: TextView
    private val railItems = mutableListOf<View>()
    private val stack = mutableListOf<TvScreen>()
    private val tabs = mutableMapOf<Int, TvScreen>()
    private val handler = Handler(Looper.getMainLooper())

    private var companion: TvCompanionServer? = null
    var companionLastAction: String? = null
        private set
    var phoneConnected = false
        private set

    val currentTab: Int get() = stack.lastOrNull()?.tab ?: TAB_HOME

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(StateApp.instance.getLocaleContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        StateApp.instance.setGlobalContext(this, lifecycleScope, mainId)
        StateApp.instance.mainAppStarting(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.tv_activity)

        runBlocking {
            try { StatePlatform.instance.updateAvailableClients(this@TvActivity) } catch (e: Throwable) { Logger.e(TAG, "updateAvailableClients failed", e) }
        }
        StateApp.instance.mainAppStarted(this)
        StateApp.instance.mainAppStartedWithExternalFiles(this)

        content = findViewById(R.id.tv_content)
        rail = findViewById(R.id.tv_rail)
        dim = findViewById(R.id.tv_dim)
        toastView = findViewById(R.id.tv_toast)

        addRailItem(TAB_HOME, R.drawable.ic_home, R.string.tv_home)
        addRailItem(TAB_SUBSCRIPTIONS, R.drawable.ic_subscriptions, R.string.tv_subscriptions)
        addRailItem(TAB_SEARCH, R.drawable.ic_search, R.string.tv_search)
        addRailItem(TAB_PHONE, R.drawable.ic_qr, R.string.tv_phone)

        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { old, now ->
            val inRail = now != null && isInRail(now)
            // Entering the rail from content lands on the current tab, not the nearest item.
            if (inRail && (old == null || !isInRail(old))) railItems.firstOrNull { it.isSelected && it !== now }?.let { rail.post { it.requestFocus() } }
            setRailOpen(inRail)
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = back() })

        StateSubscriptions.instance.onSubscriptionsChanged.subscribe(this) { _, _ ->
            runOnUiThread { (tabs[TAB_SUBSCRIPTIONS] as? TvSubscriptionsScreen)?.let { if (stack.lastOrNull() === it) it.load() else it.dirty = true } }
        }

        // The phone link never outlives the TV app being on screen (player included).
        TvForeground.onBackground = {
            stopCompanion()
            if (currentTab == TAB_SUBSCRIPTIONS) (tabs[TAB_SUBSCRIPTIONS] as? TvSubscriptionsScreen)?.markSeen()
        }
        showTab(TAB_HOME)
    }

    override fun onStart() {
        super.onStart()
        TvForeground.started(this)
    }

    // The player's "Go to channel" relaunches this singleTask activity with a channel URL.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_CHANNEL)?.let { openChannel(it) }
    }

    override fun onStop() {
        super.onStop()
        TvForeground.stopped(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        stopCompanion()
        TvForeground.onBackground = null
        StateSubscriptions.instance.onSubscriptionsChanged.remove(this)
        stack.forEach { it.onDestroyed() }
        StateApp.instance.mainAppDestroyed(this, mainId)
    }

    // Navigation

    fun showTab(tab: Int) {
        currentFocus?.let(::hideKeyboard)
        val screen = tabs.getOrPut(tab) {
            when (tab) {
                TAB_SUBSCRIPTIONS -> TvSubscriptionsScreen(this)
                TAB_SEARCH -> TvSearchScreen(this)
                TAB_PHONE -> TvCompanionScreen(this)
                else -> TvHomeScreen(this)
            }
        }
        stack.lastOrNull()?.onHidden()
        if (currentTab == TAB_SUBSCRIPTIONS && tab != TAB_SUBSCRIPTIONS) (tabs[TAB_SUBSCRIPTIONS] as? TvSubscriptionsScreen)?.markSeen()
        stack.filter { it !in tabs.values }.forEach { it.onDestroyed() }
        stack.clear()
        content.removeAllViews()
        push(screen)
    }

    private fun push(screen: TvScreen) {
        stack.lastOrNull()?.let { it.onHidden(); content.removeView(it.view) }
        stack.add(screen)
        (screen.view.parent as? ViewGroup)?.removeView(screen.view)
        content.addView(screen.view)
        railItems.forEach { it.isSelected = it.tag == screen.tab }
        screen.onShown()
    }

    private fun back() {
        val focusInRail = currentFocus?.let(::isInRail) == true
        when {
            stack.size > 1 -> {
                val top = stack.removeAt(stack.size - 1)
                top.onHidden(); top.onDestroyed(); content.removeView(top.view)
                val under = stack.last()
                content.addView(under.view)
                under.onShown()
                under.view.requestFocus()
            }
            !focusInRail -> railItems.firstOrNull { it.isSelected }?.requestFocus()
            currentTab != TAB_HOME -> { showTab(TAB_HOME); railItems.first().requestFocus() }
            else -> finish()
        }
    }

    fun openChannel(url: String) {
        if ((stack.lastOrNull() as? TvChannelScreen)?.url != url) push(TvChannelScreen(this, url))
        focusContent()
    }
    fun openCompanion() = showTab(TAB_PHONE)
    fun openSearch(query: String) {
        showTab(TAB_SEARCH)
        (tabs[TAB_SEARCH] as TvSearchScreen).search(query)
        focusContent()
    }

    fun play(content: IPlatformContent, row: TvRow) {
        val i = row.videos.indexOf(content)
        TvQueue.set(if (i >= 0) row.videos.drop(i + 1).take(25) else listOf())
        startActivity(Intent(this, TvPlayerActivity::class.java).putExtra(TvPlayerActivity.EXTRA_URL, content.url))
    }

    /** Set by navigation; the next screen content able to take focus takes it. */
    private var focusContentPending = false

    /** True when a freshly loaded screen may take focus: after navigation, or when focus is not in the rail. */
    fun isContentFocusWanted(): Boolean = focusContentPending || currentFocus.let { it == null || !isInRail(it) }

    /** Called by a screen once it moved focus into its content. */
    fun contentFocused() { focusContentPending = false }

    private fun focusContent() {
        focusContentPending = true
        val view = stack.lastOrNull()?.view ?: return
        view.post { if (focusContentPending && view.requestFocus()) focusContentPending = false }
    }

    // Rail

    private fun addRailItem(tab: Int, icon: Int, label: Int) {
        val item = LayoutInflater.from(this).inflate(R.layout.tv_rail_item, rail, false)
        item.tag = tab
        item.contentDescription = getString(label)
        item.findViewById<ImageView>(R.id.rail_icon).apply {
            setImageResource(icon)
            imageTintList = ContextCompat.getColorStateList(this@TvActivity, R.color.tv_rail_text)
        }
        item.findViewById<TextView>(R.id.rail_label).setText(label)
        item.setOnClickListener { if (currentTab != tab || stack.size > 1) showTab(tab); focusContent() }
        item.setOnKeyListener { _, keyCode, event ->
            // Right from the rail always lands in content, even when it is still loading.
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN) stack.lastOrNull()?.view?.requestFocus() ?: false else false
        }
        item.id = View.generateViewId()
        rail.addView(item)
        railItems.add(item)
        // Up from the first item and down from the last stay in the rail.
        railItems.first().nextFocusUpId = railItems.first().id
        railItems.forEach { it.nextFocusDownId = View.NO_ID }
        item.nextFocusDownId = item.id
    }

    private fun isInRail(v: View): Boolean {
        var p: View? = v
        while (p != null) { if (p === rail) return true; p = p.parent as? View }
        return false
    }

    private var railOpen = false
    private fun setRailOpen(open: Boolean) {
        if (open == railOpen) return
        railOpen = open
        val to = resources.getDimensionPixelSize(if (open) R.dimen.tv_rail_width_open else R.dimen.tv_rail_width)
        rail.layoutParams = rail.layoutParams.apply { width = to }
        railItems.forEach { it.findViewById<View>(R.id.rail_label).visibility = if (open) View.VISIBLE else View.GONE }
        dim.animate().alpha(if (open) 1f else 0f).setDuration(180).start()
    }

    // Feedback

    fun toast(text: String) {
        toastView.text = text
        toastView.visibility = View.VISIBLE
        toastView.alpha = 0f
        toastView.animate().alpha(1f).setDuration(150).start()
        handler.removeCallbacks(hideToast)
        handler.postDelayed(hideToast, 3500)
    }
    private val hideToast = Runnable { toastView.animate().alpha(0f).setDuration(200).withEndAction { toastView.visibility = View.GONE }.start() }

    fun hideKeyboard(v: View) {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0)
    }

    // Phone companion

    /** Starts (or keeps) the companion server; returns the QR URL, or null without a LAN address. */
    fun startCompanion(): String? {
        val server = companion ?: TvCompanionServer(this, onRequest = ::onCompanionRequest).also {
            it.onConnected = { runOnUiThread { onPhoneConnected() } }
            try { it.start() } catch (e: Throwable) { Logger.e(TAG, "Companion server failed to start", e); return null }
            companion = it
            companionLastAction = null
            phoneConnected = false
            handler.postDelayed(idleCheck, IDLE_CHECK_MS)
        }
        return server.url ?: run { stopCompanion(); null }
    }

    fun stopCompanion() {
        val server = companion ?: return
        companion = null
        phoneConnected = false
        handler.removeCallbacks(idleCheck)
        Thread { server.stop() }.start()
        railItems.firstOrNull { it.tag == TAB_PHONE }?.findViewById<View>(R.id.rail_dot)?.visibility = View.GONE
        tabs.remove(TAB_PHONE)
        if (!isFinishing && !isChangingConfigurations && currentTab == TAB_PHONE) showTab(TAB_HOME)
    }

    private val idleCheck = object : Runnable {
        override fun run() {
            val server = companion ?: return
            if (System.currentTimeMillis() - server.lastRequestAt > IDLE_TIMEOUT_MS) {
                stopCompanion(); toast(getString(R.string.tv_companion_stopped))
            } else handler.postDelayed(this, IDLE_CHECK_MS)
        }
    }

    /** Runs on a server thread; the returned text is shown on the phone. */
    private fun onCompanionRequest(request: TvCompanionServer.Request): String {
        val message = when (request) {
            is TvCompanionServer.Request.Search -> onMain { bringToFront(); openSearch(request.query); "Searching for “${request.query}” on your TV" }
            is TvCompanionServer.Request.Follow -> {
                require(StatePlatform.instance.hasEnabledChannelClient(request.url)) { getString(R.string.tv_follow_failed) }
                val channel = try { StatePlatform.instance.getChannelLive(request.url) } catch (e: Throwable) {
                    throw IllegalArgumentException(getString(R.string.tv_follow_failed))
                }
                onMain {
                    if (!StateSubscriptions.instance.isSubscribed(channel)) StateSubscriptions.instance.addSubscription(channel)
                    toast(getString(R.string.tv_followed_toast, channel.name))
                }
                "Following ${channel.name}"
            }
            is TvCompanionServer.Request.Play -> when {
                StatePlatform.instance.hasEnabledContentClient(request.url) -> onMain {
                    TvQueue.set(listOf())
                    startActivity(Intent(this, TvPlayerActivity::class.java).putExtra(TvPlayerActivity.EXTRA_URL, request.url))
                    "Playing on your TV"
                }
                StatePlatform.instance.hasEnabledChannelClient(request.url) -> onMain { bringToFront(); openChannel(request.url); "Opened the channel on your TV" }
                else -> throw IllegalArgumentException("None of your sources can open that link")
            }
            is TvCompanionServer.Request.InstallPlugin -> {
                val (config, script) = fetchPlugin(request.url)
                onMain { bringToFront(); confirmPlugin(config, script) }
                "Check your TV to confirm adding ${config.name}"
            }
        }
        companionLastAction = message
        runOnUiThread { onPhoneConnected() }
        return message
    }

    private fun onPhoneConnected() {
        phoneConnected = true
        (tabs[TAB_PHONE] as? TvCompanionScreen)?.setStatus(companionLastAction, connected = true)
        railItems.firstOrNull { it.tag == TAB_PHONE }?.findViewById<View>(R.id.rail_dot)?.visibility = View.VISIBLE
    }

    /** Phone actions that change the TV screen close the player first (singleTask clears it) so they are seen. */
    private fun bringToFront() {
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
            startActivity(Intent(this, TvActivity::class.java))
    }

    private fun <T> onMain(block: () -> T): T {
        val result = CompletableDeferred<T>()
        runOnUiThread { try { result.complete(block()) } catch (e: Throwable) { result.completeExceptionally(e) } }
        return runBlocking { result.await() }
    }

    private fun fetchPlugin(url: String): Pair<SourcePluginConfig, String> {
        val client = ManagedHttpClient().apply { setTimeout(FETCH_TIMEOUT_MS) }
        val config = try {
            client.get(url).use { resp ->
                val json = resp.body?.byteStream()?.use { readCapped(it, MAX_CONFIG_BYTES) }?.toString(Charsets.UTF_8)
                require(resp.isOk && !json.isNullOrEmpty())
                SourcePluginConfig.fromJson(json, url)
            }
        } catch (e: Throwable) {
            throw IllegalArgumentException("That link isn't a Grayjay source")
        }
        val scriptUrl = TvCompanionServer.httpUrl(config.absoluteScriptUrl)
        val script = try {
            client.get(scriptUrl).use { resp ->
                val len = resp.body?.contentLength() ?: -1
                if (!resp.isOk || len > MAX_SCRIPT_BYTES) null
                else resp.body?.byteStream()?.use { readCapped(it, MAX_SCRIPT_BYTES) }?.toString(Charsets.UTF_8)?.takeIf { it.isNotEmpty() }
            }
        } catch (e: Throwable) { null } ?: throw IllegalArgumentException("Couldn't download ${config.name}'s code")
        // A signature that doesn't match the script means it was altered: never offer it.
        require(config.scriptSignature.isNullOrEmpty() || config.validate(script)) {
            "${config.name}'s signature doesn't match its code, so it can't be added"
        }
        // A replacement keeps the installed source's login, so it must come from the same signer.
        // Copying the key is not enough: the new script must be signed by it.
        val installed = StatePlugins.instance.getPlugin(config.id)?.config?.scriptPublicKey
        require(installed.isNullOrEmpty() || (installed == config.scriptPublicKey && config.validate(script))) {
            "This would replace your ${config.name} source with one from a different author, so it was refused"
        }
        return config to script
    }

    private var pluginDialog: Dialog? = null

    /** The TV-side consent for code the phone asked to install. "Don't add" holds focus first. */
    private fun confirmPlugin(config: SourcePluginConfig, script: String) {
        pluginDialog?.dismiss()
        val view = LayoutInflater.from(this).inflate(R.layout.tv_dialog_plugin, null)
        val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar).apply {
            setContentView(FrameLayout(this@TvActivity).apply {
                setBackgroundColor(0xCC000000.toInt())
                addView(view, FrameLayout.LayoutParams(view.resources.getDimensionPixelSize(R.dimen.tv_dialog_width), ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
            })
        }
        pluginDialog = dialog

        // fetchPlugin already rejected mismatched signatures; still check here so the label can never outrun the proof.
        val official = config.isOfficialAuthor() && config.validate(script)
        val author = config.author.ifBlank { "an unknown author" }
        view.findViewById<TextView>(R.id.plugin_title).text = getString(R.string.tv_plugin_title, config.name)
        // Only a verified signature earns "From"; anything else is the config's own claim.
        view.findViewById<TextView>(R.id.plugin_author).text =
            getString(if (official) R.string.tv_plugin_by else R.string.tv_plugin_claims, author, config.version)
        val icon = view.findViewById<ImageView>(R.id.plugin_icon)
        config.absoluteIconUrl?.let { Glide.with(this).load(it).into(icon) } ?: run {
            icon.visibility = View.GONE
            (view.findViewById<View>(R.id.plugin_title).parent as View).let { (it.layoutParams as ViewGroup.MarginLayoutParams).marginStart = 0 }
        }

        val details = view.findViewById<LinearLayout>(R.id.plugin_details)
        fun line(text: String, color: Int = R.color.tv_muted, bold: Boolean = false, top: Int = 10) {
            details.addView(TextView(this).apply {
                setTextAppearance(R.style.TvText_Body)
                this.text = text
                setTextColor(ContextCompat.getColor(this@TvActivity, color))
                if (bold) typeface = resources.getFont(R.font.inter_semibold)
                setPadding(0, (top * resources.displayMetrics.density).toInt(), 0, 0)
            })
        }
        line(getString(if (official) R.string.tv_plugin_official else R.string.tv_plugin_third_party), R.color.white, bold = true, top = 0)
        StatePlugins.instance.getPlugin(config.id)?.let { line(getString(R.string.tv_plugin_replaces, it.config.name)) }
        for ((title, body) in config.getWarnings(script)) {
            line(title, R.color.tv_warn, bold = true, top = 16)
            line(body, top = 4)
        }
        val hosts = config.allowUrls
        if (hosts.isNotEmpty()) {
            line(getString(R.string.tv_plugin_access), R.color.white, bold = true, top = 16)
            if (hosts.any { it == "everywhere" }) line(getString(R.string.tv_plugin_access_any), R.color.tv_warn, bold = true, top = 4)
            else {
                val shown = hosts.take(6).joinToString(", ")
                line(if (hosts.size > 6) "$shown " + getString(R.string.tv_plugin_access_more, hosts.size - 6) else shown, top = 4)
            }
        }

        // Last: the author's own words, so they can never push the warnings out of view.
        if (config.description.isNotBlank()) line(config.description, top = 16)

        val cancel = view.findViewById<View>(R.id.plugin_cancel)
        cancel.setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.plugin_install).setOnClickListener {
            dialog.dismiss()
            installPlugin(config, script)
        }
        dialog.setOnDismissListener { if (pluginDialog === dialog) pluginDialog = null }
        dialog.show()
        cancel.requestFocus()
        // Long warnings scroll inside a capped area so the two buttons never leave the screen.
        val scroll = view.findViewById<ScrollView>(R.id.plugin_scroll)
        val maxHeight = (resources.displayMetrics.heightPixels * 0.45).toInt()
        scroll.post {
            if (scroll.height > maxHeight) {
                scroll.layoutParams = scroll.layoutParams.apply { height = maxHeight }
                scroll.isFocusable = true
                scroll.foreground = ContextCompat.getDrawable(this, R.drawable.tv_card_frame)
            }
        }
    }

    private fun installPlugin(config: SourcePluginConfig, script: String) {
        toast(getString(R.string.tv_plugin_installing, config.name))
        StatePlugins.instance.installPluginBackground(this, lifecycleScope, config, script, { _, _ -> }) { ex ->
            if (ex != null) {
                Logger.e(TAG, "Install failed", ex)
                toast(getString(R.string.tv_plugin_failed, config.name))
                return@installPluginBackground
            }
            StatePlugins.instance.clearUpdateAvailable(config)
            lifecycleScope.launch {
                // Adding from the phone means "I want this": turn it on even if it was installed but off.
                if (!StatePlatform.instance.isClientEnabled(config.id)) StatePlatform.instance.enableClient(listOf(config.id))
                (tabs[TAB_HOME] as? TvHomeScreen)?.load()
                toast(getString(R.string.tv_plugin_installed, config.name))
                companionLastAction = "Added ${config.name}"
                onPhoneConnected()
            }
        }
    }

    companion object {
        private const val TAG = "TvActivity"
        const val EXTRA_CHANNEL = "channel"
        const val TAB_HOME = 0
        const val TAB_SUBSCRIPTIONS = 1
        const val TAB_SEARCH = 2
        const val TAB_PHONE = 3
        private const val IDLE_TIMEOUT_MS = 15 * 60 * 1000L
        private const val IDLE_CHECK_MS = 60 * 1000L
        private const val FETCH_TIMEOUT_MS = 10_000L
        private const val MAX_SCRIPT_BYTES = 5L * 1024 * 1024
        private const val MAX_CONFIG_BYTES = 256L * 1024

        /** Reads at most [max] bytes; null when the stream is longer. */
        fun readCapped(input: java.io.InputStream, max: Long): ByteArray? {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) return out.toByteArray()
                if (out.size() + n > max) return null
                out.write(buf, 0, n)
            }
        }
    }
}

/**
 * Counts started TV activities. Android starts the next activity before stopping the previous
 * one, so the count only reaches zero when Grayjay TV really leaves the screen.
 */
object TvForeground {
    private val started = mutableSetOf<Any>()
    var onBackground: (() -> Unit)? = null
    fun started(a: Any) { started.add(a) }
    fun stopped(a: Any) { started.remove(a); if (started.isEmpty()) onBackground?.invoke() }
}

/** Up-next list handed from the row the viewer played from to the player. */
object TvQueue {
    @Volatile private var items: List<IPlatformContent> = listOf()
    fun set(list: List<IPlatformContent>) { items = list }
    fun take(): IPlatformContent? = synchronized(this) { items.firstOrNull()?.also { items = items.drop(1) } }
    fun peek(): IPlatformContent? = items.firstOrNull()
}
