package com.playtranslate.region

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [RegionPolicy.isHunyuanRestricted]'s pure-signal overload.
 * The Context-based overload's only added behavior is signal collection from
 * TelephonyManager + Locale; the policy logic (which country codes count as
 * restricted, multi-signal OR-fold, default-open on no signals) is fully
 * exercised here.
 *
 * The Tencent HY Community License (the model license for Hunyuan-MT 1.5)
 * defines "Territory" as worldwide excluding the European Union, United
 * Kingdom, and South Korea (§1(l)). These tests confirm the policy
 * implements that definition correctly.
 */
class RegionPolicyTest {

    // ── All-restricted-signal cases ───────────────────────────────────────

    @Test
    fun `germany on all three signals returns restricted`() {
        assertTrue(RegionPolicy.isHunyuanRestricted(listOf("DE", "DE", "DE")))
    }

    @Test
    fun `france returns restricted`() {
        assertTrue(RegionPolicy.isHunyuanRestricted(listOf("FR")))
    }

    @Test
    fun `italy returns restricted`() {
        assertTrue(RegionPolicy.isHunyuanRestricted(listOf("IT")))
    }

    @Test
    fun `poland returns restricted`() {
        assertTrue(RegionPolicy.isHunyuanRestricted(listOf("PL")))
    }

    @Test
    fun `portugal returns restricted - PT is ISO Portugal not the app`() {
        // Explicit guard: the ISO code "PT" matches Portugal and must be
        // treated as EU-restricted. Easy to confuse with the PlayTranslate
        // app's own "PT" abbreviation; the EU_COUNTRY_CODES set decides.
        assertTrue(RegionPolicy.isHunyuanRestricted(listOf("PT")))
    }

    @Test
    fun `croatia returns restricted - most-recent EU joiner`() {
        // Croatia joined 2013-07-01; serves as the regression case for the
        // "we remembered to add new EU members" check.
        assertTrue(RegionPolicy.isHunyuanRestricted(listOf("HR")))
    }

    @Test
    fun `united kingdom returns restricted`() {
        // UK is restricted under the Tencent HY Community License separately
        // from the EU set (the UK left the EU in 2020 but remains on
        // Tencent's exclusion list).
        assertTrue(RegionPolicy.isHunyuanRestricted(listOf("GB")))
    }

    @Test
    fun `south korea returns restricted`() {
        // KR is restricted under the Tencent HY Community License separately
        // from the EU set.
        assertTrue(RegionPolicy.isHunyuanRestricted(listOf("KR")))
    }

    // ── Permitted-signal cases ────────────────────────────────────────────

    @Test
    fun `united states returns permitted`() {
        assertFalse(RegionPolicy.isHunyuanRestricted(listOf("US")))
    }

    @Test
    fun `japan returns permitted`() {
        // Japan is NOT in the restricted set; the model card supports JA→EN
        // translation and JA users are explicitly the target audience.
        assertFalse(RegionPolicy.isHunyuanRestricted(listOf("JP")))
    }

    @Test
    fun `canada returns permitted`() {
        assertFalse(RegionPolicy.isHunyuanRestricted(listOf("CA")))
    }

    @Test
    fun `switzerland returns permitted - not EU member`() {
        // Switzerland is geographically in Europe but not an EU member; the
        // license's exclusion is EU-as-a-political-bloc + UK + KR, not
        // "any European country."
        assertFalse(RegionPolicy.isHunyuanRestricted(listOf("CH")))
    }

    @Test
    fun `norway returns permitted - not EU member`() {
        assertFalse(RegionPolicy.isHunyuanRestricted(listOf("NO")))
    }

    @Test
    fun `north korea returns permitted - license excludes south korea not north`() {
        // KP (DPRK) is NOT in the restricted set; only KR (Republic of
        // Korea) is. Edge case but worth pinning so a future refactor
        // doesn't widen to "any Korea".
        assertFalse(RegionPolicy.isHunyuanRestricted(listOf("KP")))
    }

    // ── Multi-signal OR-fold ──────────────────────────────────────────────

    @Test
    fun `any restricted signal wins over permitted ones`() {
        // US locale + DE SIM (traveler whose phone is set up in US but
        // currently has a German carrier SIM) → restricted. Matches the
        // policy: license is geographic, not nationality / locale-of-origin.
        assertTrue(RegionPolicy.isHunyuanRestricted(listOf("US", "DE", "US")))
    }

    @Test
    fun `all permitted signals returns permitted`() {
        // Locale en_US, SIM US, network US — typical US user.
        assertFalse(RegionPolicy.isHunyuanRestricted(listOf("US", "US", "US")))
    }

    @Test
    fun `case-insensitive matching`() {
        // TelephonyManager returns lowercase ISO codes per Android docs;
        // policy normalizes to upper before comparing.
        assertTrue(RegionPolicy.isHunyuanRestricted(listOf("de")))
        assertTrue(RegionPolicy.isHunyuanRestricted(listOf("gb", "us")))
    }

    // ── Default-open edge cases ───────────────────────────────────────────

    @Test
    fun `empty signal list returns permitted - default-open`() {
        // Wifi-only tablet with no SIM and a "C" locale — no signals
        // available. Policy falls open; the click-through legal-attestation
        // dialog is the second-line gate in this case.
        assertFalse(RegionPolicy.isHunyuanRestricted(emptyList<String>()))
    }

    @Test
    fun `all blank signals returns permitted`() {
        // TelephonyManager returns empty strings (not null) when SIM state
        // is absent; policy treats blank entries as no-signal.
        assertFalse(RegionPolicy.isHunyuanRestricted(listOf("", "", "")))
    }

    @Test
    fun `blanks mixed with restricted still returns restricted`() {
        // No-SIM device with German locale (system Locale country = "DE",
        // TelephonyManager returns blanks).
        assertTrue(RegionPolicy.isHunyuanRestricted(listOf("", "", "DE")))
    }

    @Test
    fun `unknown country code returns permitted`() {
        // An ISO code not in the restricted set — neither the 27 EU members,
        // GB, nor KR. Returns permitted.
        assertFalse(RegionPolicy.isHunyuanRestricted(listOf("XX")))
    }
}
