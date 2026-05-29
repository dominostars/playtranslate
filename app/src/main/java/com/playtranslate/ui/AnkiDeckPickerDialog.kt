package com.playtranslate.ui

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.playtranslate.AnkiManager
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.applyAccentOverlay
import com.playtranslate.applyDialogEdgeToEdge
import com.playtranslate.fullScreenDialogTheme
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.playtranslate.themeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen deck picker for Anki. Shows the user's AnkiDroid decks
 * inside a grouped-card section that mirrors the Settings / Language
 * picker styling (MaterialCardView wrapper, inset dividers, accent-
 * tinted background on the currently-selected row).
 */
class AnkiDeckPickerDialog : DialogFragment() {

    var onDeckSelected: (() -> Unit)? = null

    override fun getTheme(): Int = fullScreenDialogTheme(requireContext())

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        applyAccentOverlay(dialog.context.theme, requireContext())
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_anki_deck_picker, container, false)

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setWindowAnimations(R.style.AnimSlideRight)
            applyDialogEdgeToEdge(this, requireContext())
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(sys.left, sys.top, sys.right, maxOf(sys.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        val toolbar = view.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { dismiss() }

        val container = view.findViewById<LinearLayout>(R.id.deckListContainer)
        val prefs = Prefs(requireContext())

        container.addView(buildLoadingTextView())

        viewLifecycleOwner.lifecycleScope.launch {
            val decks = withContext(Dispatchers.IO) { AnkiManager(requireContext()).getDecks() }
            if (!isAdded) return@launch
            container.removeAllViews()

            if (decks.isEmpty()) {
                container.addView(TextView(requireContext()).apply {
                    text = getString(R.string.anki_deck_picker_empty)
                    setTextColor(requireContext().themeColor(R.attr.ptTextMuted))
                    textSize = 14f
                })
                return@launch
            }

            renderDecksSection(container, decks.entries.toList(), prefs)
        }
    }

    private fun buildLoadingTextView(): TextView {
        val ctx = requireContext()
        return TextView(ctx).apply {
            text = getString(R.string.anki_deck_picker_loading)
            setTextColor(ctx.themeColor(R.attr.ptTextMuted))
            textSize = 14f
            setPadding(0, (16 * resources.displayMetrics.density).toInt(), 0, 0)
        }
    }

    private fun renderDecksSection(
        parent: LinearLayout,
        decks: List<Map.Entry<Long, String>>,
        prefs: Prefs,
    ) {
        val ctx = requireContext()
        val inflater = layoutInflater

        val card = inflater.inflate(R.layout.language_list_section, parent, false) as MaterialCardView
        val rowContainer = card.findViewById<LinearLayout>(R.id.sectionRows)
        val cardRadius = card.radius
        val lastIdx = decks.lastIndex
        decks.forEachIndexed { idx, entry ->
            if (idx > 0) rowContainer.addView(insetDivider(rowContainer))
            val topRadius = if (idx == 0) cardRadius else 0f
            val bottomRadius = if (idx == lastIdx) cardRadius else 0f
            rowContainer.addView(buildDeckRow(rowContainer, entry, prefs, topRadius, bottomRadius))
        }
        parent.addView(card)
    }

    private fun buildDeckRow(
        container: ViewGroup,
        entry: Map.Entry<Long, String>,
        prefs: Prefs,
        topCornerRadius: Float,
        bottomCornerRadius: Float,
    ): View {
        val ctx = requireContext()
        val isSelected = entry.key == prefs.ankiDeckId
        val view = layoutInflater
            .inflate(R.layout.language_list_row, container, false)
        view.findViewById<TextView>(R.id.tvRowTitle).apply {
            text = entry.value
            // Bolding hints at selection alongside the highlight background.
            setTypeface(typeface, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
        }

        // language_list_row's trailing slot is wired for a Delete
        // button; repurpose it as a non-tappable selection checkmark.
        val trailing = view.findViewById<FrameLayout>(R.id.btnDelete)
        val trailingIcon = view.findViewById<ImageView>(R.id.ivDeleteIcon)
        if (isSelected) {
            trailing.visibility = View.VISIBLE
            trailingIcon.setImageResource(R.drawable.ic_check)
            trailingIcon.imageTintList = ColorStateList.valueOf(ctx.themeColor(R.attr.ptAccent))
            trailing.isClickable = false
            trailing.isFocusable = false
            trailing.foreground = null
        }
        if (isSelected) {
            view.background = ctx.pickerSelectedRowBackground(topCornerRadius, bottomCornerRadius)
        }
        view.setOnClickListener {
            prefs.ankiDeckId = entry.key
            prefs.ankiDeckName = entry.value
            onDeckSelected?.invoke()
            dismiss()
        }
        return view
    }

    private fun insetDivider(container: ViewGroup): View =
        layoutInflater
            .inflate(R.layout.settings_row_divider, container, false)

    companion object {
        const val TAG = "AnkiDeckPickerDialog"

        fun newInstance() = AnkiDeckPickerDialog()
    }
}
