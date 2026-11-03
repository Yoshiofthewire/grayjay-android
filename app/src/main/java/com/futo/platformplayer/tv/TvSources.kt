package com.futo.platformplayer.tv

import android.view.LayoutInflater
import android.view.View
import android.webkit.CookieManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.futo.platformplayer.R
import com.futo.platformplayer.Settings
import com.futo.platformplayer.api.media.platforms.js.JSClient
import com.futo.platformplayer.api.media.platforms.js.SourcePluginConfig
import com.futo.platformplayer.logging.Logger
import com.futo.platformplayer.states.StateDeveloper
import com.futo.platformplayer.states.StatePlatform
import com.futo.platformplayer.states.StatePlugins
import com.futo.platformplayer.states.StateSubscriptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Installed sources, the same set the phone app lists. The developer source is phone-only. */
private fun installedSources(): List<JSClient> = StatePlatform.instance.getAvailableClients()
    .filterIsInstance<JSClient>()
    .filter { it.config.id != StateDeveloper.DEV_ID }
    .sortedWith(compareByDescending<JSClient> { StatePlatform.instance.isClientEnabled(it.config.id) }.thenBy { it.config.name.lowercase() })

private fun ImageView.loadSourceIcon(client: JSClient) {
    StatePlatform.instance.getPlatformIcon(client.config.id)?.setImageView(this) ?: Glide.with(this).load(client.config.absoluteIconUrl).into(this)
}

class TvSourceListScreen(act: TvActivity) : TvScreen(act) {
    override val tab = TvActivity.TAB_SOURCES
    override val view: View = LayoutInflater.from(act).inflate(R.layout.tv_screen_source_list, null)
    private val list: LinearLayout = view.findViewById(R.id.sources_list)

    init {
        view.findViewById<View>(R.id.sources_add_more).setOnClickListener { act.openSourcePicker(onboarding = false, returnTab = tab) }
    }

    override fun onShown() = render()

    private fun render() {
        val sources = installedSources()
        val on = sources.count { StatePlatform.instance.isClientEnabled(it.config.id) }
        view.findViewById<TextView>(R.id.sources_summary).text = act.resources.getQuantityString(R.plurals.tv_sources_summary, on, on, sources.size)
        list.removeAllViews()
        val embedded = StatePlugins.instance.getEmbeddedSources(act).keys
        for (client in sources) {
            val status = listOfNotNull(
                act.getString(if (StatePlatform.instance.isClientEnabled(client.config.id)) R.string.tv_source_on else R.string.tv_source_off),
                act.getString(R.string.tv_source_logged_in).takeIf { client.isLoggedIn },
                act.getString(R.string.tv_source_built_in).takeIf { client.config.id in embedded },
            ).joinToString(" · ")
            list.addChoiceRow(TvChoice(client.config.name, sub = client.config.description.takeIf { it.isNotBlank() }, value = status), false) {
                act.openSourceDetail(client.config.id)
            }
        }
    }
}

/**
 * One source: on/off, log in/out, remove, and its settings. Settings follow the phone app's rules:
 * booleans and dropdowns only, "dependency" hides a setting until the one it depends on is on,
 * "warningDialog" asks before turning on, and advanced settings sit behind a toggle. Changes are
 * saved and the source reloaded when the screen is left, as on the phone.
 */
class TvSourceDetailScreen(act: TvActivity, private val id: String) : TvScreen(act) {
    override val tab = TvActivity.TAB_SOURCES
    override val view: View = LayoutInflater.from(act).inflate(R.layout.tv_screen_source_detail, null)
    private val actions: LinearLayout = view.findViewById(R.id.source_detail_actions)
    private val settingsList: LinearLayout = view.findViewById(R.id.source_detail_settings)

    private var values: HashMap<String, String?> = hashMapOf()
    private var pluginSettingsChanged = false
    private var appSettingsChanged = false
    private var showAdvanced = false
    private var loaded = false

    private val client get() = StatePlatform.instance.getAvailableClients().find { it.id == id } as? JSClient
    private val isEmbedded get() = StatePlugins.instance.getEmbeddedSources(act).containsKey(id)

    override fun onShown() {
        val c = client ?: return act.back()
        if (!loaded) {
            values = HashMap(c.descriptor.getSettingsWithDefaults())
            c.descriptor.appSettings.loadDefaults(c.config)
            loaded = true
        }
        render(c)
    }

    override fun onHidden() = saveIfChanged()

