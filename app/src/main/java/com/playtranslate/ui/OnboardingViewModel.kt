package com.playtranslate.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.playtranslate.PlayTranslateAccessibilityService
import com.playtranslate.Prefs
import com.playtranslate.capture.CaptureBackendResolver
import com.playtranslate.language.LanguagePackStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * The onboarding readiness gate. Projects setup completeness + screen mode into
 * the [AppReadiness] that MainActivity routes on, replacing the old imperative
 * `checkOnboardingState()` derivation.
 *
 * Reactivity is split, because the gate's inputs come from very different
 * sources:
 *  - A [Prefs.observe] collector covers the few *pref-backed* inputs
 *    ([Prefs.ONBOARDING_GATE_KEYS]: source / target language, the debug
 *    force-single toggle). These re-derive on their own when written anywhere.
 *  - Everything else — pack-installed disk state, notification + accessibility
 *    permissions, the active capture backend, display topology / multi-window —
 *    is NOT pref-observable, so MainActivity calls [refresh] from each legacy
 *    onboarding trigger (onResume, display add/remove/change, the notification
 *    permission result, screen-mode change, welcome-pack install).
 *
 * `CaptureBackendResolver.reresolve` is deliberately NOT called here: it has
 * side effects (on a backend swap it stops live mode and destroys the
 * MediaProjection session). MainActivity calls reresolve immediately before
 * [refresh]; this VM only *reads* `active()`.
 *
 * The initial state is `null` — the gate intentionally derives nothing until
 * MainActivity's first [refresh] from `onResume`, where the foreground statics
 * ([Prefs.isSingleScreen] reads `MainActivity.isInForeground`) and the
 * app-start `reresolve` have settled. This mirrors the legacy timing, where the
 * first `checkOnboardingState()` ran in `onResume`, and avoids routing a
 * half-derived state during `onStart`. The observe seed is dropped for the same
 * reason.
 */
class OnboardingViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<AppReadiness?>(null)

    /** Null until MainActivity's first [refresh]; thereafter the current
     *  readiness. The router treats null as "nothing to route yet." */
    val state: StateFlow<AppReadiness?> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            Prefs(getApplication()).observe(*Prefs.ONBOARDING_GATE_KEYS)
                .drop(1) // skip the on-subscribe seed; onResume drives the first derive
                .collect { _state.value = derive() }
        }
    }

    /** Re-read the non-pref inputs (system / disk / display) and re-derive.
     *  MainActivity calls this after [CaptureBackendResolver.reresolve] on each
     *  onboarding trigger. */
    fun refresh() {
        _state.value = derive()
    }

    private fun derive(): AppReadiness {
        val app = getApplication<Application>()
        val prefs = Prefs(app)
        // A FORCE-stale active source pack (e.g. a pre-Sudachi ja-v2 install) is
        // installed-but-non-functional: isInstalled() is true because the
        // dict.sqlite schema is current, yet the tokenizer payload is obsolete.
        // Treat it as NOT configured so the gate routes to the welcome screen
        // and the home is never composed for a broken language. Because derive()
        // re-runs on every onResume (and pref change), this holds across
        // backgrounding — the mandatory upgrade can't be escaped by sending the
        // app home and returning. isInstalled is checked first so a schema-
        // corrupt pack (which isInstalled deletes) short-circuits here.
        val languageConfigured =
            LanguagePackStore.isInstalled(app, prefs.sourceLangId) &&
                !LanguagePackStore.isForcedUpgrade(app, prefs.sourceLangId) &&
                prefs.hasTargetLangBeenSet
        val notifGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        // captureReady only requires the accessibility page when the *active*
        // backend depends on the service; MediaProjection does not.
        val captureReady = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // API 29: MediaProjection is the only backend, so active() is always MP
            // and the !requiresAccessibilityService proxy below is always true — it
            // can't gate the capture step. "Ready" here means the overlay permission
            // the MediaProjection floating controls need has been granted.
            Settings.canDrawOverlays(app)
        } else {
            PlayTranslateAccessibilityService.isEnabled(app) ||
                !CaptureBackendResolver.active().requiresAccessibilityService
        }
        val singleScreen = Prefs.isSingleScreen(app)
        return computeReadiness(languageConfigured, notifGranted, captureReady, singleScreen)
    }
}
