package com.playtranslate.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.language.LanguagePackCatalogLoader
import com.playtranslate.language.ScriptFamily
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [DefinitionTapGate] over the pairs the app ships: which text of a word
 * page's definitions is text to look a source-language word up in. Each
 * case is the language being read, what the text's markup says it is, the
 * language it would otherwise be in (the dictionary's declared target, else
 * the app's), the line and the tapped word in it.
 *
 * The script of the other language is the platform's to say
 * ([writtenScript]), so the class runs on the framework's locale data.
 *
 * The text in each language is the subject under test, not copy.
 */
@RunWith(RobolectricTestRunner::class)
class DefinitionTapGateTest {

    private fun gate(source: SourceLangId, declared: String, other: String, run: String, token: String) =
        DefinitionTapGate.tappable(source, declared, other, run, token)

    private val jaLine = "興がさめる。"
    private val zhLine = "失去兴趣"

    // Mutation: the script rule is dropped for unmarked text.
    @Test fun `Japanese beside a Latin-script language is told by its script, kanji alone included`() {
        assertEquals(true, gate(SourceLangId.JA, "", "en", jaLine, "興"))
        assertEquals(true, gate(SourceLangId.JA, "", "en", jaLine, "さめる"))
        assertEquals(true, gate(SourceLangId.JA, "", "en", "感興", "感興"))
        assertEquals(false, gate(SourceLangId.JA, "", "en", "to become subdued", "become"))
        // A Latin word in a Japanese line is none of the language's own script.
        assertEquals(false, gate(SourceLangId.JA, "", "en", "CDを買う", "CD"))
        assertEquals(true, gate(SourceLangId.JA, "", "es", jaLine, "興"))
    }

    // Mutations: Han alone counts as Japanese beside Chinese; kana in the
    // line is not asked for.
    @Test fun `Japanese beside Chinese needs kana in the line`() {
        assertEquals(true, gate(SourceLangId.JA, "", "zh", jaLine, "興"))
        assertEquals(false, gate(SourceLangId.JA, "", "zh", zhLine, "兴趣"))
        assertEquals("a kanji-only line cannot be told from Chinese", false, gate(SourceLangId.JA, "", "zh", "感興", "感興"))
        assertEquals(false, gate(SourceLangId.JA, "", "zh-Hant", "興趣", "興趣"))
    }

    // Mutation: Chinese beside Japanese is tappable whatever the line holds.
    @Test fun `Chinese beside Japanese needs a line with no kana`() {
        assertEquals(true, gate(SourceLangId.ZH, "", "ja", zhLine, "兴趣"))
        assertEquals(false, gate(SourceLangId.ZH, "", "ja", jaLine, "興"))
        assertEquals(true, gate(SourceLangId.ZH_HANT, "", "ja", "興趣", "興趣"))
        assertEquals(true, gate(SourceLangId.ZH, "", "en", zhLine, "兴趣"))
        assertEquals(false, gate(SourceLangId.ZH, "", "en", "to lose interest", "lose"))
    }

    @Test fun `Korean, Russian, Arabic, Thai and Hindi beside English are told by their script`() {
        assertEquals(true, gate(SourceLangId.KO, "", "en", "흥미를 잃다", "흥미"))
        assertEquals(false, gate(SourceLangId.KO, "", "en", "to lose interest", "lose"))
        assertEquals(true, gate(SourceLangId.RU, "", "en", "терять интерес", "интерес"))
        assertEquals(false, gate(SourceLangId.RU, "", "en", "to lose interest", "interest"))
        assertEquals(true, gate(SourceLangId.AR, "", "en", "يفقد الاهتمام", "الاهتمام"))
        assertEquals(true, gate(SourceLangId.TH, "", "en", "หมดความสนใจ", "สนใจ"))
        assertEquals(true, gate(SourceLangId.HI, "", "en", "रुचि खोना", "रुचि"))
    }

    // Mutation: a Latin-script source is tappable by its script.
    @Test fun `a Latin-script language beside another needs its text marked, or a dictionary in the language itself`() {
        assertEquals(false, gate(SourceLangId.ES, "", "en", "perder el interés", "interés"))
        assertEquals(false, gate(SourceLangId.EN, "", "es", "to lose interest", "interest"))
        // Nor beside a language of another script: Latin letters in a
        // Japanese definition are as likely a name or an abbreviation.
        assertEquals(false, gate(SourceLangId.ES, "", "ja", "perder el interés", "interés"))
        assertEquals(true, gate(SourceLangId.ES, "es", "en", "perder el interés", "interés"))
        assertEquals(true, gate(SourceLangId.ES, "", "es", "perder el interés", "interés"))
        assertEquals(true, gate(SourceLangId.ES, "", "es-MX", "perder el interés", "interés"))
    }

    // Mutation: text marked as another language is taken by its script.
    @Test fun `text marked as another language is never tappable, and text marked as the source's is`() {
        assertEquals(false, gate(SourceLangId.JA, "zh", "en", "感興", "感興"))
        assertEquals(false, gate(SourceLangId.JA, "en", "ja", jaLine, "興"))
        assertEquals(true, gate(SourceLangId.JA, "ja", "zh", "感興", "感興"))
        assertEquals(true, gate(SourceLangId.JA, "ja-JP", "en", jaLine, "興"))
        assertEquals(true, gate(SourceLangId.ZH_HANT, "zh", "ja", "興趣", "興趣"))
        assertEquals(false, gate(SourceLangId.ES, "en", "es", "to lose interest", "interest"))
    }

    // Mutation: a marking outweighs the script.
    @Test fun `a word in none of the source's own script is not one of its words, whatever the text is marked as`() {
        assertEquals(false, gate(SourceLangId.JA, "ja", "en", "CDを買う", "CD"))
        assertEquals(false, gate(SourceLangId.JA, "", "ja", "to become subdued", "become"))
        assertEquals(false, gate(SourceLangId.RU, "ru", "en", "USB порт", "USB"))
    }

    @Test fun `a same-language pair or a monolingual dictionary makes the text the source's`() {
        assertEquals(true, gate(SourceLangId.JA, "", "ja", "感興", "感興"))
        assertEquals(true, gate(SourceLangId.EN, "", "en", "to lose interest", "interest"))
        assertEquals(true, gate(SourceLangId.ZH, "", "zh-Hans", zhLine, "兴趣"))
        assertEquals(true, gate(SourceLangId.RU, "", "ru", "терять интерес", "интерес"))
    }

    // Mutations: the other language's script is read from the app's own
    // source languages, so one it only translates into counts as another
    // script; the script comparison is dropped.
    @Test fun `a language of the source's own script cannot be told from it, one the app only translates into included`() {
        for (other in listOf("uk", "be", "bg", "mk")) {
            assertEquals(other, false, gate(SourceLangId.RU, "", other, "втрачати інтерес", "інтерес"))
            assertEquals(other, false, gate(SourceLangId.RU, "", other, "терять интерес", "интерес"))
        }
        for (other in listOf("fa", "ur")) {
            assertEquals(other, false, gate(SourceLangId.AR, "", other, "از دست دادن علاقه", "علاقه"))
        }
        assertEquals(false, gate(SourceLangId.HI, "", "mr", "रस गमावणे", "रस"))
        // Cantonese is Chinese's script: beside Chinese nothing tells them
        // apart, beside Japanese the kana does.
        assertEquals(false, gate(SourceLangId.ZH_HANT, "", "yue", "興趣", "興趣"))
        assertEquals(false, gate(SourceLangId.JA, "", "yue", "感興", "感興"))
        assertEquals(true, gate(SourceLangId.JA, "", "yue", jaLine, "興"))
        // What the text is marked as, and a dictionary in the language
        // itself, are asked before the script and still say yes.
        assertEquals(true, gate(SourceLangId.RU, "ru", "uk", "терять интерес", "интерес"))
        assertEquals(true, gate(SourceLangId.RU, "", "ru", "терять интерес", "интерес"))
    }

    @Test fun `a language of another script is told apart, one the app only translates into included`() {
        assertEquals(true, gate(SourceLangId.RU, "", "el", "терять интерес", "интерес"))
        assertEquals(true, gate(SourceLangId.RU, "", "ka", "терять интерес", "интерес"))
        assertEquals(true, gate(SourceLangId.AR, "", "he", "يفقد الاهتمام", "الاهتمام"))
        assertEquals(true, gate(SourceLangId.HI, "", "bn", "रुचि खोना", "रुचि"))
        assertEquals(true, gate(SourceLangId.TH, "", "ta", "หมดความสนใจ", "สนใจ"))
        assertEquals(true, gate(SourceLangId.JA, "", "uk", jaLine, "興"))
        assertEquals(true, gate(SourceLangId.ZH, "", "ko", zhLine, "兴趣"))
    }

    // Mutation: a language of no known script is taken to write another.
    @Test fun `a language whose script is not known cannot be told from the source's`() {
        assertEquals(false, gate(SourceLangId.JA, "", "", jaLine, "興"))
        assertEquals(false, gate(SourceLangId.JA, "", "xx", jaLine, "興"))
        assertEquals(false, gate(SourceLangId.RU, "", "und", "терять интерес", "интерес"))
        assertEquals(false, gate(SourceLangId.RU, "", "not a language", "терять интерес", "интерес"))
        assertEquals(false, gate(SourceLangId.ES, "", "", "perder el interés", "interés"))
        // Marked text needs no other language.
        assertEquals(true, gate(SourceLangId.JA, "ja", "", jaLine, "興"))
    }

    @Test fun `the script of a language is the platform's, by its code alone or with a script or region in it`() {
        assertEquals("Cyrl", writtenScript("uk"))
        assertEquals("Arab", writtenScript("fa"))
        assertEquals("Deva", writtenScript("mr"))
        assertEquals("Latn", writtenScript("en"))
        assertEquals("Jpan", writtenScript("ja"))
        assertEquals("Hans", writtenScript("zh"))
        assertEquals("Hant", writtenScript("zh-TW"))
        assertEquals("Hant", writtenScript("zh_Hant"))
        assertEquals("Kore", writtenScript("ko"))
        assertEquals("Latn", writtenScript(" es-MX "))
        assertNull(writtenScript(""))
        assertNull(writtenScript("und"))
        assertNull(writtenScript("xx"))
    }

    /**
     * Every language the app translates into, against every source with a
     * script of its own that nothing else in the list shares a rule for:
     * unmarked text is tappable exactly where the catalog names another
     * script for the other language than the source's. A language the
     * platform did not know would read here as not tappable.
     */
    // Mutation: as the first test's, over the whole list.
    @Test fun `over every language the app translates into, unmarked text is tappable only beside another script`() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val targets = LanguagePackCatalogLoader.load(ctx).packs
            .filterKeys { it.startsWith("target-") }
            .mapKeys { it.key.removePrefix("target-") }
        assertTrue("the catalog's targets: ${targets.size}", targets.size >= 50)
        val catalogName = mapOf(
            ScriptFamily.CYRILLIC to "CYRILLIC",
            ScriptFamily.ARABIC to "ARABIC",
            ScriptFamily.DEVANAGARI to "DEVANAGARI",
            ScriptFamily.THAI to "THAI",
            ScriptFamily.CJK_KOREAN to "KOREAN",
        )
        val samples = mapOf(
            SourceLangId.RU to ("терять интерес" to "интерес"),
            SourceLangId.AR to ("يفقد الاهتمام" to "الاهتمام"),
            SourceLangId.HI to ("रुचि खोना" to "रुचि"),
            SourceLangId.TH to ("หมดความสนใจ" to "สนใจ"),
            SourceLangId.KO to ("흥미를 잃다" to "흥미"),
        )
        var same = 0
        for ((source, sample) in samples) {
            val own = catalogName.getValue(SourceLanguageProfiles[source].scriptFamily)
            for ((other, entry) in targets) {
                if (other == source.code) continue
                if (entry.script == own) same++
                assertEquals(
                    "${source.code} beside $other (${entry.script})",
                    entry.script != own,
                    gate(source, "", other, sample.first, sample.second),
                )
            }
        }
        // Ukrainian, Belarusian, Bulgarian and Macedonian beside Russian,
        // Persian and Urdu beside Arabic, Marathi beside Hindi.
        assertEquals(7, same)
    }
}
