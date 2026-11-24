package com.playtranslate.translation

import android.app.ActivityManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.translation.gemma.GemmaE2BMnnModel
import com.playtranslate.translation.hymt.HyMt2Model
import com.playtranslate.translation.hymt.HyMtModel
import com.playtranslate.translation.llm.ModelHelper
import com.playtranslate.translation.llm.MultiFileSha
import com.playtranslate.translation.llm.OnDeviceLlmBackend
import com.playtranslate.translation.qwen.Qwen35Mnn2bModel
import com.playtranslate.translation.qwen.QwenMnnModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowVMRuntime
import java.io.File

/**
 * Which on-device models answer a free-form prompt, and when.
 *
 * Type rule: every on-device LLM is a [PromptCompleter], the general chat
 * models (Qwen 2.5, Qwen 3.5, Gemma 4) and the two Hunyuan-MT models, which
 * were made to translate and answer a prompt all the same.
 *
 * [PromptCompleter.canComplete] on any of them is "the model can be run":
 * the device meets its hardware floor (64-bit, total RAM), the user has it
 * switched on, and it is installed. That is exactly the part of
 * [TranslationBackend.isUsable] that is not about a language pair, pinned
 * as a truth table over the three conditions against isUsable, plus a
 * same-language pair, which isUsable refuses and canComplete ignores.
 * Hunyuan-MT 1.5 has a fourth condition, its licence's territory.
 *
 * Robolectric: installation is the model directory's `.sentinel` holding
 * the shipped catalog's sha (as [HyMt2CatalogTest] fakes it; a set of
 * files' aggregate, for a model that is several), the RAM is
 * ActivityManager's memory info and the ABI is the runtime's 64-bit flag.
 * MnnTranslator itself needs the native engine and is not exercised.
 */
