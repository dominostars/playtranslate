package com.playtranslate.ui

import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.AnkiManager
import com.playtranslate.OverlayMode
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.language.HintTextKind
import com.playtranslate.translation.Cooldownable
import com.playtranslate.translation.TranslationBackend
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [RootSettingsViewModel] projects the root Settings drill-down cells. This
 * pins the Anki conditional-cell state machine (get-app → grant → navigate) —
 * the logic the renderer now consumes via render() instead of computing inline
 * — plus the language-name projection and the TTS Loading seed. The power card
 * and stale-pack card are deliberately not modelled here (live-system-state,
 * still renderer-owned). Construction seeds [RootSettingsViewModel.state]
 * synchronously, so each case sets the world up first, then asserts on
 * `state.value`.
 */
@RunWith(RobolectricTestRunner::class)
class RootSettingsViewModelTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val ctx: Context = app

    @Before fun clearPrefs() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After fun tearDown() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun installAnkiDroid() {
        shadowOf(app.packageManager).installPackage(
            PackageInfo().apply { packageName = "com.ichi2.anki" },
        )
    }

    @Test fun `target name projects from prefs`() {
        Prefs(ctx).targetLang = "en"
        val vm = RootSettingsViewModel(app)
        assertEquals("English", vm.state.value.targetName)
    }

    @Test fun `source name is populated`() {
        Prefs(ctx).sourceLang = "ja"
        val vm = RootSettingsViewModel(app)
        assertTrue(vm.state.value.sourceName.isNotBlank())
    }

    @Test fun `anki cell — not installed → GetApp`() {
        val vm = RootSettingsViewModel(app)
        assertEquals(RootSettingsViewModel.AnkiCell.GetApp, vm.state.value.anki)
    }

    @Test fun `anki cell — installed without permission → GrantAccess`() {
        installAnkiDroid()
        val vm = RootSettingsViewModel(app)
        assertEquals(RootSettingsViewModel.AnkiCell.GrantAccess, vm.state.value.anki)
    }

    @Test fun `anki cell — installed + granted → Navigate`() {
        installAnkiDroid()
        shadowOf(app).grantPermissions(AnkiManager.PERMISSION)
        val vm = RootSettingsViewModel(app)
        assertTrue(vm.state.value.anki is RootSettingsViewModel.AnkiCell.Navigate)
    }

    @Test fun `tts cell seeds as Loading`() {
        val vm = RootSettingsViewModel(app)
        assertEquals(RootSettingsViewModel.TtsCell.Loading, vm.state.value.tts)
    }

    @Test fun `refresh re-resolves the anki cell after the grant lands`() {
        installAnkiDroid()
        val vm = RootSettingsViewModel(app)
        assertEquals(RootSettingsViewModel.AnkiCell.GrantAccess, vm.state.value.anki)
        // Grant arrives while Settings is backgrounded — refresh() (called on
        // resume) must re-poll and flip the cell to Navigate.
        shadowOf(app).grantPermissions(AnkiManager.PERMISSION)
        vm.refresh()
        assertTrue(vm.state.value.anki is RootSettingsViewModel.AnkiCell.Navigate)
    }

    // ── Translation digest ────────────────────────────────────────────────

    // The digest gates on isUsable (the waterfall's own gate), NOT the enable
    // pref — so the fake's usability is controlled directly.
    private open class FakeBackend(
        override val id: String,
        override val displayName: String,
        override val priority: Int,
        override val requiresInternet: Boolean,
        override val isDegradedFallback: Boolean = false,
        private val usable: Boolean = true,
    ) : TranslationBackend {
        override fun isUsable(source: String, target: String) = usable
        override suspend fun translate(text: String, source: String, target: String) = text
    }

    @Test fun `online digest is highest-priority usable + a plus per extra`() {
        val (online, _) = translationDigest(
            listOf(
                FakeBackend("gemini", "Gemini", 7, requiresInternet = true, usable = true),
                FakeBackend("deepl", "DeepL", 10, requiresInternet = true, usable = true),
                FakeBackend("lingva", "Lingva", 20, requiresInternet = true, usable = false),
            ),
            "ja", "en", "None", 0L,
        )
        assertEquals("Gemini+", online)
    }

    @Test fun `single usable online backend has no plus`() {
        val (online, _) = translationDigest(
            listOf(
                FakeBackend("gemini", "Gemini", 7, requiresInternet = true, usable = true),
                FakeBackend("deepl", "DeepL", 10, requiresInternet = true, usable = false),
            ),
            "ja", "en", "None", 0L,
        )
        assertEquals("Gemini", online)
    }

    @Test fun `no usable online backend reads None`() {
        val (online, _) = translationDigest(
            listOf(FakeBackend("gemini", "Gemini", 7, requiresInternet = true, usable = false)),
            "ja", "en", "None", 0L,
        )
        assertEquals("None", online)
    }

    @Test fun `enabled-but-unusable backend is not advertised`() {
        // Bergamot-style: present + toggled on, but no usable model for the pair
        // (isUsable false). Must not be advertised; ML Kit is the excluded
        // fallback, so the offline side reads None.
        val (_, offline) = translationDigest(
            listOf(
                FakeBackend("bergamot", "Bergamot", 28, requiresInternet = false, usable = false),
                FakeBackend("mlkit", "ML Kit", 30, requiresInternet = false, isDegradedFallback = true, usable = true),
            ),
            "ja", "en", "None", 0L,
        )
        assertEquals("None", offline)
    }

    @Test fun `offline digest excludes the ML Kit fallback even when usable`() {
        val (_, offline) = translationDigest(
            listOf(
                FakeBackend("bergamot", "Bergamot", 28, requiresInternet = false, usable = true),
                FakeBackend("mlkit", "ML Kit", 30, requiresInternet = false, isDegradedFallback = true, usable = true),
            ),
            "ja", "en", "None", 0L,
        )
        assertEquals("Bergamot", offline)
    }

    @Test fun `digest evaluates usability for the given pair`() {
        val seen = mutableListOf<Pair<String, String>>()
        translationDigest(
            listOf(
                object : FakeBackend("gemini", "Gemini", 7, requiresInternet = true) {
                    override fun isUsable(source: String, target: String): Boolean {
                        seen += source to target
                        return true
                    }
                },
            ),
            "ja", "en", "None", 0L,
        )
        assertEquals(listOf("ja" to "en"), seen)
    }

    private class CoolingFakeBackend(
        id: String,
        displayName: String,
        priority: Int,
        requiresInternet: Boolean,
        private val until: Long,
    ) : FakeBackend(id, displayName, priority, requiresInternet, usable = true), Cooldownable {
        override fun unavailableUntil(): Long? = until
        override fun unavailableDescription(): String? = "test cooldown"
        override fun recordSuccess(attemptStartedAtMs: Long) {}
    }

    @Test fun `a cooling-down backend is excluded from the digest`() {
        val now = 1_000L
        // Gemini cools down until now+1 (skipped, like the waterfall) → DeepL wins.
        val (online, _) = translationDigest(
            listOf(
                CoolingFakeBackend("gemini", "Gemini", 7, requiresInternet = true, until = now + 1),
                FakeBackend("deepl", "DeepL", 10, requiresInternet = true, usable = true),
            ),
            "ja", "en", "None", now,
        )
        assertEquals("DeepL", online)
    }

    @Test fun `an elapsed cooldown does not exclude the backend`() {
        val now = 1_000L
        // unavailableUntil <= now → ready (the waterfall's `> now` gate).
        val (online, _) = translationDigest(
            listOf(CoolingFakeBackend("gemini", "Gemini", 7, requiresInternet = true, until = now)),
            "ja", "en", "None", now,
        )
        assertEquals("Gemini", online)
    }

    @Test fun `overlay mode label falls back to Translation without a hint layer`() {
        assertEquals(
            R.string.overlay_mode_option_translation,
            overlayModeLabelRes(OverlayMode.FURIGANA, HintTextKind.NONE),
        )
        assertEquals(
            R.string.overlay_mode_option_furigana,
            overlayModeLabelRes(OverlayMode.FURIGANA, HintTextKind.FURIGANA),
        )
        assertEquals(
            R.string.overlay_mode_option_pinyin,
            overlayModeLabelRes(OverlayMode.FURIGANA, HintTextKind.PINYIN),
        )
        assertEquals(
            R.string.overlay_mode_option_translation,
            overlayModeLabelRes(OverlayMode.TRANSLATION, HintTextKind.FURIGANA),
        )
    }

    // ── Hotkeys / Appearance digests ──────────────────────────────────────

    @Test @Config(sdk = [34])
    fun `hotkeys digest shows the Add tile CTA when unadded on api 33+`() {
        Prefs(ctx).apply { quickTileAdded = false; hotkeyTranslation = ""; hotkeyFurigana = "" }
        val s = RootSettingsViewModel(app).state.value.hotkeysSummary
        assertTrue(s.startsWith("Add tile"))
        assertTrue(s.endsWith("No hotkeys set"))
    }

    @Test @Config(sdk = [34])
    fun `hotkeys digest drops the tile mention once the tile is added`() {
        Prefs(ctx).apply { quickTileAdded = true; hotkeyTranslation = "ctrl+t"; hotkeyFurigana = "" }
        // Tile added → not mentioned; just the hotkey state remains.
        assertEquals("Translation", RootSettingsViewModel(app).state.value.hotkeysSummary)
    }

    @Test @Config(sdk = [31])
    fun `hotkeys digest omits the tile CTA below api 33`() {
        Prefs(ctx).apply { quickTileAdded = false; hotkeyTranslation = ""; hotkeyFurigana = "" }
        assertEquals("No hotkeys set", RootSettingsViewModel(app).state.value.hotkeysSummary)
    }

    @Test @Config(sdk = [34])
    fun `hint hotkey is labeled Furigana for a Japanese source`() {
        Prefs(ctx).apply { quickTileAdded = true; hotkeyTranslation = ""; hotkeyFurigana = "ctrl+f" }
        // Default source is Japanese → hint layer present; tile added so dropped.
        assertEquals("Furigana", RootSettingsViewModel(app).state.value.hotkeysSummary)
    }

    @Test @Config(sdk = [34])
    fun `stale hint hotkey is not advertised when the source has no hint layer`() {
        Prefs(ctx).apply {
            sourceLang = "ko"; quickTileAdded = true; hotkeyTranslation = ""; hotkeyFurigana = "ctrl+f"
        }
        // Korean → HintTextKind.NONE → the leftover furigana hotkey must not show.
        assertEquals("No hotkeys set", RootSettingsViewModel(app).state.value.hotkeysSummary)
    }

    @Test fun `appearance digest is theme mode + accent name`() {
        val s = RootSettingsViewModel(app).state.value.appearanceSummary
        assertTrue(s.startsWith("System")) // default theme mode
        assertTrue(s.endsWith("Aqua"))     // default accent
    }

    // ── TTS cell tap mapping ──────────────────────────────────────────────

    @Test fun `tts Loading is non-interactive (no picker before availability resolves)`() {
        assertEquals(null, ttsTapFor(RootSettingsViewModel.TtsCell.Loading))
    }

    @Test fun `tts Available opens the picker, NoEngine opens setup`() {
        assertEquals(
            TtsTap.OPEN_PICKER,
            ttsTapFor(RootSettingsViewModel.TtsCell.Available("Google TTS", "Voice 1")),
        )
        assertEquals(TtsTap.OPEN_SETUP, ttsTapFor(RootSettingsViewModel.TtsCell.NoEngine))
    }
}
