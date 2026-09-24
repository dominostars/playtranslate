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
 * row, and both run the same [onClick] / [onLongClick] — so an action
 * behaves identically wherever the fit put it.
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

    /** Runs the action. [anchor] is the view presenting it right now: the
     *  inline button, or ⋯ when it ran from the menu (a popover the action
     *  opens anchors there). */
    var onClick: ((anchor: View) -> Unit)? = null

    /** The long-press action (the Anki one-tap), or null for none. Also
     *  what makes the controller's A a hold on this action. */
    var onLongClick: ((anchor: View) -> Unit)? = null
        set(value) {
            if (field === value) return
            field = value
            notifyChanged(affectsWidth = false)
        }

    /** The owning row (set by [SectionHeaderRow.setActions]). */
    internal var observer: ((HeaderAction, affectsWidth: Boolean) -> Unit)? = null

    private fun notifyChanged(affectsWidth: Boolean) {
        observer?.invoke(this, affectsWidth)
    }
}
