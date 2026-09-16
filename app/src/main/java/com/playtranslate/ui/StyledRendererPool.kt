package com.playtranslate.ui

import android.view.ViewGroup

/**
 * A capped supply of styled renderers for a column of [WordResultCell]s —
 * the results page's Words card ([WordRowsBinder]). Up to [cap]
 * [YomitanDefinitionsView]s are minted lazily as rows ask, kept for the
 * page's life and handed back on every rebuild, so a capture's re-render
 * costs [cap] content swaps rather than [cap] shell loads, and a long list
 * can never hold more than [cap] WebViews at once: rows past the cap bind
 * flat. A cap of 0 is the flat tier outright. The sizing is
 * [STYLED_WORD_ROW_CAP]'s.
 *
 * Renderer death — one crash kills every page in the renderer process —
 * retires the pool for the page's life: the dead renderers are dropped as
 * their cells hand them back, the idle ones are destroyed, and nothing new
 * is minted (the sentence sheet's rule, chosen over a crash-and-remint
 * loop). [destroyAll] retires it the same way at page teardown. Main
 * thread only.
 */
internal class StyledRendererPool(private val cap: Int) : WordResultCell.StyledRendererSource {

    private val idle = ArrayDeque<YomitanDefinitionsView>()

    /** Minted and not yet destroyed: idle plus lent out. */
    private var live = 0

    /** No renderer leaves this pool again: a death, or [destroyAll]. */
    private var retired = false

    /** Renderers alive right now, idle or lent — the bound the cap promises. */
    val liveCount: Int get() = live

    override fun acquire(create: () -> YomitanDefinitionsView): YomitanDefinitionsView? {
        if (retired) return null
        idle.removeFirstOrNull()?.let { v ->
            if (v.isUsable()) return v
            // Died while idle (its hooks were cleared, so nobody heard):
            // the whole renderer is gone with it.
            live--
            retire()
            return null
        }
        if (live >= cap) return null
        val v = create()
        if (!v.isUsable()) {
            // No WebView provider on the device; the empty wrapper holds
            // nothing worth destroying. Don't ask again.
            retired = true
            return null
        }
        live++
        return v
    }

    override fun release(v: YomitanDefinitionsView) {
        // A cell's hooks must not outlive its hold: an idle renderer that
        // dies would otherwise call back into a cell that has moved on.
        v.onContentHeight = null
        v.onRendererGone = null
        v.onBodyTap = null
        (v.parent as? ViewGroup)?.removeView(v)
        when {
            !v.isUsable() -> {
                // Destroyed itself on renderer death; every other page in
                // that renderer is dead too.
                live--
                retire()
            }
            retired -> {
                v.destroy()
                live--
            }
            else -> idle.addLast(v)
        }
    }

    /** Page teardown: destroy every idle renderer and take back no more.
     *  The host releases its cells first, so the lent ones are idle by
     *  now; one handed back later is destroyed on the spot. Idempotent. */
    fun destroyAll() = retire()

    private fun retire() {
        retired = true
        idle.forEach { it.destroy() }
        live -= idle.size
        idle.clear()
    }
}