    private fun render(c: JSClient, focusId: Int = View.NO_ID) {
        val config = c.config
        view.findViewById<TextView>(R.id.source_detail_name).text = config.name
        // As in the consent dialog: only a signature that verifies against the installed script earns "From".
        val verified = !config.scriptSignature.isNullOrEmpty() &&
            StatePlugins.instance.getScript(id)?.let { runCatching { config.validate(it) }.getOrDefault(false) } == true
        view.findViewById<TextView>(R.id.source_detail_meta).text = act.getString(
            if (verified) R.string.tv_source_by else R.string.tv_plugin_claims, config.author.ifBlank { "an unknown author" }, config.version)
        view.findViewById<ImageView>(R.id.source_detail_icon).apply {
            val hasIcon = StatePlatform.instance.getPlatformIcon(this@TvSourceDetailScreen.id) != null || config.absoluteIconUrl != null
            visibility = if (hasIcon) View.VISIBLE else View.GONE
            if (hasIcon) loadSourceIcon(c)
        }

        val enabled = StatePlatform.instance.isClientEnabled(id)
        actions.removeAllViews()
        val toggle = button(if (enabled) R.string.tv_source_turn_off else R.string.tv_source_turn_on, primary = !enabled) { setEnabled(!enabled) }
        toggle.id = R.id.source_action_toggle
        if (config.authentication != null) {
            button(if (c.isLoggedIn) R.string.tv_source_log_out else R.string.tv_log_in, primary = false) {
                if (c.isLoggedIn) logout(config) else act.login(id)
            }.id = R.id.source_action_login
        }
        if (!isEmbedded) button(R.string.tv_source_remove, primary = false) { confirmRemove(config) }.id = R.id.source_action_remove
        view.findViewById<TextView>(R.id.source_detail_note).apply {
            visibility = if (isEmbedded) View.VISIBLE else View.GONE
            setText(R.string.tv_source_built_in_note)
        }

        renderSettings(c)
        (if (focusId != View.NO_ID) view.findViewById<View>(focusId) else null ?: toggle).let { v -> v.post { v.requestFocus() } }
    }

    private fun button(label: Int, primary: Boolean, onClick: () -> Unit): TextView {
        val b = LayoutInflater.from(act).inflate(R.layout.tv_row_retry, actions, false) as TextView
        b.setText(label)
        if (primary) b.setBackgroundResource(R.drawable.tv_button_primary)
        (b.layoutParams as LinearLayout.LayoutParams).marginEnd = act.resources.getDimensionPixelSize(R.dimen.tv_card_gap)
        b.setOnClickListener { onClick() }
        actions.addView(b)
        return b
    }

    private fun isTrue(v: String?) = v == "true" || v == "1" || v == "True"

    private fun renderSettings(c: JSClient) {
        // Rebuilding drops the focused row; remember its position so focus stays where the viewer was.
        val focused = settingsList.indexOfChild(settingsList.focusedChild)
        settingsList.removeAllViews()
        val settings = c.config.settings
        // Same visibility rule as the phone: hidden unless the setting it depends on exists and is on.
        fun visible(s: SourcePluginConfig.Setting): Boolean {
            if (s.isAdvanced == true && !showAdvanced) return false
            val dep = s.dependency ?: return true
            return settings.any { it.variableOrName == dep } && isTrue(values[dep])
        }
        section(R.string.tv_source_settings)
        var shown = 0
        for (s in settings) {
            if (!visible(s)) continue
            when (s.type.lowercase()) {
                "header" -> { section(s.name); continue }
                "boolean" -> {
                    val on = isTrue(values[s.variableOrName])
                    settingsList.addChoiceRow(TvChoice(s.name, s.description, act.getString(if (on) R.string.tv_source_on else R.string.tv_source_off)), false) {
                        setBoolean(c, s, !on)
                    }
                }
                "dropdown" -> {
                    val options = s.options.orEmpty()
                    if (options.isEmpty()) continue
                    val i = values[s.variableOrName]?.toIntOrNull()?.coerceIn(0, options.size - 1) ?: 0
                    settingsList.addChoiceRow(TvChoice(s.name, s.description, options[i]), false) {
                        TvDialogs.choose(act, s.name, options, i) { pick -> values[s.variableOrName] = pick.toString(); pluginSettingsChanged = true; renderSettings(c) }
                    }
                }
                else -> continue // other types aren't editable on the phone either
            }
            shown++
        }
        if (settings.any { it.isAdvanced == true }) {
            settingsList.addChoiceRow(TvChoice(act.getString(R.string.tv_source_advanced), value = act.getString(if (showAdvanced) R.string.tv_source_on else R.string.tv_source_off)), false) {
                showAdvanced = !showAdvanced; renderSettings(c)
            }
        } else if (shown == 0) {
            settingsList.addView(TextView(act).apply { setTextAppearance(R.style.TvText_Body); setText(R.string.tv_source_no_settings); setPadding(32, 0, 0, 0) })
        }

        val app = c.descriptor.appSettings
        section(R.string.tv_source_grayjay)
        appToggle(c, R.string.tv_source_show_home, app.tabEnabled.enableHome ?: c.config.enableInHome) { app.tabEnabled.enableHome = it }
        appToggle(c, R.string.tv_source_show_search, app.tabEnabled.enableSearch ?: c.config.enableInSearch) { app.tabEnabled.enableSearch = it }

        if (focused >= 0) {
            val rows = (0 until settingsList.childCount).map { settingsList.getChildAt(it) }
            (rows.drop(focused).firstOrNull { it.isFocusable } ?: rows.lastOrNull { it.isFocusable })?.requestFocus()
        }
    }

