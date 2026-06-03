package com.playtranslate.ui

import android.app.Application
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.playtranslate.AnkiManager
import com.playtranslate.OverlayMode
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.capturableDisplays
import com.playtranslate.language.HintTextKind
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.ocr.registry.OcrModelManager
import com.playtranslate.ocr.registry.ocrLabel
import com.playtranslate.translation.Cooldownable
import com.playtranslate.translation.TranslationBackend
import com.playtranslate.translation.TranslationBackendRegistry
import com.playtranslate.tts.TtsEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/** Field separator shared by every cell digest ("System · Teal"). */
private const val SEP = " · "

/** Compose the ("online", "offline") translation digests from a backend list:
 *  the highest-priority **usable, non-cooling-down** backend on each side + a
 *  "+" per additional, or [none]. Degraded fallbacks (ML Kit) are excluded, and
 *  a backend in an active cooldown at [now] is skipped — matching the waterfall,
 *  so the digest never advertises a service translations won't actually use.
 *  Pure (no singleton / Context) → unit-testable; the VM passes the live
 *  [TranslationBackendRegistry.orderedBackends] + the pair + the clock. */
internal fun translationDigest(
    backends: List<TranslationBackend>,
    source: String,
    target: String,
    none: String,
    now: Long,
): Pair<String, String> {
    val active = backends.filter {
        !it.isDegradedFallback && it.isUsable(source, target) && !it.isCoolingDown(now)
    }
    val (online, offline) = active.partition { it.requiresInternet }
    fun digest(list: List<TranslationBackend>) =
        if (list.isEmpty()) none else list.first().displayName + "+".repeat(list.size - 1)
    return digest(online) to digest(offline)
}

/** True when a [Cooldownable] backend is in an active cooldown at [now] — the
 *  same gate the waterfall uses (it skips `unavailableUntil() > now`), so the
 *  digest reflects what would actually run. */
private fun TranslationBackend.isCoolingDown(now: Long): Boolean {
    val until = (this as? Cooldownable)?.unavailableUntil()
    return until != null && until > now
}

/** The capture overlay-mode label resource for [mode] + the source language's
 *  [hintKind]: hint mode shows Furigana/Pinyin only when the language actually
 *  has that layer; otherwise (incl. a stale FURIGANA mode on a no-hint language)
 *  it reads "Translation". Pure → unit-testable. */
@StringRes
internal fun overlayModeLabelRes(mode: OverlayMode, hintKind: HintTextKind): Int =
    if (mode == OverlayMode.FURIGANA && hintKind != HintTextKind.NONE) {
        if (hintKind == HintTextKind.PINYIN) R.string.overlay_mode_option_pinyin
        else R.string.overlay_mode_option_furigana
    } else {
        R.string.overlay_mode_option_translation
    }

/**
 * Root Settings drill-down state, projected from [Prefs] + live system state.
 *
 * Scope (Stage 6, "right-sized"): this VM owns the state-dependent CONFIGURE
 * cells, their status **digests** (the hub-cell summary lines), and the
 * language names. The power card / capture-lifecycle surface and the stale-pack
 * update card are deliberately NOT here — they project live system state with
 * no pref to observe and stay imperative + resume-driven in [SettingsRenderer].
 *
 * Two state channels:
 *  - **Reactive** — names + the Hotkeys / Appearance digests re-derive from
 *    `Prefs.observe` (all their inputs are prefs), so an accent change or hotkey
 *    edit shows with no onResume catch-up.
 *  - **Polled** — the Translation digest (real backend *usability* — installed
 *    models + keys + pair, not just toggles), the Capture digest (display set /
 *    OCR availability / overlay mode), the Anki cell + deck/card-type, and the
 *    TTS engine + voice. [refresh] re-reads them on resume; their inputs change
 *    on sub-pages or at the system level, so resume is the catch-up point.
 */
class RootSettingsViewModel(app: Application) : AndroidViewModel(app) {

    /** Anki CONFIGURE cell: the get-app → grant → navigate conditional. */
    sealed interface AnkiCell {
        /** AnkiDroid not installed — tapping opens the Play Store listing. */
        object GetApp : AnkiCell
        /** Installed but the API permission isn't granted — tap requests it. */
        object GrantAccess : AnkiCell
        /** Installed + granted — tap opens [AnkiSettingsActivity]; the digest
         *  shows the chosen deck + card type. */
        data class Navigate(val deckName: String, val cardName: String) : AnkiCell
    }

