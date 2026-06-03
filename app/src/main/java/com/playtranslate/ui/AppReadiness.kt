package com.playtranslate.ui

/**
 * The app's top-level readiness, derived from setup completeness + screen mode.
 *
 * This is the single value the onboarding gate routes on: either the user is
 * still in setup ([Onboarding], parameterised by which [Step] is outstanding),
 * or setup is complete and the app shows its steady-state [Ready] home.
 *
 * [Ready] carries its [Home] variant as data (not a bare object) on purpose: a
 * screen-mode hot-plug while already Ready must change the *value* so a
 * `StateFlow` re-emits and the router re-forks the home. A bare `Ready` object
 * would conflate single<->dual and strand the user in the wrong home (e.g. the
 * non-dismissible single-screen dialog on a now dual-screen device).
 */
sealed interface AppReadiness {
    /** The outstanding setup steps, in the order onboarding presents them. */
    enum class Step { LANGUAGE, NOTIFICATION, CAPTURE }

    /** The steady-state home surface, selected by screen mode. */
    enum class Home { SINGLE_SCREEN, DUAL }

    data class Onboarding(val step: Step) : AppReadiness

    data class Ready(val home: Home) : AppReadiness
}

/**
 * Pure readiness derivation — no `Context`, no singletons, no side effects.
 *
 * The caller gathers the four inputs from their (impure) sources and performs
 * any side-effecting refresh (notably `CaptureBackendResolver.reresolve`, which
 * can tear down a stale capture session) *before* calling this. Keeping the
 * derivation pure is what makes the gate unit-testable and keeps service
 * teardown out of the readiness logic.
 *
 * @param languageConfigured source pack installed AND a target language chosen
 * @param notifGranted POST_NOTIFICATIONS granted (or running pre-Tiramisu)
 * @param captureReady the active capture backend can run now — accessibility
 *   enabled, or the backend (e.g. MediaProjection) doesn't require it
 * @param singleScreen the viewport is a single screen, where the forced
 *   non-dismissible settings "home" applies
 */
fun computeReadiness(
    languageConfigured: Boolean,
    notifGranted: Boolean,
    captureReady: Boolean,
    singleScreen: Boolean,
): AppReadiness = when {
    !languageConfigured -> AppReadiness.Onboarding(AppReadiness.Step.LANGUAGE)
    !notifGranted -> AppReadiness.Onboarding(AppReadiness.Step.NOTIFICATION)
    !captureReady -> AppReadiness.Onboarding(AppReadiness.Step.CAPTURE)
    else -> AppReadiness.Ready(
        if (singleScreen) AppReadiness.Home.SINGLE_SCREEN else AppReadiness.Home.DUAL,
    )
}
