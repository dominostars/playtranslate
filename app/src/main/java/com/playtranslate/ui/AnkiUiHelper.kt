package com.playtranslate.ui

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import com.playtranslate.themeColor
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.playtranslate.AnkiManager
import com.playtranslate.Prefs
import com.playtranslate.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Loads AnkiDroid decks into [spinner] and auto-saves the selection to [Prefs].
 * [onLoaded] is called with the ordered list of deck entries once loaded.
 *
 * No-ops if AnkiDroid is not installed or permission has not been granted.
 * Must be called from a Fragment with a live [viewLifecycleOwner].
 */
fun Fragment.loadAnkiDecksInto(
    spinner: Spinner,
    onLoaded: (entries: List<Map.Entry<Long, String>>) -> Unit
) {
    viewLifecycleOwner.lifecycleScope.launch {
        val prefs       = Prefs(requireContext())
        val ankiManager = AnkiManager(requireContext())
        if (!ankiManager.isAnkiDroidInstalled() || !ankiManager.hasPermission()) return@launch

        val decks = withContext(Dispatchers.IO) { ankiManager.getDecks() }
        if (decks.isEmpty()) return@launch

        val entries = decks.entries.toList()
        spinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            entries.map { it.value }
        )
        val savedIdx = entries.indexOfFirst { it.key == prefs.ankiDeckId }.takeIf { it >= 0 } ?: 0
        spinner.setSelection(savedIdx)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, v: View?, pos: Int, id: Long) {
                val entry = entries.getOrNull(pos) ?: return
                prefs.ankiDeckId   = entry.key
                prefs.ankiDeckName = entry.value
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        onLoaded(entries)
    }
}

/**
 * Shows a styled dialog explaining what AnkiDroid is and offering to open the Play Store listing.
 * Matches the visual style of the FloatingIconMenu confirmation dialog.
 */
fun showAnkiNotInstalledDialog(context: Context) {
    val density = context.resources.displayMetrics.density
    fun dp(v: Int) = (v * density).toInt()
    fun dpf(v: Int) = v * density

    val dialog = Dialog(context)
    dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

    val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(context.themeColor(R.attr.ptElevated))
            cornerRadius = dpf(16)
        }
        elevation = dpf(12)
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(24), dp(24), dp(24), dp(16))
    }

    // Title
    card.addView(TextView(context).apply {
        text = context.getString(R.string.anki_not_installed_title)
        setTextColor(context.themeColor(R.attr.ptText))
        textSize = 17f
        gravity = Gravity.CENTER
        setTypeface(null, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(8)
        }
    })

    // Subtitle
    card.addView(TextView(context).apply {
        text = context.getString(R.string.anki_not_installed_message)
        setTextColor(context.themeColor(R.attr.ptTextMuted))
        textSize = 13f
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(20)
        }
    })

    val hPad = dp(20)
    val vPad = dp(10)
    val btnLp = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    // "Get AnkiDroid" button
    card.addView(Button(context).apply {
        text = context.getString(R.string.anki_not_installed_get)
        setTextColor(context.themeColor(R.attr.ptAccentOn))
        textSize = 14f
        background = GradientDrawable().apply {
            setColor(context.themeColor(R.attr.ptWarning))
            cornerRadius = dpf(8)
        }
        isAllCaps = false
        setPadding(hPad, vPad, hPad, vPad)
        layoutParams = LinearLayout.LayoutParams(btnLp).apply {
            bottomMargin = dp(8)
        }
        setOnClickListener {
            dialog.dismiss()
            val intent = Intent(Intent.ACTION_VIEW,
                Uri.parse(context.getString(R.string.anki_play_store_url)))
            context.startActivity(intent)
        }
    })

    // Cancel button
    card.addView(Button(context).apply {
        text = context.getString(android.R.string.cancel)
        setTextColor(context.themeColor(R.attr.ptTextMuted))
        textSize = 14f
        setBackgroundColor(Color.TRANSPARENT)
        isAllCaps = false
        setPadding(hPad, vPad, hPad, vPad)
        layoutParams = LinearLayout.LayoutParams(btnLp)
        setOnClickListener { dialog.dismiss() }
    })

    dialog.setContentView(card)
    dialog.window?.setLayout(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT)
    dialog.show()
}

