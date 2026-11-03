package com.futo.platformplayer.tv

import android.app.Activity
import android.app.Dialog
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.futo.platformplayer.R

/** A row of the TV's pick-one lists: label, optional detail, optional value, check when selected. */
class TvChoice(val label: String, val sub: String? = null, val value: String? = null, val selected: Boolean = false)

fun LinearLayout.addChoiceRow(choice: TvChoice, showCheck: Boolean, onClick: () -> Unit): View {
    val row = LayoutInflater.from(context).inflate(R.layout.tv_choice_row, this, false)
    row.findViewById<TextView>(R.id.choice_label).text = choice.label
    row.findViewById<TextView>(R.id.choice_sub).apply { text = choice.sub; visibility = if (choice.sub.isNullOrBlank()) View.GONE else View.VISIBLE }
    row.findViewById<TextView>(R.id.choice_value).apply { text = choice.value; visibility = if (choice.value.isNullOrBlank()) View.GONE else View.VISIBLE }
    row.findViewById<View>(R.id.choice_check).visibility = when {
        !showCheck -> View.GONE
        choice.selected -> View.VISIBLE
        else -> View.INVISIBLE
    }
    row.isSelected = choice.selected
    row.contentDescription = listOfNotNull(choice.label, choice.value, choice.sub).joinToString(", ")
    row.setOnClickListener { onClick() }
    addView(row)
    return row
}

/** Modal TV dialogs. The safe choice always takes focus first, so a stray OK never confirms. */
object TvDialogs {
    private fun show(act: Activity, build: (View, Dialog) -> View): Dialog {
        val view = LayoutInflater.from(act).inflate(R.layout.tv_dialog, null)
        val dialog = Dialog(act, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.setContentView(FrameLayout(act).apply {
            setBackgroundColor(0xCC000000.toInt())
            addView(view, FrameLayout.LayoutParams(act.resources.getDimensionPixelSize(R.dimen.tv_dialog_width), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        })
        val first = build(view, dialog)
        dialog.show()
        first.requestFocus()
        return dialog
    }

    fun confirm(act: Activity, title: String, body: String?, confirm: String, cancel: String = act.getString(R.string.tv_cancel), onConfirm: () -> Unit) {
        show(act) { v, d ->
            v.findViewById<TextView>(R.id.dialog_title).text = title
            v.findViewById<TextView>(R.id.dialog_body).apply { text = body; visibility = if (body.isNullOrBlank()) View.GONE else View.VISIBLE }
            val ok = v.findViewById<TextView>(R.id.dialog_ok)
            ok.text = confirm
            ok.setOnClickListener { d.dismiss(); onConfirm() }
            v.findViewById<TextView>(R.id.dialog_cancel).apply { text = cancel; setOnClickListener { d.dismiss() } }
        }
    }

    /** One of [options]; focus starts on the current choice. */
    fun choose(act: Activity, title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
        show(act) { v, d ->
            v.findViewById<TextView>(R.id.dialog_title).text = title
            v.findViewById<View>(R.id.dialog_buttons).visibility = View.GONE
            v.findViewById<View>(R.id.dialog_scroll).visibility = View.VISIBLE
            val list = v.findViewById<LinearLayout>(R.id.dialog_options)
            val rows = options.mapIndexed { i, o -> list.addChoiceRow(TvChoice(o, selected = i == selected), true) { d.dismiss(); if (i != selected) onPick(i) } }
            rows.getOrElse(selected) { rows.first() }
        }
    }
}
