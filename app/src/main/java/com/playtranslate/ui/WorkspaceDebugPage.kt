package com.playtranslate.ui

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.playtranslate.R
import com.playtranslate.themeColor

/**
 * TEMPORARY Step-0 harness page for the floating workspace — debug builds
 * only, reached by long-pressing the floating menu's Language row. Exists
 * purely to falsify the workspace primitive on device (shadow, corner X,
 * tap-outside, IME lift, push/pop, controller nav) before any real flow
 * depends on it. Deleted when the language picker becomes the first tenant.
 */
class WorkspaceDebugPage(private val depth: Int = 1) : WorkspacePage {

    private var scroll: ScrollView? = null
    private val actions = ArrayList<NavAction>()
    private var edit: EditText? = null
    private var hostRef: WorkspaceHost? = null

    override fun title(ctx: Context): CharSequence = "Workspace $depth"

    override fun onCreateView(ctx: Context, parent: ViewGroup, host: WorkspaceHost): View {
        hostRef = host
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()

        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(8f), dp(16f), dp(16f))
        }
        val ripple = TypedValue().let {
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
            it.resourceId
        }
        for (i in 1..6) {
            val row = TextView(ctx).apply {
                text = "Row $i"
                textSize = 15f
                setTextColor(ctx.themeColor(R.attr.ptText))
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(56f)
                if (ripple != 0) setBackgroundResource(ripple)
                isClickable = true
                setOnClickListener { text = "Row $i ✓" }
            }
            column.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            actions.add(NavAction(row))
        }
        val field = EditText(ctx).apply {
            hint = "IME check"
            setTextColor(ctx.themeColor(R.attr.ptText))
            setHintTextColor(ctx.themeColor(R.attr.ptTextHint))
            // Landscape handheld: keep the keyboard out of extract mode.
            imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_DONE
            setSingleLine()
            setOnFocusChangeListener { _, hasFocus -> host.setImeMode(hasFocus) }
            setOnEditorActionListener { v, _, _ ->
                v.clearFocus()
                true
            }
        }
        edit = field
        column.addView(
            field,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8f) },
        )
        val pushBtn = TextView(ctx).apply {
            text = "Push page ${depth + 1}"
            textSize = 15f
            setTextColor(ctx.themeColor(R.attr.ptAccent))
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(56f)
            if (ripple != 0) setBackgroundResource(ripple)
            isClickable = true
            setOnClickListener { host.push(WorkspaceDebugPage(depth + 1)) }
        }
        column.addView(
            pushBtn,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        actions.add(NavAction(pushBtn))

        return ScrollView(ctx).apply {
            isFillViewport = true
            addView(
                column,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }.also { scroll = it }
    }

    override fun navActions(): List<NavAction> = actions

    override fun scrollView(): ViewGroup? = scroll

    override fun onBack(): Boolean {
        val field = edit ?: return false
        if (!field.hasFocus()) return false
        field.clearFocus()
        hostRef?.setImeMode(false)
        return true
    }

    override fun onDestroy() {
        actions.clear()
        scroll = null
        edit = null
        hostRef = null
    }
}
