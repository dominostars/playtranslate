package com.playtranslate.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import com.playtranslate.R

/**
 * Overlay popup showing a Japanese word lookup result.
 * Left 1/4: word (kanji) + furigana. Middle: scrollable definitions + frequency.
 * Right: Anki button (if installed). Dismissable via tap outside.
 */
class WordLookupPopup(
    val ctx: Context,
    private val wm: WindowManager
) {
    private var popupView: View? = null
    var onDismiss: (() -> Unit)? = null
    var onAnkiTap: (() -> Unit)? = null
    var onOpenTap: (() -> Unit)? = null
    /** Suppresses onDismiss callback during show()'s internal dismiss(). */
    private var suppressDismissCallback = false

    /** Whether to show the Anki button (set before first show). */
    var showAnkiButton = false

    /** Whether to show the "open in app" button (set before first show). */
    var showOpenButton = false

    /** Use TYPE_APPLICATION_PANEL instead of TYPE_ACCESSIBILITY_OVERLAY (for in-app use). */
    var useActivityWindow = false

    /** Vertical gap between finger position and popup edge (dp). Default 40. */
    var verticalMarginDp = 40

    /** The word currently displayed — used to skip redundant redraws. */
    var currentWord: String? = null
        private set

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val arrowSizePx = dp(10)
    private val popupCornerRadius = dp(12).toFloat()
    private val bgColor = Color.parseColor("#242424")
    private val ankiColumnW = dp(44)

    fun show(
        word: String,
        reading: String?,
        senses: List<SenseDisplay>,
        freqScore: Int,
        isCommon: Boolean = false,
        screenX: Int, screenY: Int,
        screenW: Int, screenH: Int,
        anchorHeight: Int = 0,
        label: String? = null
    ) {
        // Skip full redraw if same word is already showing
        if (word == currentWord && popupView != null) return

        suppressDismissCallback = true
        dismiss()
        suppressDismissCallback = false
        currentWord = word

        val baseW = (screenW * 0.85f).toInt().coerceAtMost(dp(360))
        val hasRightButton = showAnkiButton || showOpenButton
        val popupW = if (hasRightButton) baseW + ankiColumnW else baseW
        val maxCardH = dp(160)
        val minCardH = dp(64)
        val margin = dp(verticalMarginDp)

        // Build card first so we can measure its desired height
        val card = buildCardView(word, reading, senses, freqScore, isCommon, popupW, label)
        val widthSpec = View.MeasureSpec.makeMeasureSpec(popupW, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(maxCardH, View.MeasureSpec.AT_MOST)
        card.measure(widthSpec, heightSpec)
        val cardH = card.measuredHeight.coerceIn(minCardH, maxCardH)

        val totalH = cardH + arrowSizePx

        // Decide if popup goes above or below anchor
        val aboveFinger = screenY - totalH - margin >= 0
        val yRaw = if (aboveFinger) {
            screenY - totalH - margin
        } else {
            screenY + anchorHeight + margin
        }
        val x = (screenX - popupW / 2).coerceIn(0, screenW - popupW)
        val y = yRaw.coerceIn(0, screenH - totalH)

        val arrowRelX = (screenX - x).coerceIn(arrowSizePx, popupW - arrowSizePx)

        val windowType = if (useActivityWindow)
            WindowManager.LayoutParams.TYPE_APPLICATION_PANEL
        else
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

        // Build popup with arrow. The popup window itself receives
        // ACTION_OUTSIDE for taps landing beyond its bounds (via
        // FLAG_WATCH_OUTSIDE_TOUCH on the window params below). That
        // outside notification is non-consuming — the real touch still
        // flows through to the underlying activity window, so tapping a
        // new word in ClickableTextView dismisses the current popup AND
        // selects the new word in a single tap.
        val container = FrameLayout(ctx).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_OUTSIDE -> {
                        dismiss()
                        // Do not consume — the real touch continues to the
                        // underlying window.
                        false
                    }
                    // Touches inside the popup bounds fall through to the
                    // child views so buttons (Anki / Open) still register.
                    else -> false
                }
            }
            // Dismiss on joystick movement (analog stick beyond dead zone).
            // Requires the container to have window focus, so the popup
            // window below is marked focusable.
            setOnGenericMotionListener { _, event ->
                if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
                    && event.action == MotionEvent.ACTION_MOVE
                ) {
                    val axisX = event.getAxisValue(MotionEvent.AXIS_X)
                    val axisY = event.getAxisValue(MotionEvent.AXIS_Y)
                    if (axisX * axisX + axisY * axisY > 0.25f) {
                        dismiss()
                        true
                    } else false
                } else false
            }
        }

        // Arrow view
        val arrow = ArrowView(ctx, bgColor, arrowSizePx, pointsDown = aboveFinger).apply {
            layoutParams = FrameLayout.LayoutParams(arrowSizePx * 2, arrowSizePx).apply {
                gravity = if (aboveFinger) Gravity.BOTTOM else Gravity.TOP
                leftMargin = arrowRelX - arrowSizePx
            }
        }

        card.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            cardH
        ).apply {
            if (aboveFinger) {
                topMargin = 0
            } else {
                topMargin = arrowSizePx
            }
        }

        container.addView(card)
        container.addView(arrow)

        val popupParams = WindowManager.LayoutParams(
            popupW, totalH,
            windowType,
            // FLAG_NOT_TOUCH_MODAL is REQUIRED alongside
            // FLAG_WATCH_OUTSIDE_TOUCH. Without it, a focusable window is
            // touch-modal by default and captures every touch system-wide,
            // locking the app. The outside-touch notification is an
            // orthogonal mechanism from touch modality.
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

        wm.addView(container, popupParams)
        // Request window focus so onGenericMotionListener receives joystick
        // events (the previous architecture got focus via the backdrop).
        container.requestFocus()
        popupView = container
    }

    fun dismiss() {
        try { popupView?.let { wm.removeView(it) } } catch (_: Exception) {}
        popupView = null
        currentWord = null
        if (!suppressDismissCallback) onDismiss?.invoke()
    }

    val isShowing: Boolean get() = popupView != null

    private fun buildCardView(
        word: String,
        reading: String?,
        senses: List<SenseDisplay>,
        freqScore: Int,
        isCommon: Boolean,
        width: Int,
        label: String? = null
    ): View {
        val bg = GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = popupCornerRadius
        }

        val root = FrameLayout(ctx).apply {
            background = bg
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }

        val hLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Left 1/4: word + furigana only
        val leftW = ((width - dp(24)) * 0.25f).toInt()
        val leftCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(leftW, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
        }

        if (reading != null && reading != word) {
            leftCol.addView(TextView(ctx).apply {
                text = reading
                setTextColor(Color.parseColor("#A0A0A0"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
        }

        leftCol.addView(TextView(ctx).apply {
            text = word
            setTextColor(Color.parseColor("#EFEFEF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(
                10, 22, 1, TypedValue.COMPLEX_UNIT_SP
            )
        })

        hLayout.addView(leftCol)

        // Divider
        hLayout.addView(View(ctx).apply {
            setBackgroundColor(Color.parseColor("#2E2E2E"))
            layoutParams = LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                setMargins(dp(8), 0, dp(8), 0)
            }
        })

        // Middle: scrollable definitions + frequency
        val rightScroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            isVerticalScrollBarEnabled = true
            isFillViewport = false
        }

        val rightCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(4), 0)
        }

        // Common badge + frequency stars row
        if (isCommon || freqScore > 0) {
            val metaRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(4))
            }
            if (isCommon) {
                val badge = TextView(ctx).apply {
                    text = "common"
                    setTextColor(Color.parseColor("#A0A0A0"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(5), dp(1), dp(5), dp(1))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#383838"))
                        cornerRadius = dp(4).toFloat()
                    }
                }
                metaRow.addView(badge)
            }
            if (freqScore > 0) {
                metaRow.addView(TextView(ctx).apply {
                    text = "★".repeat(freqScore.coerceAtMost(5))
                    setTextColor(Color.parseColor("#606060"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    if (isCommon) setPadding(dp(6), 0, 0, 0)
                })
            }
            rightCol.addView(metaRow)
        }

        if (label != null) {
            rightCol.addView(TextView(ctx).apply {
                text = label
                setTextColor(Color.parseColor("#D4A017"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(4))
            })
        }

        senses.forEachIndexed { i, sense ->
            if (sense.pos.isNotBlank()) {
                rightCol.addView(TextView(ctx).apply {
                    text = sense.pos
                    setTextColor(Color.parseColor("#A0A0A0"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    typeface = Typeface.DEFAULT_BOLD
                    if (i > 0) setPadding(0, dp(6), 0, 0)
                })
            }
            rightCol.addView(TextView(ctx).apply {
                text = "${i + 1}. ${sense.definition}"
                setTextColor(Color.parseColor("#EFEFEF"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            })
        }

        rightScroll.addView(rightCol)
        hLayout.addView(rightScroll)

        // Right-side button column (Anki or Open in app)
        if (showAnkiButton || showOpenButton) {
            // Divider before button
            hLayout.addView(View(ctx).apply {
                setBackgroundColor(Color.parseColor("#2E2E2E"))
                layoutParams = LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                    setMargins(dp(8), 0, dp(4), 0)
                }
            })

            val iconRes = if (showAnkiButton) R.drawable.ic_card_stack else R.drawable.ic_open_in_new
            val onTap = if (showAnkiButton) onAnkiTap else onOpenTap
            val icon = ImageView(ctx).apply {
                val drawable = AppCompatResources.getDrawable(ctx, iconRes)?.mutate()
                if (drawable != null) {
                    DrawableCompat.setTint(drawable, Color.parseColor("#A0A0A0"))
                    setImageDrawable(drawable)
                }
                setPadding(dp(7), dp(4), dp(1), dp(4))
                layoutParams = LinearLayout.LayoutParams(ankiColumnW - dp(13), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                    gravity = Gravity.CENTER_VERTICAL
                }
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setOnClickListener { onTap?.invoke() }
            }
            hLayout.addView(icon)
        }

        root.addView(hLayout)

        return root
    }

    /** Small triangle arrow view pointing toward the word. */
    private class ArrowView(
        ctx: Context,
        private val color: Int,
        private val arrowSize: Int,
        private val pointsDown: Boolean
    ) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = this@ArrowView.color
            style = Paint.Style.FILL
        }
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            path.reset()
            val w = width.toFloat()
            val h = height.toFloat()
            if (pointsDown) {
                path.moveTo(0f, 0f)
                path.lineTo(w, 0f)
                path.lineTo(w / 2f, h)
            } else {
                path.moveTo(w / 2f, 0f)
                path.lineTo(w, h)
                path.lineTo(0f, h)
            }
            path.close()
            canvas.drawPath(path, paint)
        }
    }

    data class SenseDisplay(
        val pos: String,
        val definition: String
    )
}
