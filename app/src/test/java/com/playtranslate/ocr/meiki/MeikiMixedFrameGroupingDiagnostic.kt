package com.playtranslate.ocr.meiki

import android.graphics.Rect
import com.playtranslate.language.TextOrientation
import com.playtranslate.ocr.core.DeskewGeometry
import com.playtranslate.ocr.core.LayoutAnalyzer
import com.playtranslate.ocr.core.OcrBox
import com.playtranslate.ocr.core.RecognizedLine
import com.playtranslate.ocr.core.RecognizedRegion
import com.playtranslate.ocr.core.RegionOrigin
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.cos
import kotlin.math.sin

/**
 * DIAGNOSTIC (Thor 2026-08-12): Meiki's skew retry accepts rotated regions
 * (logcat: est −3.4…−12.7, conf wins) but nothing renders angled. This pins
 * whether the grouping shell preserves those angles for the REAL frame shape —
 * a JA frame mixing a measured-0 upright mass with a few slanted singletons —
 * or silently absorbs them (the greedy run + θ̄-residual path in clusterRun).
 */
@RunWith(RobolectricTestRunner::class)
class MeikiMixedFrameGroupingDiagnostic {

    private fun region(text: String, w: Float, h: Float, deg: Float, cx: Float, cy: Float): RecognizedRegion {
        val box = if (deg == 0f) {
            OcrBox.upright(
                Rect(
                    (cx - w / 2).toInt(), (cy - h / 2).toInt(),
                    (cx + w / 2).toInt(), (cy + h / 2).toInt(),
                ),
            )
        } else {
            val r = Math.toRadians(deg.toDouble())
            val c = cos(r).toFloat(); val s = sin(r).toFloat()
            val corners = listOf(
                -w / 2 to -h / 2, w / 2 to -h / 2, w / 2 to h / 2, -w / 2 to h / 2,
            ).map { (x, y) -> (cx + x * c - y * s) to (cy + x * s + y * c) }
            OcrBox(
                Rect(
                    DeskewGeometry.roundHalfUp(corners.minOf { it.first }),
                    DeskewGeometry.roundHalfUp(corners.minOf { it.second }),
                    DeskewGeometry.roundHalfUp(corners.maxOf { it.first }),
                    DeskewGeometry.roundHalfUp(corners.maxOf { it.second }),
                ),
                w, h, deg,
            )
        }
        return RecognizedRegion(
            text = text, box = box, orientation = TextOrientation.HORIZONTAL,
            confidence = 0.8f,
            lines = listOf(
                RecognizedLine(text, box, TextOrientation.HORIZONTAL, confidence = 0.8f),
            ),
            origin = RegionOrigin.LINE,
        )
    }

    @Test
    fun thorFrameShape_slantsSurviveGrouping() {
        val regions = buildList {
            // Upright dialogue mass (measured 0 — Meiki emits OcrBox.upright).
            add(region("むかしむかし、あるところに", 620f, 40f, 0f, 700f, 900f))
            add(region("おじいさんとおばあさんが", 560f, 40f, 0f, 680f, 960f))
            add(region("すんでいました。", 340f, 40f, 0f, 570f, 1020f))
            add(region("はい", 90f, 36f, 0f, 300f, 700f))
            add(region("いいえ", 130f, 36f, 0f, 300f, 760f))
            add(region("メニュー", 170f, 34f, 0f, 1500f, 80f))
            add(region("アイテム", 170f, 34f, 0f, 1500f, 140f))
            add(region("そうび", 130f, 34f, 0f, 1500f, 200f))
            add(region("セーブ", 130f, 34f, 0f, 1500f, 260f))
            // The slanted singletons the retry accepted (logged angles).
            add(region("クエストログ", 400f, 45f, -4.9f, 400f, 300f))
            add(region("ボーナスステージ", 350f, 42f, -4.8f, 500f, 420f))
            add(region("ランクアップ", 250f, 40f, -3.6f, 900f, 350f))
            add(region("ヒミツのへや", 200f, 50f, -12.6f, 1100f, 500f))
        }
        val groups = LayoutAnalyzer.analyze(
            regions, sourceLang = "ja", screenshotWidthInRegionSpace = 1920f,
        )
        val slanted = groups.filter { it.angleDeg != 0f }
        val report = groups.joinToString("\n") {
            "  angle=${it.angleDeg} '${it.text.take(16)}' bounds=${it.bounds.toShortString()}"
        }
        assertTrue(
            "expected slanted groups to survive; got:\n$report",
            slanted.isNotEmpty(),
        )
        // Every accepted slant should reach the output as a non-zero group
        // angle (they cluster apart from the exact-0 mass at cap 4°).
        assertTrue(
            "expected ≥3 slanted groups (got ${slanted.size}):\n$report",
            slanted.size >= 3,
        )
    }

    @Test
    fun singleLightSlant_amongUprightMass_survivesGrouping() {
        // The absorption hazard: ONE light slant whose greedy run captures the
        // exact-0 mass (|0−(−3.4)| ≤ cap 4°), θ̄ from the longest member (an
        // upright dialogue line) = exactly 0, residual excursion of the slant
        // under 0.35·h — the measured angle would be silently dropped.
        val regions = buildList {
            add(region("むかしむかし、あるところに", 620f, 40f, 0f, 700f, 900f))
            add(region("おじいさんとおばあさんが", 560f, 40f, 0f, 680f, 960f))
            add(region("メニュー", 170f, 34f, 0f, 1500f, 80f))
            add(region("ランクアップ", 250f, 40f, -3.4f, 900f, 300f))
        }
        val groups = LayoutAnalyzer.analyze(
            regions, sourceLang = "ja", screenshotWidthInRegionSpace = 1920f,
        )
        val slanted = groups.filter { it.angleDeg != 0f }
        assertTrue(
            "single −3.4° slant absorbed by the 0° mass:\n" +
                groups.joinToString("\n") { "  angle=${it.angleDeg} '${it.text.take(16)}'" },
            slanted.isNotEmpty(),
        )
    }
}