    /** TTS CONFIGURE cell (no sub-page; behaves like the old TTS row). */
    sealed interface TtsCell {
        /** Engine availability not resolved yet (async check in flight). */
        object Loading : TtsCell
        /** A usable engine — digest is "engine · voice"; tap opens the voice
         *  picker. [engineLabel] is null when the engine name can't be read. */
        data class Available(val engineLabel: String?, val voiceLabel: String) : TtsCell
        /** No usable engine — tapping shows the "No Text-to-Speech" alert. */
        object NoEngine : TtsCell
    }

    data class UiState(
        val sourceName: String,
        val targetName: String,
        /** Translation digest halves, each "Name", "Name+", or "None". The
         *  renderer prefixes them with the cloud / cloud-off glyphs. */
        val translationOnline: String,
        val translationOffline: String,
        val hotkeysSummary: String,
        val appearanceSummary: String,
        val captureSummary: String,
        val anki: AnkiCell,
        val tts: TtsCell,
    )

    private val prefs = Prefs(app)

    private val _state = MutableStateFlow(seed())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        // Names + the Hotkeys / Appearance digests react to their backing prefs
        // (source/target language, hotkey edits, accent/theme). observe() seeds
        // on collect, so this also covers first render. The Translation digest
        // is polled in refresh() instead — it depends on non-pref state
        // (installed models, keys), so a pref signal can't carry it.
        viewModelScope.launch {
            prefs.observe(
                Prefs.KEY_SOURCE_LANG, Prefs.KEY_TARGET_LANG,
                Prefs.KEY_QUICK_TILE_ADDED, Prefs.KEY_HOTKEY_TRANSLATION, Prefs.KEY_HOTKEY_FURIGANA,
                Prefs.KEY_THEME_MODE, Prefs.KEY_ACCENT_NAME,
            ).collect {
                _state.value = _state.value.copy(
                    sourceName = resolveSourceName(),
                    targetName = resolveTargetName(),
                    hotkeysSummary = hotkeysDigest(),
                    appearanceSummary = appearanceDigest(),
                )
            }
        }
    }

    /** Re-poll the state no pref signal carries: the Translation digest (real
     *  backend usability — installed models / keys / pair support), the Anki
     *  permission + deck/card, and the TTS engine + voice. Called on resume. */
    fun refresh() {
        val (online, offline) = translationDigests()
        _state.value = _state.value.copy(
            anki = resolveAnkiCell(),
            translationOnline = online,
            translationOffline = offline,
            captureSummary = captureDigest(),
        )
        viewModelScope.launch {
            _state.value = _state.value.copy(tts = resolveTtsCell())
        }
    }

    private fun seed(): UiState {
        val (online, offline) = translationDigests()
        return UiState(
            sourceName = resolveSourceName(),
            targetName = resolveTargetName(),
            translationOnline = online,
            translationOffline = offline,
            hotkeysSummary = hotkeysDigest(),
            appearanceSummary = appearanceDigest(),
            captureSummary = captureDigest(),
            anki = resolveAnkiCell(),
            tts = TtsCell.Loading,
        )
    }

    // ── Translation digest ────────────────────────────────────────────────

    /** Wraps the pure [translationDigest] with the live registry + the same
     *  source/target the waterfall uses (CaptureService): the source's
     *  translationCode (not the raw stored code) + the target lang. So an
     *  enabled-but-keyless online backend, or Bergamot with no installed model
     *  for this pair, is correctly NOT advertised — `isUsable` is the gate. */
    private fun translationDigests(): Pair<String, String> =
        translationDigest(
            TranslationBackendRegistry.orderedBackends(),
            SourceLanguageProfiles[prefs.sourceLangId].translationCode,
            prefs.targetLang,
            str(R.string.settings_digest_none),
            System.currentTimeMillis(),
        )

    // ── Hotkeys digest ────────────────────────────────────────────────────

    private fun hotkeysDigest(): String {
        val hintKind = SourceLanguageProfiles[prefs.sourceLangId].hintTextKind
        val hasTranslation = prefs.hotkeyTranslation.isNotEmpty()
        // A leftover furigana hotkey from a prior language must not surface when
        // the current source has no hint layer — matches the Hotkeys page hiding
        // that row for HintTextKind.NONE.
        val hasHint = prefs.hotkeyFurigana.isNotEmpty() && hintKind != HintTextKind.NONE
        val hintLabel = str(
            if (hintKind == HintTextKind.PINYIN) R.string.settings_hotkeys_pinyin
            else R.string.settings_hotkeys_furigana,
        )
        val translationLabel = str(R.string.settings_hotkeys_translation)
        val hotkeys = when {
            hasTranslation && hasHint -> "$translationLabel, $hintLabel"
            hasTranslation -> translationLabel
            hasHint -> hintLabel
            else -> str(R.string.settings_hotkeys_none)
        }
        // "Add tile" only when it's actionable: the add flow is API 33+
        // (StatusBarManager.requestAddTileService), and once the tile is added
        // there's nothing left to prompt — so it drops from the digest.
        val showAddTile = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !prefs.quickTileAdded
        return if (showAddTile) str(R.string.settings_hotkeys_tile_add) + SEP + hotkeys else hotkeys
    }

    // ── Appearance digest ─────────────────────────────────────────────────

    private fun appearanceDigest(): String {
        val mode = str(
            when (prefs.themeMode) {
                ThemeMode.SYSTEM -> R.string.pt_theme_mode_system
                ThemeMode.LIGHT -> R.string.pt_theme_mode_light
                ThemeMode.DARK -> R.string.pt_theme_mode_dark
            },
        )
        return mode + SEP + str(prefs.accent.displayName)
    }

    // ── Capture digest (polled — live display / OCR state) ────────────────

    /** "[display →] OCR → mode": what the capture pipeline is set to do. Display
     *  is omitted single-screen, the display name when exactly one is selected,
     *  or "N Displays" for several. Live system state (display set, OCR
     *  availability), so it's recomputed in refresh(), not observed. */
    private fun captureDigest(): String {
        val ctx = getApplication<Application>()
        val parts = mutableListOf<String>()
        if (!Prefs.isSingleScreen(ctx)) {
            val ids = prefs.captureDisplayIds
            val displayPart = if (ids.size >= 2) {
                ctx.getString(R.string.settings_capture_displays_count, ids.size)
            } else {
                ids.firstOrNull()?.let { id ->
                    val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                    dm.capturableDisplays().firstOrNull { it.displayId == id }?.name
                }
            }
            if (displayPart != null) parts += displayPart
        }
        parts += OcrModelManager.selectedBackend(ctx, prefs.sourceLangId).ocrLabel
        val hintKind = SourceLanguageProfiles[prefs.sourceLangId].hintTextKind
        parts += ctx.getString(overlayModeLabelRes(prefs.overlayMode, hintKind))
        return parts.joinToString(" → ")
    }

    // ── Anki / TTS (polled) ───────────────────────────────────────────────

    private fun resolveAnkiCell(): AnkiCell {
        val anki = AnkiManager(getApplication())
        return when {
            !anki.isAnkiDroidInstalled() -> AnkiCell.GetApp
            !anki.hasPermission() -> AnkiCell.GrantAccess
            else -> AnkiCell.Navigate(
                deckName = prefs.ankiDeckName.ifBlank { str(R.string.settings_anki_default) },
                cardName = prefs.ankiModelName.ifBlank { str(R.string.settings_anki_default) },
            )
        }
    }

    private suspend fun resolveTtsCell(): TtsCell {
        val ctx = getApplication<Application>()
        if (!TtsEngine.isEngineAvailable(ctx)) return TtsCell.NoEngine
        val lang = prefs.sourceLangId
        val voices = TtsEngine.voicesFor(ctx, lang)
        val savedName = prefs.ttsVoiceName(lang)
        val idx = if (savedName == null) -1 else voices.indexOfFirst { it.name == savedName }
        val voiceLabel = if (idx >= 0) str(R.string.tts_voice_numbered, idx + 1)
                         else str(R.string.tts_voice_default)
        return TtsCell.Available(TtsEngine.activeEngineLabel(ctx), voiceLabel)
    }

    // ── Language names ────────────────────────────────────────────────────

    private fun resolveSourceName(): String =
        SourceLangId.fromCode(prefs.sourceLang)?.displayName()
            ?: Locale.forLanguageTag(prefs.sourceLang)
                .getDisplayLanguage(Locale.getDefault())
                .replaceFirstChar { it.uppercase(Locale.getDefault()) }

    private fun resolveTargetName(): String {
        val locale = Locale.forLanguageTag(prefs.targetLang)
        return locale.getDisplayLanguage(locale).replaceFirstChar { it.uppercase(locale) }
    }

    private fun str(@StringRes id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)
}
