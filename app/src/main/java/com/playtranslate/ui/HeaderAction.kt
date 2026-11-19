package com.playtranslate.ui

import android.view.View
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes

/**
 * One action in a results section header: the single source of truth for
 * both places it can appear, the inline button in its [SectionHeaderRow] and
 * its row in the ⋯ menu ([ActionOverflowMenu]). The binder
 * ([TranslationSectionBinder]) writes these fields and never touches a view;
 * the row renders the inline button from them, the open menu renders its
 * row, and both run the same [onClick] — so an action behaves
 * identically wherever the fit put it.
 *
 * [icon] is what the menu row always shows, and what an [Presentation.ICON]
 * button shows inline; a [Presentation.TEXT_PILL] shows [label] inline
 * instead (the worded "Show on screen" toggle), keeping [icon] for its menu
 * row. [active] is the accent state: furigana on, speaking, boxes shown.
 *
 * Every setter reports a real change to the row that owns the action
 * ([observer]); [available] false means GONE, inline and in the menu alike.
 */
class HeaderAction(
    /** The inline view's id: tests and lookups find it like the old XML
     *  button (declared in values/ids.xml). */
    @IdRes val viewId: Int,
    @DrawableRes icon: Int,
    /** The short name: the menu row's text, and a pill's inline text. */
    label: CharSequence,
    /** The inline button's spoken name. */
    contentDescription: CharSequence = label,
    val presentation: Presentation = Presentation.ICON,
    /** Never folds: stays inline after ⋯ (the section's eye). */
    val pinned: Boolean = false,
) {
    enum class Presentation { ICON, TEXT_PILL }

    var available: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            notifyChanged(affectsWidth = true)
        }

    var active: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            notifyChanged(affectsWidth = false)
        }

    /** 0 for none: a menu row then keeps the icon's place empty, so its
     *  name lines up with its neighbours' (never for an inline button). */
    @DrawableRes
    var icon: Int = icon
        set(value) {
            if (field == value) return
            field = value
            notifyChanged(affectsWidth = false)
        }

    var label: CharSequence = label
        set(value) {
            if (field.toString() == value.toString()) return
            field = value
            // Only a pill's inline width follows its text.
            notifyChanged(affectsWidth = presentation == Presentation.TEXT_PILL)
        }

    var contentDescription: CharSequence = contentDescription
        set(value) {
            if (field.toString() == value.toString()) return
            field = value
            notifyChanged(affectsWidth = false)
        }

    /** A value shown at the end of the action's menu row (a language row's
     *  current language); null for none. Inline buttons do not show it. */
    var detail: CharSequence? = null
        set(value) {
            if (field?.toString() == value?.toString()) return
            field = value
            notifyChanged(affectsWidth = false)
        }

    /** Runs the action. [anchor] is the view presenting it right now: the
     *  inline button, or ⋯ when it ran from the menu (a popover the action
     *  opens anchors there). */
    var onClick: ((anchor: View) -> Unit)? = null

    /** The owning row (set by [SectionHeaderRow.setActions]). */
    internal var observer: ((HeaderAction, affectsWidth: Boolean) -> Unit)? = null

    private fun notifyChanged(affectsWidth: Boolean) {
        observer?.invoke(this, affectsWidth)
    }
}

/** One group of a ⋯ menu: its rows, under [title] when it has one. */
class MenuGroup(val title: CharSequence?, val actions: List<HeaderAction>)

/**
 * A row of [HeaderAction]s that folds the ones that do not fit behind a ⋯
 * button: what an [ActionOverflowMenu] lists and follows. A results section's
 * header ([SectionHeaderRow]) and the detached window's bar are the two.
 */
interface FoldingActionRow {
    /** The available actions the last fit folded behind ⋯, in order. */
    fun foldedActions(): List<HeaderAction>

    /** What the ⋯ menu lists, in groups it rules off from one another: the
     *  folded actions as one group with no heading, unless the row has more
     *  to offer there (the detached window's menu is its whole list of
     *  actions, under headings). */
    fun menuGroups(): List<MenuGroup> = listOf(MenuGroup(null, foldedActions()))

    /** Whether [action]'s menu row can be tapped now. One that cannot is
     *  shown dimmed and keeps its place, so the rows under it never move. */
    fun menuEnabled(action: HeaderAction): Boolean = true

    /** Whether the menu stays up after [action] ran from it: a switch,
     *  which the user may want to flip back or see flip. Every other row
     *  closes the menu first. */
    fun menuStaysOpen(action: HeaderAction): Boolean = false

    /** The ⋯ button: the anchor for the menu and for any popover a menu
     *  action opens. */
    val moreAnchor: View

    fun addListener(listener: Listener)
    fun removeListener(listener: Listener)

    /** What an open overflow menu follows. */
    interface Listener {
        /** The folded set may have changed, or which rows can be tapped
         *  ([menuEnabled]). Posted from the measure pass that changed it, so
         *  a listener never mutates views mid-measure; compare [menuGroups]
         *  against what you showed. */
        fun onFoldChanged(row: FoldingActionRow) {}

        /** An action's look changed (icon, accent, name). Synchronous. */
        fun onActionChanged(action: HeaderAction) {}
    }
}