    private fun appToggle(c: JSClient, label: Int, on: Boolean, set: (Boolean) -> Unit) {
        settingsList.addChoiceRow(TvChoice(act.getString(label), value = act.getString(if (on) R.string.tv_source_on else R.string.tv_source_off)), false) {
            set(!on); appSettingsChanged = true; renderSettings(c)
        }
    }

    private fun section(label: Int) = section(act.getString(label))
    private fun section(text: String) {
        val t = LayoutInflater.from(act).inflate(R.layout.tv_section_title, settingsList, false) as TextView
        t.text = text
        settingsList.addView(t)
    }

    private fun setBoolean(c: JSClient, s: SourcePluginConfig.Setting, on: Boolean) {
        val apply = { values[s.variableOrName] = on.toString(); pluginSettingsChanged = true; renderSettings(c) }
        val warning = s.warningDialog
        if (on && !warning.isNullOrBlank()) TvDialogs.confirm(act, s.name, warning, act.getString(R.string.tv_turn_on)) { apply() } else apply()
    }

    private fun saveIfChanged() {
        if (!pluginSettingsChanged && !appSettingsChanged) return
        val c = client ?: return
        if (pluginSettingsChanged) StatePlugins.instance.setPluginSettings(id, values)
        if (appSettingsChanged) StatePlugins.instance.savePlugin(id)
        pluginSettingsChanged = false; appSettingsChanged = false
        act.toast(act.getString(R.string.tv_source_settings_saved, c.config.name))
        reloadSource()
    }

    private fun reloadSource() {
        act.lifecycleScope.launch {
            try { withContext(Dispatchers.IO) { StatePlatform.instance.reloadClient(act, id) } } catch (e: Throwable) { Logger.e(TAG, "Reload failed", e) }
            act.sourcesChanged()
        }
    }

    private fun setEnabled(on: Boolean) {
        val c = client ?: return
        if (on) return change { StatePlatform.instance.enableClient(listOf(id)) }
        val rest = StatePlatform.instance.getSortedEnabledClient().map { it.id }.filter { it != id }
        if (rest.isEmpty()) return act.toast(act.getString(R.string.tv_source_last_on))
        act.lifecycleScope.launch {
            // Like the phone: warn when follows depend on the source being turned off.
            val follows = withContext(Dispatchers.IO) {
                StateSubscriptions.instance.getSubscriptions().count { runCatching { c.isChannelUrl(it.channel.url) }.getOrDefault(false) }
            }
            val turnOff = { change { StatePlatform.instance.selectClients(*rest.toTypedArray()) } }
            if (follows == 0) turnOff()
            else TvDialogs.confirm(act, act.getString(R.string.tv_source_off_title, c.config.name),
                act.getString(R.string.tv_source_off_body, follows), act.getString(R.string.tv_source_turn_off)) { turnOff() }
        }
    }

    private fun change(block: suspend () -> Unit) {
        act.lifecycleScope.launch {
            try { withContext(Dispatchers.IO) { block() } } catch (e: Throwable) { Logger.e(TAG, "Source change failed", e) }
            act.sourcesChanged()
            client?.let { render(it, R.id.source_action_toggle) }
        }
    }

    private fun logout(config: SourcePluginConfig) {
        StatePlugins.instance.setPluginAuth(id, null)
        if (Settings.instance.plugins.clearCookiesOnLogout)
            try { CookieManager.getInstance().removeAllCookies(null) } catch (e: Throwable) { Logger.e(TAG, "Clearing cookies failed", e) }
        act.lifecycleScope.launch {
            try { withContext(Dispatchers.IO) { StatePlatform.instance.reloadClient(act, id) } } catch (e: Throwable) { Logger.e(TAG, "Reload failed", e) }
            act.toast(act.getString(R.string.tv_source_logged_out, config.name))
            act.sourcesChanged()
            client?.let { render(it, R.id.source_action_login) }
        }
    }

    private fun confirmRemove(config: SourcePluginConfig) {
        TvDialogs.confirm(act, act.getString(R.string.tv_source_remove_title, config.name), act.getString(R.string.tv_source_remove_body),
            act.getString(R.string.tv_source_remove)) {
            pluginSettingsChanged = false; appSettingsChanged = false
            act.lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        StatePlugins.instance.deletePlugin(id)
                        StatePlatform.instance.updateAvailableClients(act)
                    }
                    act.toast(act.getString(R.string.tv_source_removed, config.name))
                } catch (e: Throwable) {
                    Logger.e(TAG, "Remove failed", e)
                }
                act.sourcesChanged()
                act.back()
            }
        }
    }

    companion object { private const val TAG = "TvSourceDetail" }
}
