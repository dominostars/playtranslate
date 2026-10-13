package com.playtranslate.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import com.playtranslate.R
import com.playtranslate.language.inflection.ConjugationSection
import com.playtranslate.language.inflection.ConjugationTable
import com.playtranslate.themeColor

/**
 * The conjugation card's body: a chip per section (the moods and the
 * non-finite forms), and under them the selected section as a grid with the
 * pronoun column frozen on the left while the tense columns scroll sideways
 * (a 390 px frame fits two or three tenses), or as a two-column list for the
 * non-finite forms. The cells the user arrived from are filled in accent, a
 * rare column is drawn in the hint colour with its marker, and vos sits under
 * tú as a smaller second line. Code-built on the design-system tokens like
 * [PtGroupCard], which hosts it on the word detail page ([WordDetailBinder]).
 * The selected chip is a solid accent pill with its text in the theme
 * background colour. Chips are clickable, so the workspace's controller nav
 * reaches them; cells are static in this version. A table with one section
 * (a German noun's declension, the English Forms list) shows no chip strip:
 * the card's header already names it.
 */
class ConjugationTableView(ctx: Context) : LinearLayout(ctx) {

    private val textColor = ctx.themeColor(R.attr.ptText)
    private val bgColor = ctx.themeColor(R.attr.ptBg)
    private val mutedColor = ctx.themeColor(R.attr.ptTextMuted)
    private val hintColor = ctx.themeColor(R.attr.ptTextHint)
    private val accentColor = ctx.themeColor(R.attr.ptAccent)
    private val accentTintColor = ctx.themeColor(R.attr.ptAccentTint)
    private val dividerColor = ctx.themeColor(R.attr.ptDivider)

    private val chipRow = LinearLayout(ctx).apply {
        orientation = HORIZONTAL
        setPadding(dp(10), dp(10), dp(10), dp(6))
    }
    /** The chip row and the hairline under it, hidden for a one-section table. */
    internal val chipStrip = LinearLayout(ctx).apply { orientation = VERTICAL }
    /** The grid area, on the card's own colour: a darker band was tried and
     *  read too heavy (Gilad, 2026-10-12); the hairline under the chips is
     *  the separation. */
    internal val body = LinearLayout(ctx).apply { orientation = VERTICAL }

    /** The chips, one per section, in section order. */
    internal val chips = mutableListOf<TextView>()
    /** The selected section's body cells by row then column (grid sections),
     *  or one cell per row (list sections). The pronoun/label column is
     *  [rowLabels]. */
    internal val cells = mutableListOf<List<TextView>>()
    internal val rowLabels = mutableListOf<TextView>()
    internal val columnHeaders = mutableListOf<TextView>()

    var table: ConjugationTable? = null
        private set
    var selectedSection: Int = 0
        private set

