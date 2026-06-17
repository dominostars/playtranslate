package com.playtranslate.yomitan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [YomitanDictionaryStore.isSameDictionaryIdentity] — the
 * auto-update replacement identity guard. A new revision must still be the same
 * dictionary (same title; any declared languages must agree on primary subtag),
 * else the update URL resolved to a different deck (author misconfiguration) and
 * the swap is skipped. Deliberately NOT an anti-tampering control. Pure JVM.
 */
class YomitanReplacementIdentityTest {

    private fun match(
        newTitle: String, newSource: String?, newTarget: String?,
        installedTitle: String, installedSource: String?, installedTarget: String?,
    ) = YomitanDictionaryStore.isSameDictionaryIdentity(
        newTitle, newSource, newTarget, installedTitle, installedSource, installedTarget,
    )

    @Test fun `same title and languages matches`() {
        assertTrue(match("JMdict", "ja", "en", "JMdict", "ja", "en"))
    }

    @Test fun `title trim and case are ignored`() {
        assertTrue(match("  jmdict ", "ja", "en", "JMdict", "ja", "en"))
    }

    @Test fun `different title is rejected`() {
        assertFalse(match("Spam Deck", "ja", "en", "JMdict", "ja", "en"))
    }

    @Test fun `declared source-language mismatch is rejected`() {
        assertFalse(match("JMdict", "zh", "en", "JMdict", "ja", "en"))
    }

    @Test fun `declared target-language mismatch is rejected`() {
        assertFalse(match("JMdict", "ja", "fr", "JMdict", "ja", "en"))
    }

    @Test fun `null language on either side is tolerated`() {
        // Author added language metadata in a new revision (installed had none),
        // or removed it — not a different dictionary.
        assertTrue(match("JMdict", null, null, "JMdict", "ja", "en"))
        assertTrue(match("JMdict", "ja", "en", "JMdict", null, null))
    }

    @Test fun `primary subtag match ignores region or script suffix`() {
        assertTrue(match("CC-CEDICT", "zh-Hans", "en", "CC-CEDICT", "zh", "en-US"))
    }

    @Test fun `blank language is treated as undeclared`() {
        assertTrue(match("JMdict", "  ", "en", "JMdict", "ja", "en"))
    }
}
