package com.playtranslate.region

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * Best-effort device-region detection for license-gated on-device models.
 *
 * The Tencent HY Community License (the model license for the Hunyuan-MT 1.5
 * backend) excludes the European Union, United Kingdom, and South Korea from
 * its "Territory" definition (§1(l), §5(c)). PT ships in those regions via
 * Google Play, so we need a pre-download gate that hides the catalog row from
 * users who appear to be in the restricted region.
 *
 * **Detection strategy** — three permission-free signals checked in parallel:
 *   1. SIM operator country (`TelephonyManager.getSimCountryIso`)
 *   2. Network operator country (`TelephonyManager.getNetworkCountryIso`)
 *   3. System locale country (`Locale.getDefault().getCountry()`)
 *
 * If **any** signal indicates a restricted country, we treat the device as
 * restricted. This is deliberately broad: a US-resident traveler whose SIM
 * is from a German carrier should not be served the catalog row, because
 * Tencent's license restricts the *use* not the user's nationality.
 *
 * **Default-open** — if no signal is available (e.g. wifi-only tablet with
 * no SIM and a "C" locale), the function returns `false` (permitted). The
 * second-layer gate in this case is the click-through legal-attestation
 * dialog the user must agree to before the model downloads. This is the
 * deliberate "Commercial Best Efforts" floor — it matches how Meta Llama 4
 * and Stable Diffusion handle restricted-territory weights and is consistent
 * with the expert legal review in `mnn-spike/HYMT_SPIKE_REPORT.md`.
 *
 * **No permissions required** — both `getSimCountryIso()` and
 * `getNetworkCountryIso()` are available since API 1 without any runtime
 * permission, and `Locale.getDefault()` is permission-free by definition.
 * The manifest is unchanged.
 */
object RegionPolicy {

    /**
     * The 27 EU member states (ISO 3166-1 alpha-2), as of 2026. Hardcoded
     * because (a) the list changes maybe once a decade (last change: Croatia
     * joined 2013-07-01) and (b) shipping a runtime list-of-EU-countries
     * fetch would add a network dependency for a pure metadata check.
     *
     * **Watch for**: future enlargements (current candidate countries:
     * Albania, Bosnia and Herzegovina, Georgia, Moldova, Montenegro,
     * North Macedonia, Serbia, Türkiye, Ukraine). When any of those accede,
     * add their ISO code here and ship a catalog/app update.
     *
     * Note: "PT" here is Portugal's ISO code, **not** the PlayTranslate app
     * abbreviation. Easy source of confusion when grepping; the constant
     * naming below disambiguates downstream.
     */
    private val EU_COUNTRY_CODES: Set<String> = setOf(
        "AT", // Austria
        "BE", // Belgium
        "BG", // Bulgaria
        "HR", // Croatia
        "CY", // Cyprus
        "CZ", // Czech Republic
        "DK", // Denmark
        "EE", // Estonia
        "FI", // Finland
        "FR", // France
        "DE", // Germany
        "GR", // Greece (ISO is GR; not "EL" — that's the EU-internal code)
        "HU", // Hungary
        "IE", // Ireland
        "IT", // Italy
        "LV", // Latvia
        "LT", // Lithuania
        "LU", // Luxembourg
        "MT", // Malta
        "NL", // Netherlands
        "PL", // Poland
        "PT", // Portugal (NB: also matches an unfortunate grep for "PT" the app)
        "RO", // Romania
        "SK", // Slovakia
        "SI", // Slovenia
        "ES", // Spain
        "SE", // Sweden
    )

    /**
     * Countries excluded from the Tencent HY Community License "Territory":
     * the 27 EU member states plus United Kingdom (GB) and South Korea (KR).
     */
    private val HUNYUAN_RESTRICTED: Set<String> = EU_COUNTRY_CODES + setOf("GB", "KR")

    /**
     * `true` iff any signal indicates the device is in a Tencent
     * HY-Community-License-restricted region. Falls open (`false`) when no
     * signal is available.
     */
    fun isHunyuanRestricted(context: Context): Boolean =
        isHunyuanRestricted(collectCountrySignals(context))

    /**
     * Pure overload — given an explicit list of country-code signals,
     * returns `true` iff any of them is in the restricted set. Exposed
     * `internal` so unit tests can exercise the policy without standing
     * up an Android Context.
     *
     * Signals are matched case-insensitively against the upper-case set;
     * empty / blank entries are ignored.
     */
    internal fun isHunyuanRestricted(signals: List<String>): Boolean =
        signals.any { it.isNotBlank() && it.uppercase(Locale.ROOT) in HUNYUAN_RESTRICTED }

    /**
     * The country signals available for this device, normalized to uppercase
     * ISO 3166-1 alpha-2 codes. Empty list iff the device exposes no signal
     * (extremely rare — even a freshly-factory-reset device exposes a
     * Locale country in practice).
     *
     * Exposed `internal` so unit tests can swap fake signals in directly,
     * avoiding the need to mock TelephonyManager / Locale globally.
     */
    internal fun collectCountrySignals(context: Context): List<String> {
        val out = mutableListOf<String>()
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (tm != null) {
            // getSimCountryIso(): ISO country code of the SIM provider, lowercase.
            // Returns empty string (not null) when SIM state is absent.
            tm.simCountryIso?.takeIf { it.isNotBlank() }?.let { out += it.uppercase(Locale.ROOT) }
            // getNetworkCountryIso(): ISO country of the network operator the
            // device is currently registered with. Lowercase, empty if not
            // attached (e.g. airplane mode, no SIM).
            tm.networkCountryIso?.takeIf { it.isNotBlank() }?.let { out += it.uppercase(Locale.ROOT) }
        }
        Locale.getDefault().country.takeIf { it.isNotBlank() }?.let {
            out += it.uppercase(Locale.ROOT)
        }
        return out
    }
}
