package com.playtranslate.ui

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.playtranslate.AnkiManager
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.fullScreenDialogTheme
import com.playtranslate.themeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen deck picker for Anki. Shows all available decks from AnkiDroid
 * and lets the user select one. Slides in from the right like other pickers.
 */
class AnkiDeckPickerDialog : DialogFragment() {

    var onDeckSelected: (() -> Unit)? = null

    override fun getTheme(): Int = fullScreenDialogTheme(requireContext())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_anki_deck_picker, container, false)

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setWindowAnimations(R.style.AnimSlideRight)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val toolbar = view.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { dismiss() }

        val container = view.findViewById<LinearLayout>(R.id.deckListContainer)
        val prefs = Prefs(requireContext())

        // Show loading state
        container.addView(TextView(requireContext()).apply {
            text = "Loading decks\u2026"
            setTextColor(requireContext().themeColor(R.attr.ptTextMuted))
            textSize = 14f
            setPadding(0, (16 * resources.displayMetrics.density).toInt(), 0, 0)
        })

        viewLifecycleOwner.lifecycleScope.launch {
            val decks = withContext(Dispatchers.IO) { AnkiManager(requireContext()).getDecks() }
            if (!isAdded) return@launch

            container.removeAllViews()

            if (decks.isEmpty()) {
                container.addView(TextView(requireContext()).apply {
                    text = "No decks found"
                    setTextColor(requireContext().themeColor(R.attr.ptTextMuted))
                    textSize = 14f
                })
                return@launch
            }

            val entries = decks.entries.toList()
            entries.forEachIndexed { index, entry ->
                if (index > 0) {
                    // Divider between rows
                    container.addView(View(requireContext()).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, 1
                        ).also { it.marginStart = (16 * resources.displayMetrics.density).toInt() }
                        setBackgroundColor(requireContext().themeColor(R.attr.ptDivider))
                    })
                }
                container.addView(buildDeckRow(entry, prefs))
            }
        }
    }

    private fun buildDeckRow(entry: Map.Entry<Long, String>, prefs: Prefs): View {
        val ctx = requireContext()
        val dp = ctx.resources.displayMetrics.density
        val isSelected = entry.key == prefs.ankiDeckId

        return TextView(ctx).apply {
            text = entry.value
            textSize = 15f
            setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(ctx.themeColor(
                if (isSelected) R.attr.ptAccent else R.attr.ptText
            ))
            setPadding(
                (16 * dp).toInt(), (14 * dp).toInt(),
                (16 * dp).toInt(), (14 * dp).toInt()
            )
            minHeight = (56 * dp).toInt()
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = android.util.TypedValue().let { tv ->
                ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                ctx.getDrawable(tv.resourceId)
            }
            setOnClickListener {
                prefs.ankiDeckId = entry.key
                prefs.ankiDeckName = entry.value
                onDeckSelected?.invoke()
                dismiss()
            }
        }
    }

    companion object {
        const val TAG = "AnkiDeckPickerDialog"

        fun newInstance() = AnkiDeckPickerDialog()
    }
}