@RunWith(RobolectricTestRunner::class)
class OnDeviceCompleterTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    /** One backend, the helper whose directory says it is installed, and
     *  its RAM floor. */
    private class Chat(val backend: OnDeviceLlmBackend, val model: ModelHelper, val totalMemFloor: Long)

    private var enabled = true
    private var permitted = true

    private val models: List<ModelHelper> =
        listOf(QwenMnnModel, Qwen35Mnn2bModel, GemmaE2BMnnModel, HyMt2Model, HyMtModel)

    /** Every on-device LLM: the three chat models, then the two made for
     *  translation. */
    private fun chats(): List<Chat> = listOf(
        QwenMnnBackend(ctx) { enabled }.let { Chat(it, QwenMnnModel, it.totalMemFloorBytes) },
        Qwen35Mnn2bBackend(ctx) { enabled }.let { Chat(it, Qwen35Mnn2bModel, it.totalMemFloorBytes) },
        GemmaE2BMnnBackend(ctx) { enabled }.let { Chat(it, GemmaE2BMnnModel, it.totalMemFloorBytes) },
        HyMt2Backend(ctx) { enabled }.let { Chat(it, HyMt2Model, it.totalMemFloorBytes) },
        hyMt15().let { Chat(it, HyMtModel, it.totalMemFloorBytes) },
    )

    /** As the app wires it: the territory in the switch's provider, and
     *  again apart from the switch. */
    private fun hyMt15() = HyMtBackend(ctx, enabledProvider = { enabled && permitted }, permitted = { permitted })

    @Before fun setUp() {
        enabled = true
        permitted = true
        setTotalMem(16_000_000_000L)
        ShadowVMRuntime.setIs64Bit(true)
        models.forEach { uninstall(it) }
    }

    @After fun tearDown() {
        ShadowVMRuntime.setIs64Bit(true)
        models.forEach { uninstall(it) }
    }

    private fun setTotalMem(bytes: Long) {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(am).setMemoryInfo(ActivityManager.MemoryInfo().apply {
            totalMem = bytes
            availMem = bytes / 2
        })
    }

    /** What the downloader leaves after a verified download: the catalog's
     *  sha in the model directory's sentinel (a set of files' aggregate,
     *  for a model that is several). */
    private fun install(model: ModelHelper) {
        val entry = requireNotNull(model.catalogEntry(ctx)) { "${model.catalogKey} is not in the catalog" }
        val sha = entry.files?.let { MultiFileSha.aggregate(it) }
            ?: requireNotNull(entry.sha256) { "${model.catalogKey} has no sha in the catalog" }
        val dir = model.file(ctx)
        dir.mkdirs()
        File(dir, ".sentinel").writeText(sha)
    }

    private fun uninstall(model: ModelHelper) {
        model.file(ctx).deleteRecursively()
    }

    @Test fun `every on-device model is a prompt completer`() {
        val models: List<TranslationBackend> = listOf(
            QwenMnnBackend(ctx) { true },
            Qwen35Mnn2bBackend(ctx) { true },
            GemmaE2BMnnBackend(ctx) { true },
            HyMtBackend(ctx, enabledProvider = { true }, permitted = { true }),
            HyMt2Backend(ctx) { true },
        )
        models.forEach { assertTrue("${it.id} answers prompts", it is PromptCompleter) }
    }

    @Test fun `Hunyuan-MT 1_5 answers no question outside its licence's territory`() {
        install(HyMtModel)
        // The switch says on throughout, so the territory alone decides.
        val hymt = HyMtBackend(ctx, enabledProvider = { true }, permitted = { permitted })
        assertTrue("the precondition: permitted, on, downloaded", hymt.canComplete())

        permitted = false
        assertFalse("on, and not permitted", hymt.canComplete())
        permitted = true
        assertTrue("read live", hymt.canComplete())
    }

    @Test fun `canComplete is true when the model can be run, whatever the language pair`() {
        for (chat in chats()) {
            install(chat.model)
            val id = chat.backend.id

            assertTrue("$id: enabled, installed, capable device", chat.backend.canComplete())
            assertTrue("$id: precondition, it translates ja to en", chat.backend.isUsable("ja", "en"))
            // A pair isUsable refuses says nothing about answering prompts.
            assertFalse("$id: precondition, no same-language translation", chat.backend.isUsable("ja", "ja"))
            assertTrue("$id: still answers prompts", chat.backend.canComplete())
        }
    }

    @Test fun `canComplete is false when the model is switched off`() {
        for (chat in chats()) {
            install(chat.model)
            enabled = false
            assertFalse("${chat.backend.id}", chat.backend.canComplete())
            enabled = true
            assertTrue("${chat.backend.id}: read live", chat.backend.canComplete())
        }
    }

    @Test fun `canComplete is false when the model is not installed`() {
        for (chat in chats()) {
            uninstall(chat.model)
            assertFalse("${chat.backend.id}: nothing on disk", chat.backend.canComplete())

            // A sentinel for another file set (a stale or partial install).
            chat.model.file(ctx).mkdirs()
            File(chat.model.file(ctx), ".sentinel").writeText("0".repeat(64))
            assertFalse("${chat.backend.id}: stale sentinel", chat.backend.canComplete())
        }
    }

    @Test fun `canComplete is false below the hardware floor`() {
        for (chat in chats()) {
            install(chat.model)
            val id = chat.backend.id

            setTotalMem(chat.totalMemFloor - 1)
            assertFalse("$id: below its RAM floor", chat.backend.canComplete())
            setTotalMem(chat.totalMemFloor)
            assertTrue("$id: at its RAM floor", chat.backend.canComplete())

            ShadowVMRuntime.setIs64Bit(false)
            assertFalse("$id: a 32-bit process cannot load the engine", chat.backend.canComplete())
            ShadowVMRuntime.setIs64Bit(true)
        }
    }

    @Test fun `canComplete is exactly the non-language part of isUsable`() {
        for (chat in chats()) {
            for (on in listOf(true, false)) {
                for (installed in listOf(true, false)) {
                    for (capable in listOf(true, false)) {
                        enabled = on
                        if (installed) install(chat.model) else uninstall(chat.model)
                        setTotalMem(if (capable) chat.totalMemFloor else chat.totalMemFloor - 1)
                        val cell = "${chat.backend.id} on=$on installed=$installed capable=$capable"

                        assertEquals(cell, chat.backend.isUsable("ja", "en"), chat.backend.canComplete())
                        assertEquals(cell, on && installed && capable, chat.backend.canComplete())
                        assertFalse("$cell: same-language", chat.backend.isUsable("en", "en"))
                    }
                }
            }
        }
    }
}
