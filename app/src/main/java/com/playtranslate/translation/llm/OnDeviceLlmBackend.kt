package com.playtranslate.translation.llm

import android.content.Context
import androidx.annotation.StringRes
import com.playtranslate.translation.BackendId
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
    protected abstract val availMemFloorBytes: Long
    protected abstract val statusStringIds: StatusStringIds

    final override val requiresInternet: Boolean = false
    final override val isDegradedFallback: Boolean = false

    final override fun isUsable(source: String, target: String): Boolean {
        if (!enabledProvider()) return false
        if (!modelHelper.isInstalled(context)) return false
        if (source.equals(target, ignoreCase = true)) return false
        return supportsPair(source, target)
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
            val sizeStr = modelHelper.humanSize(context)
            return when {
                !modelHelper.isInstalled(context) ->
                    BackendStatus.Info(
                        context.getString(statusStringIds.notDownloaded, sizeStr),
                        Tone.Neutral,
                    )
                !enabledProvider() ->
                    BackendStatus.Info(
                        context.getString(statusStringIds.disabled, sizeStr),
                        Tone.Neutral,
                    )
                else ->
                    BackendStatus.Info(
                        context.getString(statusStringIds.ready, sizeStr),
                        Tone.Accent,
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
