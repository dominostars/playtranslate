package com.playtranslate.ui

import android.content.Context
import android.graphics.Outline
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import com.playtranslate.R

/**
 * The PlayTranslate mark in a circle [sizePx] across, as a view: the
 * launcher art ([R.mipmap.ic_launcher_img]) drawn 1.5× its circular frame
 * so it bleeds past every edge and the frame's oval outline crops it,
 * the same crop [SonarPingIntroView] draws on a canvas. Used by
 * [OverlayAlert]'s header and the translation-error pill. The caller sets
 * the layout params.
 */
fun appIconCircle(context: Context, sizePx: Int): FrameLayout {
    val frame = FrameLayout(context).apply {
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
    }
    val imgSize = (sizePx * 1.5f).toInt()
    frame.addView(ImageView(context).apply {
        setImageResource(R.mipmap.ic_launcher_img)
        scaleType = ImageView.ScaleType.FIT_CENTER
        // The mark is deliberately larger than its circular frame and bleeds
        // past every edge; Gravity.CENTER states that directly. Negative
        // left/top margins would rely on the default TOP|START gravity,
        // which resolves to RIGHT under an RTL locale, where FrameLayout
        // drops leftMargin and the mark drifts off-centre.
        layoutParams = FrameLayout.LayoutParams(imgSize, imgSize, Gravity.CENTER)
    })
    return frame
}
