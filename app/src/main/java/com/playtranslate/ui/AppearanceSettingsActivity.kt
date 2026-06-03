package com.playtranslate.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.playtranslate.R
import com.playtranslate.themeColor
import kotlinx.coroutines.launch

/**
 * Appearance sub-page: theme-mode (System/Dark/Light) + accent-color pickers.
 *
 * Renders from [AppearanceViewModel.state] (Prefs is the source of truth). A
 * selection writes the pref via the VM and `recreate()`s this Activity so
 * `applyTheme` re-resolves the palette + accent for a live preview — the same
 * recreate-per-change behavior the old inline pickers drove on MainActivity.
 * MainActivity, sitting behind this page, re-applies on its next onResume by
 * diffing the theme/accent prefs.
 */
class AppearanceSettingsActivity : SettingsSubPageActivity() {

    override val layoutResId = R.layout.activity_appearance_settings

    private val vm: AppearanceViewModel by viewModels()

    override fun onContentCreated(savedInstanceState: Bundle?) {
        val themeContainer = findViewById<LinearLayout>(R.id.llThemeModePicker)
        val accentContainer = findViewById<WrappingLinearLayout>(R.id.llAccentPicker)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { state ->
                    buildThemeModePicker(themeContainer, state.themeMode)
                    buildAccentPicker(accentContainer, state.accent)
                }
            }
        }
    }

    private fun onThemeModeSelected(mode: ThemeMode) {
        if (vm.state.value.themeMode == mode) return
        vm.setThemeMode(mode)
        recreate()
    }

    private fun onAccentSelected(accent: AccentColor) {
        if (vm.state.value.accent == accent) return
        vm.setAccent(accent)
        recreate()
    }

    private fun buildThemeModePicker(container: LinearLayout, current: ThemeMode) {
        container.removeAllViews()
        val dp = resources.displayMetrics.density
        val tileRadius = 12 * dp
        val swatchRadius = 8 * dp
        val accentColor = themeColor(R.attr.ptAccent)
        val outlineColor = themeColor(R.attr.ptOutline)

        val darkBg = ContextCompat.getColor(this, R.color.pt_dark_bg)
        val darkText = ContextCompat.getColor(this, R.color.pt_dark_text)
        val lightBg = ContextCompat.getColor(this, R.color.pt_light_bg)
        val lightText = ContextCompat.getColor(this, R.color.pt_light_text)

        data class ModeOption(val mode: ThemeMode, val label: String)
        val modes = listOf(
            ModeOption(ThemeMode.SYSTEM, getString(R.string.pt_theme_mode_system)),
            ModeOption(ThemeMode.DARK, getString(R.string.pt_theme_mode_dark)),
            ModeOption(ThemeMode.LIGHT, getString(R.string.pt_theme_mode_light)),
        )

        modes.forEachIndexed { idx, opt ->
            val selected = opt.mode == current
            val tile = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).also { lp ->
                    if (idx > 0) lp.marginStart = (10 * dp).toInt()
                }
                background = GradientDrawable().apply {
                    cornerRadius = tileRadius
                    setColor(Color.TRANSPARENT)
                    setStroke((2 * dp).toInt(), if (selected) accentColor else outlineColor)
                }
                setPadding((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
                isClickable = true
                isFocusable = true
                foreground = TypedValue().let { tv ->
                    theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                    ContextCompat.getDrawable(this@AppearanceSettingsActivity, tv.resourceId)
                }
                setOnClickListener { onThemeModeSelected(opt.mode) }
            }

            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val swatchH = (52 * dp).toInt()
            val swatch = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, swatchH
                )
                background = when (opt.mode) {
                    ThemeMode.DARK -> GradientDrawable().apply {
                        setColor(darkBg); cornerRadius = swatchRadius
                    }
                    ThemeMode.LIGHT -> GradientDrawable().apply {
                        setColor(lightBg); cornerRadius = swatchRadius
                    }
                    ThemeMode.SYSTEM -> DiagonalSplitDrawable(
                        topLeftColor = darkBg,
                        bottomRightColor = lightBg,
                        cornerRadius = swatchRadius,
                    )
                }
                setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
            }

            // Faux text bars for solid Light/Dark previews. System tile is
            // intentionally bare so the diagonal split reads cleanly.
            if (opt.mode != ThemeMode.SYSTEM) {
                val barColor = if (opt.mode == ThemeMode.DARK) darkText else lightText
                swatch.post {
                    swatch.removeAllViews()
                    val availW = swatch.width - swatch.paddingLeft - swatch.paddingRight
                    if (availW <= 0) return@post

                    fun makeBar(widthFraction: Float, height: Int, alphaFrac: Float): View {
                        return View(this).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                (availW * widthFraction).toInt(), (height * dp).toInt()
                            )
                            background = GradientDrawable().apply {
                                setColor(barColor)
                                cornerRadius = 2 * dp
                                this.alpha = (alphaFrac * 255).toInt()
                            }
                        }
                    }
                    swatch.addView(makeBar(0.40f, 4, 0.8f))
                    swatch.addView(makeBar(0.70f, 3, 0.4f).also {
                        (it.layoutParams as LinearLayout.LayoutParams).topMargin = (4 * dp).toInt()
                    })
                    swatch.addView(makeBar(0.55f, 3, 0.4f).also {
                        (it.layoutParams as LinearLayout.LayoutParams).topMargin = (4 * dp).toInt()
                    })
                }
            }

            inner.addView(swatch)

            val label = TextView(this).apply {
                text = opt.label
                textSize = 12f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                setTextColor(themeColor(R.attr.ptText))
                setPadding(0, (6 * dp).toInt(), 0, (2 * dp).toInt())
            }
            inner.addView(label)

            tile.addView(inner)
            container.addView(tile)
        }
    }

    private fun buildAccentPicker(container: WrappingLinearLayout, current: AccentColor) {
        container.removeAllViews()
        val dp = resources.displayMetrics.density
        val swatchSize = (48 * dp).toInt()
        val ringStroke = (2 * dp).toInt()
        val innerInset = (8 * dp).toInt()
        container.horizontalSpacingPx = (8 * dp).toInt()
        container.verticalSpacingPx = (12 * dp).toInt()

        AccentColor.values().forEach { accent ->
            val color = ContextCompat.getColor(this, accent.color)
            val selected = accent == current

            val ringDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                if (selected) setStroke(ringStroke, color)
            }
            val innerDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            val layered = LayerDrawable(arrayOf(ringDrawable, innerDrawable)).apply {
                setLayerInset(1, innerInset, innerInset, innerInset, innerInset)
            }

            val swatch = FrameLayout(this).apply {
                layoutParams = ViewGroup.LayoutParams(swatchSize, swatchSize)
                background = layered
                isClickable = true
                isFocusable = true
                contentDescription = getString(accent.displayName)
                foreground = TypedValue().let { tv ->
                    theme.resolveAttribute(
                        android.R.attr.selectableItemBackgroundBorderless, tv, true
                    )
                    ContextCompat.getDrawable(this@AppearanceSettingsActivity, tv.resourceId)
                }
                setOnClickListener { onAccentSelected(accent) }
            }
            container.addView(swatch)
        }
    }
}
