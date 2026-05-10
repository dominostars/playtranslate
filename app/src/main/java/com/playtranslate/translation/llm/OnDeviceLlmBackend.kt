package com.playtranslate.translation.llm

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import com.playtranslate.R
import com.playtranslate.translation.BackendStatus
import com.playtranslate.translation.Tone
import com.playtranslate.translation.TranslationBackend
import com.playtranslate.translation.translategemma.LlamaTranslator
import com.playtranslate.translation.translategemma.PromptStyle

/**
 * Shared base for on-device LLM translation backends (TranslateGemma, Qwen, ...).
 *
 * Concrete subclasses provide configuration: [id], [displayName], [priority],
 * [quality], [modelHelper], [promptStyle], [availMemFloorBytes], [statusStringIds].
 * The shared logic — usability gating, status formatting, dispatch through the
 * [LlamaTranslator] singleton — lives here. Subclasses can override [supportsPair]
 * to whitelist specific language pairs (e.g. TG's en-pivot gate); the default
 * accepts any pair where source != target.
 *
 * `requiresInternet` and `isDegradedFallback` are sealed at this level — every
 * on-device LLM is offline and not a degraded fallback, by definition of the
 * abstraction.
 */
abstract class OnDeviceLlmBackend(
    protected val context: Context,
    protected val enabledProvider: () -> Boolean,
) : TranslationBackend {

    protected abstract val modelHelper: ModelHelper
    protected abstract val promptStyle: PromptStyle

    /** Transient per-call floor checked at translate time inside
     *  [LlamaTranslator]. If `availMem` drops below this value a translate
     *  call throws a transient exception and the registry's waterfall
     *  falls through to the next backend. */
    protected abstract val availMemFloorBytes: Long

    /** Permanent device-level floor: the minimum `MemoryInfo.totalMem` we
     *  require to even consider this backend installable on this device.
     *  Read by [meetsHardwareRequirements] (UI gate) and by
     *  [OnDeviceLlmDownloader] (download-time gate, via Settings). Public so
     *  Settings can wire its downloader without duplicating the constant. */
    abstract val totalMemFloorBytes: Long

    protected abstract val statusStringIds: StatusStringIds

    final override val requiresInternet: Boolean = false
    // false matches the abstraction (users opt into TG/Qwen; they aren't
    // "degraded"). Known side effect: when an on-device LLM produces a
    // translation because an online backend transiently failed, the result
    // gets cached and outlasts the recovery — see the "Note discipline"
    // comment in CaptureService.translateGroupsSeparately. A clean fix
    // would split the cache-suppression and "⚠ Offline" semantics of this
    // flag; not worth the plumbing for the small staleness window today.
    final override val isDegradedFallback: Boolean = false

    final override fun isUsable(source: String, target: String): Boolean {
        // Hardware gate is the cheapest check and a hard prerequisite — a
        // device that can't even host the native library never proceeds. This
        // mirrors the UI's row-disabling logic so the waterfall can never
        // accidentally select an un-runnable backend even if a pref persists
        // across an OS / device change.
        if (!meetsHardwareRequirements()) return false
        if (!enabledProvider()) return false
        if (!modelHelper.isInstalled(context)) return false
        if (source.equals(target, ignoreCase = true)) return false
        return supportsPair(source, target)
    }

    /**
     * True iff this device has the static hardware capabilities required to
     * run this backend at all (arm64 ABI + sufficient total RAM). Consulted
     * by:
     *   - the Settings UI to decide whether the row is interactive,
     *   - [isUsable] as the first gate in the registry waterfall,
     *   - [OnDeviceLlmDownloader.preflightRam] as a defense-in-depth check.
     *
     * Distinct from [isUsable]: this is a static device-level fact (doesn't
     * change while the app runs); [isUsable] is per-translation and depends
     * on prefs, file presence, and the language pair.
     */
    fun meetsHardwareRequirements(): Boolean = supportsRequiredAbi() && hasEnoughTotalMemory()

    /**
     * Localized human-readable explanation for *why* the device doesn't meet
     * the hardware requirements, or null if it does. Surfaced in the row's
     * status line when the switch is hidden.
     */
    fun hardwareIncompatibilityReason(): String? {
        if (!supportsRequiredAbi()) {
            return context.getString(R.string.llm_hardware_unsupported_arm64)
        }
        if (!hasEnoughTotalMemory()) {
            val needGb = (totalMemFloorBytes + 999_999_999L) / 1_000_000_000L
            return context.getString(R.string.llm_hardware_unsupported_ram, needGb)
        }
        return null
    }

    private fun supportsRequiredAbi(): Boolean =
        Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "x86_64" }

    private fun hasEnoughTotalMemory(): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.totalMem >= totalMemFloorBytes
    }

    /**
     * Default: any pair where source != target. Subclasses can override to
     * whitelist specific language pairs (e.g. TG's en-pivot gate during the
     * conservative-default period before per-pair quality is verified).
     */
    protected open fun supportsPair(source: String, target: String): Boolean = true

    override suspend fun translate(text: String, source: String, target: String): String =
        LlamaTranslator.getInstance(context).translate(
            text = text,
            source = source,
            target = target,
            modelPath = modelHelper.file(context).absolutePath,
            promptStyle = promptStyle,
            availMemFloorBytes = availMemFloorBytes,
        )

    override fun close() {
        // The LlamaTranslator singleton outlives any individual backend; closing
        // it from one backend's close() would tear down the engine for the
        // still-active sibling. Engine teardown happens at process death;
        // explicit teardown is via LlamaTranslator.close() if ever needed.
    }

    override val status: BackendStatus
        get() {
            // Surface hardware incompatibility as the line-2 status — takes
            // precedence over download/enabled/ready states because there's
            // no point telling the user "downloaded" if the model can't run.
            hardwareIncompatibilityReason()?.let {
                return BackendStatus.Info(it, Tone.Neutral)
            }
            val sizeStr = modelHelper.humanSize(context)
            // Neutral tone across all states so an enabled on-device LLM doesn't
            // visually outweigh sibling rows (DeepL, Lingva); accent would read
            // as "preferred" which isn't the intent. Memory + disk format is
            // shared between not-downloaded and active states — the toggle
            // and download progress UI carry the state distinction; this line
            // is purely informational about resource cost.
            //
            // We surface availMemFloorBytes (what LlamaTranslator.preflightMemory
            // checks per-translation), not totalMemFloorBytes (the device-class
            // gate). The latter is bigger because it bakes in headroom for the
            // OS and other apps; reading "Requires 6 GB" when the model itself
            // works in ~2.4 GB is confusing. Devices below totalMemFloorBytes
            // never see this string — they get the hardware-incompatibility
            // reason from hardwareIncompatibilityReason() above.
            val memGb = availMemFloorBytes / 1_000_000_000.0
            val memStr = if (memGb == memGb.toLong().toDouble()) "${memGb.toLong()} GB"
                         else "%.1f GB".format(memGb)
            return when {
                !modelHelper.isInstalled(context) ->
                    BackendStatus.Info(
                        context.getString(statusStringIds.notDownloaded, memStr, sizeStr),
                        Tone.Neutral,
                    )
                !enabledProvider() ->
                    BackendStatus.Info(
                        context.getString(statusStringIds.disabled, sizeStr),
                        Tone.Neutral,
                    )
                else ->
                    BackendStatus.Info(
                        context.getString(statusStringIds.ready, memStr, sizeStr),
                        Tone.Neutral,
                    )
            }
        }
}

/**
 * Bundle of `@StringRes` ids for the three states the row's status line can be in.
 * Each string accepts a single `%1$s` size argument formatted via [humanSize].
 */
data class StatusStringIds(
    @StringRes val notDownloaded: Int,
    @StringRes val disabled: Int,
    @StringRes val ready: Int,
)
