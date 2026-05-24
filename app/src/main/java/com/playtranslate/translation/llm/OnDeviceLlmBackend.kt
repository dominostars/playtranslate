package com.playtranslate.translation.llm

import android.app.ActivityManager
import android.content.Context
import androidx.annotation.StringRes
import com.playtranslate.R
import com.playtranslate.translation.BackendStatus
import com.playtranslate.translation.Tone
import com.playtranslate.translation.TranslationBackend
import com.playtranslate.translation.mnn.MnnTranslator

/**
 * Shared base for on-device LLM translation backends. Concrete subclasses
 * provide configuration: [id], [displayName], [priority], [quality],
 * [modelHelper], [promptStyle], [availMemFloorBytes], [statusStringIds].
 * The shared logic — usability gating, status formatting, dispatch through
 * the [MnnTranslator] singleton — lives here.
 *
 * Subclasses can override [supportsPair] to whitelist specific language pairs;
 * the default accepts any pair where source != target. They can also override
 * [translate] if they need engine-specific dispatch, but the default
 * [MnnTranslator] route covers every on-device backend after the :llama strip.
 *
 * `requiresInternet` and `isDegradedFallback` are sealed at this level —
 * every on-device LLM is offline and not a degraded fallback, by definition.
 */
abstract class OnDeviceLlmBackend(
    protected val context: Context,
    protected val enabledProvider: () -> Boolean,
) : TranslationBackend {

    protected abstract val modelHelper: ModelHelper
    protected abstract val promptStyle: PromptStyle

    /** Transient per-call floor checked at translate time inside
     *  [MnnTranslator]. If `availMem` drops below this value a translate
     *  call throws a transient exception and the registry's waterfall
     *  falls through to the next backend. Public so Settings can read it
     *  for the pre-toggle availMem gate (mirrors [totalMemFloorBytes]). */
    abstract val availMemFloorBytes: Long

    /** Permanent device-level floor: the minimum `MemoryInfo.totalMem` we
     *  require to even consider this backend installable on this device.
     *  Read by [meetsHardwareRequirements] (UI gate) and by
     *  [OnDeviceLlmDownloader] (download-time gate, via Settings). Public so
     *  Settings can wire its downloader without duplicating the constant. */
    abstract val totalMemFloorBytes: Long

    protected abstract val statusStringIds: StatusStringIds

    final override val requiresInternet: Boolean = false
    // false matches the abstraction (users opt into the on-device tier; they
    // aren't "degraded"). When an on-device LLM produces a translation because
    // an online backend transiently failed, the result gets cached and
    // outlasts the recovery — narrow staleness window we accept. The
    // inverse case (LLM displaced by transient memory, fallback returns a
    // result) is handled separately via WaterfallResult.displacedLlmId →
    // CaptureService.translateGroupsSeparately cache-skip.
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

    /**
     * True iff this process is running 64-bit (and therefore can load MNN).
     *
     * `:mnn` builds for arm64-v8a only, so on a 32-bit-only device the
     * abiFilters policy in `:mnn/build.gradle.kts` keeps every MNN `.so` out
     * of the APK's 32-bit slice. The OS picks `armeabi-v7a` for that device,
     * the process runs as 32-bit, and `System.loadLibrary("mnn-chat")` would
     * fail. [android.os.Process.is64Bit] directly answers "can this process
     * load 64-bit `.so` files" — true on arm64-v8a / x86_64 hosts, false on
     * armeabi-v7a / x86. We deliberately do not use `Build.SUPPORTED_ABIS`
     * here: that's a device-static list, so on a hypothetical 64-bit device
     * running our 32-bit slice (a wrong App-Bundle split delivery) it would
     * incorrectly report compatibility.
     *
     * `nativeLibraryDir` is not used either: with `extractNativeLibs=false`
     * (modern AGP default) the `.so` files live inside the APK and are
     * mmap'd by the dynamic linker, so the directory on disk is empty.
     */
    private fun supportsRequiredAbi(): Boolean = android.os.Process.is64Bit()

    private fun hasEnoughTotalMemory(): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.totalMem >= totalMemFloorBytes
    }

    /** Read live [ActivityManager.MemoryInfo.availMem]. The [status] getter
     *  uses this to decide whether to show the "Low memory" badge — there
     *  is no stored "we got displaced recently" flag, so the badge
     *  reflects current conditions, not history. Cheap (microseconds)
     *  and called only when the Settings row refreshes. */
    private fun hasEnoughAvailMemory(): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.availMem >= availMemFloorBytes
    }

    /**
     * Default: any pair where source != target. Subclasses can override to
     * whitelist specific language pairs (e.g. a JA→EN-only specialist model
     * during conservative-defaults rollouts).
     */
    protected open fun supportsPair(source: String, target: String): Boolean = true

    /**
     * Dispatch through the [MnnTranslator] singleton. Open so an
     * engine-specific subclass could re-route, but every on-device backend
     * after the :llama strip uses MNN — the override is unnecessary in
     * practice.
     */
    override suspend fun translate(text: String, source: String, target: String): String =
        MnnTranslator.getInstance(context).translate(
            text = text,
            source = source,
            target = target,
            modelPath = modelHelper.file(context).absolutePath,
            promptStyle = promptStyle,
            availMemFloorBytes = availMemFloorBytes,
        )

    override fun close() {
        // The MnnTranslator singleton outlives any individual backend; closing
        // it from one backend's close() would tear down the engine for the
        // still-active sibling. Engine teardown happens at process death;
        // explicit teardown is via MnnTranslator.close() if ever needed.
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
            // availMemFloorBytes is what MnnTranslator.preflightMemory checks
            // per-translation. totalMemFloorBytes is the device-class gate;
            // it bakes in headroom for the OS and other apps, so the user-facing
            // status line shows the per-call number (which is what they'd see
            // if a translation transiently fails). Devices below
            // totalMemFloorBytes never see this string — they get the
            // hardware-incompatibility reason from hardwareIncompatibilityReason().
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
                !hasEnoughAvailMemory() ->
                    // Live availMem is below the per-call floor right now,
                    // so a translate attempt would throw transient and fall
                    // through. No stored "we got displaced" flag — the
                    // badge reflects current conditions, so it self-clears
                    // the next time the row is refreshed after memory
                    // recovers.
                    BackendStatus.Info(
                        context.getString(R.string.llm_status_low_memory_badge),
                        Tone.Warning,
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