    init {
        orientation = VERTICAL
        layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        chipStrip.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipRow)
        })
        chipStrip.addView(View(ctx).apply {
            setBackgroundColor(dividerColor)
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        })
        addView(chipStrip)
        addView(body)
    }

    fun bind(table: ConjugationTable) {
        this.table = table
        chipRow.removeAllViews()
        chips.clear()
        table.sections.forEachIndexed { i, section ->
            val chip = TextView(context).apply {
                this.text = section.title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                isClickable = true
                isFocusable = true
                contentDescription = section.title
                layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .also { it.marginEnd = dp(6) }
                setOnClickListener { selectSection(i) }
            }
            chips.add(chip)
            chipRow.addView(chip)
        }
        chipStrip.visibility = if (table.sections.size > 1) View.VISIBLE else View.GONE
        selectSection(table.openSection.coerceIn(0, (table.sections.size - 1).coerceAtLeast(0)))
    }

    fun selectSection(index: Int) {
        val table = table ?: return
        if (table.sections.isEmpty()) return
        selectedSection = index.coerceIn(0, table.sections.size - 1)
        chips.forEachIndexed { i, chip ->
            val on = i == selectedSection
            chip.isSelected = on
            // The selected chip is a solid accent pill with the text in the
            // theme's background colour; the chip whose section holds the form
            // the user arrived from keeps a lighter accent tint while another
            // section is open, its text still muted, so the way back to that
            // form stays visible (Gilad, 2026-10-12); the rest are bare labels.
            val holdsArrival = table.sections[i].rows.any { r -> r.cells.any { it.highlighted } }
            chip.setTextColor(if (on) bgColor else mutedColor)
            chip.background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(
                    when {
                        on -> accentColor
                        holdsArrival -> accentTintColor
                        else -> dividerColor // a faint pill, so every chip reads as a button
                    },
                )
            }
        }
        body.removeAllViews()
        cells.clear()
        rowLabels.clear()
        columnHeaders.clear()
        val section = table.sections[selectedSection]
        if (section.isList) renderList(section) else renderGrid(section)
    }

    // ── grid: frozen label column + scrolling tense columns ────────────

    private fun renderGrid(section: ConjugationSection) {
        // Hairlines between the rows of both halves (identical dividers keep
        // the halves aligned), which also underlines the header row.
        val labelTable = TableLayout(context).apply { showRowDividers() }
        val formTable = TableLayout(context).apply { showRowDividers() }
        val scroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(formTable)
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        // Header row: an empty corner over the pronouns, a title per tense.
        val headerLabels = TableRow(context).apply { addView(cornerCell(), rowFill()) }
        val headerForms = TableRow(context)
        section.columns.forEachIndexed { c, title ->
            val rare = c in section.rareColumns
            val header = headerCell(
                if (rare && section.rareMarker != null) "$title (${section.rareMarker})" else title,
                if (rare) hintColor else mutedColor,
            )
            columnHeaders.add(header)
            headerForms.addView(header, rowFill())
        }
        labelTable.addView(headerLabels)
        formTable.addView(headerForms)

        val labelRows = mutableListOf<TableRow>()
        val formRows = mutableListOf<TableRow>()
        section.rows.forEach { row ->
            val labelRow = TableRow(context)
            val label = labelCell(row.label)
            rowLabels.add(label)
            labelRow.addView(label, rowFill())
            val formRow = TableRow(context)
            val rowCells = row.cells.mapIndexed { c, cell ->
                val view = formCell(
                    main = cell.forms.joinToString(" / "),
                    secondary = cell.secondary,
                    highlighted = cell.highlighted,
                    dim = c in section.rareColumns,
                )
                formRow.addView(view, rowFill())
                view
            }
            cells.add(rowCells)
            labelTable.addView(labelRow)
            formTable.addView(formRow)
            labelRows.add(labelRow)
            formRows.add(formRow)
        }

        body.addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(dp(6), dp(4), dp(6), dp(10))
            addView(labelTable, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // The vertical hairline that closes the frozen pronoun column.
            addView(View(context).apply {
                setBackgroundColor(dividerColor)
                layoutParams = LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT)
            })
            addView(scroller)
        })
        syncRowHeights(listOf(headerLabels) + labelRows, listOf(headerForms) + formRows)
        scrollToFirstHighlight(scroller, section)
    }

    /** The two tables draw independently, so a wrapped cell on one side would
     *  misalign the rows: before the first draw after a (re)build, every row
     *  takes the taller of its two halves as its minimum height. */
    private fun syncRowHeights(left: List<TableRow>, right: List<TableRow>) {
        viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                viewTreeObserver.removeOnPreDrawListener(this)
                var changed = false
                left.zip(right).forEach { (l, r) ->
                    val h = maxOf(l.height, r.height)
                    if (h > 0 && (l.height != h || r.height != h)) {
                        l.minimumHeight = h
                        r.minimumHeight = h
                        changed = true
                    }
                }
                if (changed) requestLayout()
                return !changed
            }
        })
    }

    private fun scrollToFirstHighlight(scroller: HorizontalScrollView, section: ConjugationSection) {
        val column = section.rows.asSequence()
            .flatMap { it.cells.withIndex().asSequence() }
            .firstOrNull { it.value.highlighted }?.index ?: return
        if (column == 0) return
        scroller.post {
            val target = cells.firstOrNull()?.getOrNull(column) ?: return@post
            scroller.smoothScrollTo((target.left - dp(12)).coerceAtLeast(0), 0)
        }
    }

    // ── list: label / form rows (the non-finite forms) ─────────────────

    private fun renderList(section: ConjugationSection) {
        val list = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(10))
            showRowDividers()
        }
        section.rows.forEach { row ->
            val cell = row.cells.firstOrNull()
            val label = labelCell(row.label)
            val form = formCell(
                main = cell?.forms?.joinToString(" / ").orEmpty(),
                secondary = cell?.secondary.orEmpty(),
                highlighted = cell?.highlighted == true,
                dim = false,
            )
            rowLabels.add(label)
            cells.add(listOf(form))
            list.addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(label, LayoutParams(dp(104), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(form, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
        }
        body.addView(list)
    }

    // ── cells ───────────────────────────────────────────────────────────

    private fun cornerCell() = TextView(context).apply {
        minHeight = dp(ROW_MIN_DP)
        setPadding(dp(10), dp(6), dp(10), dp(6))
    }

    private fun headerCell(title: String, color: Int) = TextView(context).apply {
        text = title
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.04f
        minHeight = dp(ROW_MIN_DP)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(6), dp(10), dp(6))
    }

    private fun labelCell(label: String) = TextView(context).apply {
        text = label
        setTextColor(mutedColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        minHeight = dp(ROW_MIN_DP)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(6), dp(10), dp(6))
    }

    private fun formCell(main: String, secondary: List<String>, highlighted: Boolean, dim: Boolean) = TextView(context).apply {
        val color = when {
            highlighted -> accentColor
            dim -> hintColor
            else -> textColor
        }
        text = if (secondary.isEmpty()) main else SpannableStringBuilder(main).apply {
            append("\n")
            val start = length
            append(secondary.joinToString(" · "))
            setSpan(RelativeSizeSpan(SECONDARY_SCALE), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(if (highlighted) accentColor else hintColor), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        // The arrival form's cells read bold on top of the accent fill.
        if (highlighted) typeface = Typeface.DEFAULT_BOLD
        if (highlighted) {
            // A rounded rect inset from the cell, so the rounding shows above and
            // below and a small buffer separates it from the row hairlines. Set
            // BEFORE the padding: a drawable with insets would otherwise replace
            // the view's padding with its own.
            background = InsetDrawable(
                GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setColor(accentTintColor)
                },
                dp(HIGHLIGHT_INSET_H_DP), dp(HIGHLIGHT_INSET_V_DP), dp(HIGHLIGHT_INSET_H_DP), dp(HIGHLIGHT_INSET_V_DP),
            )
        }
        minHeight = dp(ROW_MIN_DP)
        // Every cell fills its row (rowFill), so centring here centres in the
        // row whatever its neighbours' heights.
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(6), dp(10), dp(6))
        tag = if (highlighted) HIGHLIGHT_TAG else null
    }

    /** 1dp [dividerColor] hairlines between this layout's children. */
    private fun LinearLayout.showRowDividers() {
        dividerDrawable = GradientDrawable().apply {
            setColor(dividerColor)
            setSize(dp(1), dp(1))
        }
        showDividers = SHOW_DIVIDER_MIDDLE
    }

    /** A table cell that stretches to its row's height. */
    private fun rowFill() = TableRow.LayoutParams(TableRow.LayoutParams.WRAP_CONTENT, TableRow.LayoutParams.MATCH_PARENT)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val ROW_MIN_DP = 36
        private const val SECONDARY_SCALE = 0.85f
        private const val HIGHLIGHT_INSET_H_DP = 3
        private const val HIGHLIGHT_INSET_V_DP = 3
        /** Set as the [View.getTag] of a highlighted cell, for tests. */
        internal const val HIGHLIGHT_TAG = "conjugation-highlight"
    }
}
