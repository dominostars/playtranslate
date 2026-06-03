package com.playtranslate.ui

/**
 * What the steady-state home should do to the tab surface, decided purely from
 * the readiness transition. The Activity executes the [HomeAction] and persists
 * the returned [HomeDecision.restoredTab].
 */
sealed interface HomeAction {
    /** Single-screen: the settings panel is the only surface — select Settings
     *  and hide the bottom bar. */
    object ForceSettings : HomeAction

    /** Dual: select [tab] (a restored excursion tab, or the entry default). */
    data class ShowTab(val tab: Tab) : HomeAction

    /** Dual: leave the current tab alone (a plain dual→dual resume). */
    object KeepTab : HomeAction
}

/** [action] plus the new value of the one-shot `restoredTab` — the tab saved
 *  across an Activity recreation, to re-select on the next entry to dual. Making
 *  it an explicit output keeps the bookkeeping out of the imperative executor. */
data class HomeDecision(val action: HomeAction, val restoredTab: Tab?)

/**
 * Pure home-routing decision — no `Context`, no fragments, no side effects.
 * Unit-tested like [computeReadiness]; the executor in MainActivity is a thin
 * `when` over the returned [HomeAction].
 *
 * [AppReadiness.Home.SINGLE_SCREEN] always forces Settings (the only
 * single-screen surface). We deliberately do *not* remember which dual tab the
 * user came from — returning to dual lands on Settings. Tracking the outgoing
 * tab would mean persisting a second piece of state that collides with the
 * standard `savedInstanceState` tab on recreation (a maintainer reading
 * `restoredTab` would have to reason about two lifecycles); the small UX cost of
 * landing on the control panel after a single-screen excursion isn't worth that.
 *
 * The two ways to reach [AppReadiness.Home.DUAL] are told apart by [prev]:
 *  - `prev !is Ready` (leaving onboarding / cold launch / recreation) → pick the
 *    entry tab: the recreation-saved [restoredTab], else Settings on the
 *    MediaProjection backend (where Turn On lives), else Translate;
 *  - otherwise (a plain dual resume, or coming back from single) → keep the
 *    current tab. Coming back from single that is Settings, since single forced it.
 */
fun decideHome(
    prev: AppReadiness?,
    home: AppReadiness.Home,
    restoredTab: Tab?,
    requiresA11y: Boolean,
): HomeDecision = when (home) {
    AppReadiness.Home.SINGLE_SCREEN -> HomeDecision(HomeAction.ForceSettings, restoredTab)

    AppReadiness.Home.DUAL -> when {
        prev !is AppReadiness.Ready ->
            HomeDecision(
                HomeAction.ShowTab(restoredTab ?: if (requiresA11y) Tab.TRANSLATE else Tab.SETTINGS),
                restoredTab = null,
            )

        else -> HomeDecision(HomeAction.KeepTab, restoredTab)
    }
}
