package com.playtranslate.translation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * How much a model reasons before it answers: the user's last pick on a
 * service's config page, stored on [OnlineServiceInstance.reasoning]. What a
 * pick means on the wire depends on the service and its model, so nothing
 * reads it raw: every reader asks [ReasoningControls] for the instance's
 * [ReasoningControl] and resolves the pick through it.
 *
 * [UNSET] is the stored default: nothing picked yet, which reads as the
 * control's start state. It is never offered. [OFF] and [ON] are a switch's
 * two states; [OFF] is also the first level where an API names an off.
 * [MINIMAL], [LOW], [MEDIUM] and [HIGH] are levels as the APIs name them.
 * Serialized by name; a name this build doesn't know decodes as [UNSET]
 * (PtJson.lenient coerces it).
 */
@Serializable
enum class ReasoningLevel { UNSET, OFF, ON, MINIMAL, LOW, MEDIUM, HIGH }

/**
 * What a provider's own model catalog said about one model's thinking, as
 * received ([ThinkingCatalog]): the provider's subtree for it, `{"effort": …}`
 * from DeepSeek, `{"reasoning": …}` from OpenRouter, `{"thinking": …}` from
 * Claude, or `{}` where the catalog lists the model without one. Kept raw so
 * [ReasoningControls] reads it under its current rules. Bound to [model]: it
 * applies only while that is the instance's model, so a model change makes it
 * inert until a refresh replaces it, and switching back revives it.
 * [fetchedAtMs] paces the daily refresh ([ReportedThinkingRefresher]).
 */
@Serializable
data class ReportedThinking(val model: String, val facts: JsonObject, val fetchedAtMs: Long)

/**
 * What one service, on one model, lets a request choose about reasoning, and
 * the request fields each choice sends.
 *
 * [choices] is in display order. Empty means the config page shows no row and
 * every stored pick sends the same request; exactly [ReasoningLevel.OFF],
 * [ReasoningLevel.ON] is a switch; anything else is a segmented control. A
 * stored pick the control doesn't offer (one made under another provider, or
 * before a model change in the picker), and [ReasoningLevel.UNSET], read as
 * [fallback], the state a new service starts in. The page shows what
 * [resolve] returns and the request sends what [fields] returns, so the two
 * can't disagree.
 */
class ReasoningControl internal constructor(
    val choices: List<ReasoningLevel>,
    private val fallback: ReasoningLevel,
    private val fieldsFor: (ReasoningLevel) -> Map<String, JsonElement>,
) {
    /** Off and on only: the page draws a switch. */
    val isSwitch: Boolean get() = choices == SWITCH

    /** What [stored] means here: itself where offered, else [fallback]. */
    fun resolve(stored: ReasoningLevel): ReasoningLevel =
        if (stored in choices) stored else fallback

    /** The request fields [stored] sends here. */
    fun fields(stored: ReasoningLevel): Map<String, JsonElement> = fieldsFor(resolve(stored))

    internal companion object {
        val SWITCH = listOf(ReasoningLevel.OFF, ReasoningLevel.ON)
    }
}

/**
 * What each service lets a request choose about reasoning, model by model.
 * The config page draws a service's row from it, and the OpenAI-compatible
 * extras ([OnlineBackendFactory.requestExtrasFor]) and Gemini's
 * `generationConfig` both send its fields, so a row can't offer what the
 * request doesn't send. Exhaustive over type and preset, so a new provider has
 * to decide.
 *
 * Within one provider a field is taken by some models and rejected with a 400
 * by others, and a 400 fails every request on that service (the batch path's
 * per-text retry sends the same field). So no row offers a choice the model
 * hasn't been shown to take:
 *
 * - DeepSeek, OpenRouter and Claude publish each model's thinking options in
 *   their model catalogs. Those rows come from what the provider reported
 *   ([ReportedThinking]), so a provider change reaches the row at the next
 *   refresh without an app update. Until a report arrives the page shows no
 *   row and the request sends what it did before the row existed.
 * - OpenAI, Groq, Mistral and Gemini publish nothing usable (Gemini says only
 *   whether a model thinks). Those rows come from their docs, by exact model
 *   id: an id not listed gets no row and sends nothing, so a new or changed
 *   model can make a service slower but never breaks it.
 *
 * One policy over both sources (2026-10-26): every choice is one the
 * API names, up to High (no XHigh or Max, which add only latency and cost to
 * short game text); no "Default" that sends nothing; Off where the model can
 * stop thinking, and then Minimal is left out so a row never has more than
 * four segments; a row starts at its lowest choice.
 *
 * Sources for the tables, read 2026-10-26:
 * - OpenAI: `reasoning_effort` on Chat Completions; reasoning models take
 *   low, medium and high, and some reject `none` (GPT-6 Astra, GPT-6.1 Sol);
 *   chat-latest is ChatGPT's Instant model, with no reasoning on its model
 *   page; pro models take only `high` (developers.openai.com: the models
 *   page, the reasoning guide, the Chat Completions reference).
 * - Groq: "qwen3 models support `none` ... and `default`", and only
 *   qwen/qwen3.8-27b "additionally supports low, medium and high"; gpt-oss
 *   takes low, medium and high, Muse low to xhigh; "Values outside a model's
 *   supported set are rejected with a 400" (console.groq.com/docs: the
 *   reasoning page and the API reference).
 * - Gemini: `thinkingConfig.thinkingLevel` on Gemini 3 and 4, which take low,
 *   medium and high, and minimal on 3.5 and 3.1 Flash-Lite, 3.6 Flash and 3
 *   Flash (an error on 3.8 Flash, 3.1 Pro and 4 Argon); `thinkingBudget` on
 *   2.5, where 0 is off on Flash and Flash-Lite and -1 is dynamic
 *   (ai.google.dev/gemini-api/docs/generate-content/thinking). The rolling
 *   aliases are read as the models behind them today: gemini-flash-lite-latest
 *   as 3.5 Flash-Lite, gemini-flash-latest as 3.8 Flash, gemini-pro-latest as
 *   3.1 Pro.
 * - Mistral: `reasoning_effort` "none" or "high" on the models its reasoning
 *   page names; with "high" the reply's content is a list of chunks
 *   (docs.mistral.ai/capabilities/reasoning). mistral-small-2603 is the dated
 *   id of Mistral Small 4, which mistral-small-latest names.
 * - The reported rows read: DeepSeek's `effort.supported_levels` (thinking is
 *   switched by `thinking: {type}`); OpenRouter's `reasoning`
 *   (`supported_efforts`, `mandatory`); Claude's `capabilities.thinking.types`,
 *   whose `supported` is "false exactly when a request that sends it gets a
 *   400" (api-docs.deepseek.com/api/list-models, openrouter.ai/docs's
 *   reasoning-tokens guide, platform.claude.com/docs/en/api/models/list).
 *   Claude's thinking passes Anthropic's OpenAI-compatible layer;
 *   `reasoning_effort` doesn't.
 * - Index-Translate: Qwen 3.5 hybrid thinkers, switched off through the chat
 *   template exactly as Bilibili's own client does; Bilibili's guide says
 *   thinking must be forced off for translation or the output fails to parse
 *   (github.com/bilibili/Index-Translate), so it gets no row.
 */
object ReasoningControls {

    /** [reported] counts only while it is about [model]. */
    fun of(
        type: ServiceType,
        preset: OpenAiPreset,
        model: String,
        reported: ReportedThinking? = null,
    ): ReasoningControl {
        val facts = reported?.takeIf { it.model == model }?.facts
        return when (type) {
            ServiceType.GEMINI -> gemini(model)
            ServiceType.OPENAI -> when (preset) {
                OpenAiPreset.OPENAI ->
                    if (OPENAI_REASONING_MODELS.any { matchesId(model, it) }) OPENAI_LEVELS else NONE
                OpenAiPreset.DEEPSEEK -> facts?.let(::deepSeekReported) ?: DEEPSEEK_UNREPORTED
                OpenAiPreset.MISTRAL -> if (model in MISTRAL_REASONING_MODELS) MISTRAL else NONE
                OpenAiPreset.GROQ -> groq(model)
                OpenAiPreset.OPENROUTER -> facts?.let(::openRouterReported) ?: NONE
                OpenAiPreset.CLAUDE -> facts?.let(::claudeReported) ?: claudeUnreported(model)
                OpenAiPreset.INDEX_TRANSLATE -> INDEX_TRANSLATE
                // A user-typed endpoint speaks a dialect we can't know.
                OpenAiPreset.CUSTOM -> NONE
            }
            // Not language models; their pages are not this one.
            ServiceType.DEEPL, ServiceType.LINGVA -> NONE
        }
    }

    /** [id] itself or a dated snapshot of it (`gpt-5-2025-08-07`,
     *  `claude-haiku-4-5-20251001`), and never a longer id that starts with
     *  it: `claude-sonnet-5-5` is not Sonnet 5. */
    private fun matchesId(model: String, id: String): Boolean =
        model == id || model.startsWith("$id-20")

    // ── OpenAI ───────────────────────────────────────────────────────────

    /** The documented reasoning models; each takes low, medium and high. */
    private val OPENAI_REASONING_MODELS = listOf(
        "gpt-6-luna", "gpt-6-sol", "gpt-6.1-sol", "gpt-6.1-terra", "gpt-6-astra", "gpt-5.5",
        "gpt-5", "gpt-5-mini", "gpt-5-nano", "o3", "o3-mini", "o4-mini", "o1",
    )

    private val OPENAI_LEVELS =
        levels(ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH) { reasoningEffort(it) }

    // ── Groq ─────────────────────────────────────────────────────────────

    /** gpt-oss and Muse can't stop reasoning; Qwen 3.8 can. Groq's other
     *  Qwen 3 models take only `none` and `default`. */
    private fun groq(model: String): ReasoningControl = when (model) {
        "qwen/qwen3.8-27b" -> GROQ_QWEN_LEVELS
        "openai/gpt-oss-20b", "openai/gpt-oss-120b", "meta-models/muse-spark-open" -> GROQ_LEVELS
        else -> NONE
    }

    private val GROQ_LEVELS =
        levels(ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH) { reasoningEffort(it) }

    private val GROQ_QWEN_LEVELS = levels(
        ReasoningLevel.OFF, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH,
    ) { reasoningEffort(it) }

    // ── Gemini ───────────────────────────────────────────────────────────

    /** Fields for Gemini's `generationConfig`, not the body's top level. */
    private fun gemini(model: String): ReasoningControl = when (model) {
        in GEMINI_MINIMAL_MODELS -> GEMINI_MINIMAL_LEVELS
        in GEMINI_LOW_MODELS -> GEMINI_LEVELS
        "gemini-2.5-flash", "gemini-2.5-flash-lite" -> GEMINI_BUDGET_SWITCH
        else -> NONE
    }

    /** The models that name a minimal level, and the alias behind one. */
    private val GEMINI_MINIMAL_MODELS = setOf(
        "gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-3.6-flash", "gemini-3-flash-preview",
        "gemini-flash-lite-latest",
    )

    /** The models whose lowest level is low, and the aliases behind them. */
    private val GEMINI_LOW_MODELS = setOf(
        "gemini-3.8-flash", "gemini-3.1-pro-preview", "gemini-4-argon", "gemini-flash-latest", "gemini-pro-latest",
    )

    private val GEMINI_MINIMAL_LEVELS = levels(
        ReasoningLevel.MINIMAL, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH,
    ) { thinkingLevel(it) }

    private val GEMINI_LEVELS =
        levels(ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH) { thinkingLevel(it) }

    private fun thinkingLevel(level: String): Map<String, JsonElement> =
        mapOf("thinkingConfig" to buildJsonObject { put("thinkingLevel", level) })

    /** 2.5 Flash and Flash-Lite: off is a zero budget, on is dynamic. */
    private val GEMINI_BUDGET_SWITCH = switch(
        off = mapOf("thinkingConfig" to buildJsonObject { put("thinkingBudget", 0) }),
        on = mapOf("thinkingConfig" to buildJsonObject { put("thinkingBudget", -1) }),
    )

    // ── Mistral ──────────────────────────────────────────────────────────

    private val MISTRAL_REASONING_MODELS = setOf(
        "mistral-small-2603", "mistral-small-latest", "mistral-medium-3-5", "mistral-large-4-0",
    )

    private val MISTRAL = switch(
        off = buildJsonObject { put("reasoning_effort", "none") },
        on = buildJsonObject { put("reasoning_effort", "high") },
    )

    // ── DeepSeek, reported ───────────────────────────────────────────────

    /** Every request carries an off or a level, so DeepSeek's thinking
     *  default never decides (issue #41: every translation ran in thinking
     *  mode). Off sends the switch alone; the docs don't say what a disabled
     *  switch does beside an effort. */
    private val DEEPSEEK_OFF = mapOf("thinking" to thinkingType("disabled"))

    /** No report yet, or none for this model: no row, thinking off. */
    private val DEEPSEEK_UNREPORTED = fixed(DEEPSEEK_OFF)

    /** `effort.supported_levels` are the levels with thinking on; off is
     *  always `thinking: disabled`. A model reported without `effort` keeps
     *  thinking off with no row. */
    private fun deepSeekReported(facts: JsonObject): ReasoningControl {
        val effort = facts["effort"] as? JsonObject ?: return DEEPSEEK_UNREPORTED
        return reported(
            off = DEEPSEEK_OFF,
            levels = namedLevels(effort["supported_levels"]),
            level = { level ->
                buildJsonObject {
                    put("thinking", thinkingType("enabled"))
                    put("reasoning_effort", apiName(level))
                }
            },
            on = mapOf("thinking" to thinkingType("enabled")),
            noRow = DEEPSEEK_OFF,
        )
    }

    // ── OpenRouter, reported ─────────────────────────────────────────────

    /** A model reported without `reasoning` doesn't reason: no row, nothing
     *  sent. Off unless `mandatory`, whose models reject `none`.
     *  `supported_efforts` null means every level; absent means the model
     *  takes no effort, only `enabled`. */
    private fun openRouterReported(facts: JsonObject): ReasoningControl {
        val reasoning = facts["reasoning"] as? JsonObject ?: return NONE
        val mandatory = (reasoning["mandatory"] as? JsonPrimitive)?.booleanOrNull == true
        val efforts = reasoning["supported_efforts"]
        val exposed = "supported_efforts" in reasoning
        val levels = when {
            !exposed -> emptyList()
            efforts is JsonNull -> listOf(ReasoningLevel.MINIMAL, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH)
            else -> namedLevels(efforts)
        }
        return reported(
            off = when {
                mandatory -> null
                exposed -> openRouterEffort("none")
                else -> openRouterEnabled(false)
            },
            levels = levels,
            level = { openRouterEffort(apiName(it)) },
            on = if (mandatory) null else openRouterEnabled(true),
            noRow = emptyMap(),
        )
    }

    private fun openRouterEffort(effort: String): Map<String, JsonElement> =
        mapOf("reasoning" to buildJsonObject { put("effort", effort) })

    private fun openRouterEnabled(enabled: Boolean): Map<String, JsonElement> =
        mapOf("reasoning" to buildJsonObject { put("enabled", enabled) })

    // ── Claude, reported ─────────────────────────────────────────────────

    /** Off through `disabled`, else `between_tools`; on through `adaptive`,
     *  else a token budget below `max_tokens`. Both make a switch; a model
     *  that can't stop thinking, or can't think, gets no row and nothing
     *  sent. Null where the report doesn't describe thinking, which leaves
     *  the unreported request in place. */
    private fun claudeReported(facts: JsonObject): ReasoningControl? {
        val thinking = facts["thinking"] as? JsonObject ?: return null
        if (!supported(thinking)) return NONE
        val types = thinking["types"] as? JsonObject ?: return null
        val off = when {
            supported(types["disabled"]) -> CLAUDE_OFF
            supported(types["between_tools"]) -> CLAUDE_OFF_BETWEEN_TOOLS
            else -> null
        }
        val on = when {
            supported(types["adaptive"]) -> CLAUDE_ON_ADAPTIVE
            supported(types["enabled"]) -> CLAUDE_ON_BUDGET
            else -> null
        }
        return if (off != null && on != null) switch(off, on) else NONE
    }

    /** No report yet: no row, and the off each known model took before the
     *  row existed. Opus 5.5, Fable and Mythos think always and answer an
     *  explicit off with a 400; an id not listed is sent nothing, since the
     *  newest models reject `disabled` and slow beats broken. */
    private fun claudeUnreported(model: String): ReasoningControl = when {
        CLAUDE_OFF_BETWEEN_TOOLS_MODELS.any { matchesId(model, it) } -> fixed(CLAUDE_OFF_BETWEEN_TOOLS)
        CLAUDE_OFF_DISABLED_MODELS.any { matchesId(model, it) } -> fixed(CLAUDE_OFF)
        else -> NONE
    }

    private val CLAUDE_OFF_BETWEEN_TOOLS_MODELS = listOf("claude-sonnet-5-5")
    private val CLAUDE_OFF_DISABLED_MODELS = listOf(
        "claude-sonnet-5", "claude-opus-5", "claude-haiku-5-5",
        "claude-opus-4-8", "claude-opus-4-7", "claude-opus-4-6", "claude-sonnet-4-6",
        "claude-haiku-4-5", "claude-sonnet-4-5", "claude-opus-4-5", "claude-opus-4-1",
    )

    private val CLAUDE_OFF = mapOf("thinking" to thinkingType("disabled"))
    private val CLAUDE_OFF_BETWEEN_TOOLS = mapOf("thinking" to thinkingType("between_tools"))
    private val CLAUDE_ON_ADAPTIVE = mapOf("thinking" to thinkingType("adaptive"))

    /** The smallest budget the API takes, the fastest thinking there is,
     *  with room for a full screen's batch answer above it. The app sends
     *  no `max_tokens` otherwise. */
    private val CLAUDE_ON_BUDGET: Map<String, JsonElement> = buildJsonObject {
        put("thinking", buildJsonObject {
            put("type", "enabled")
            put("budget_tokens", 1024)
        })
        put("max_tokens", 8192)
    }

    private fun supported(element: JsonElement?): Boolean =
        ((element as? JsonObject)?.get("supported") as? JsonPrimitive)?.booleanOrNull == true

    // ── Index-Translate ──────────────────────────────────────────────────

    private val INDEX_TRANSLATE =
        fixed(mapOf("chat_template_kwargs" to buildJsonObject { put("enable_thinking", false) }))

    // ── Shapes ───────────────────────────────────────────────────────────

    /** No row, nothing sent. */
    private val NONE = fixed(emptyMap())

    /** No row, always [fields]. */
    private fun fixed(fields: Map<String, JsonElement>) =
        ReasoningControl(emptyList(), ReasoningLevel.UNSET) { fields }

    /** Levels in [choices] order, lowest first, starting at the lowest;
     *  [wire] gives a level's fields from its API name. */
    private fun levels(vararg choices: ReasoningLevel, wire: (String) -> Map<String, JsonElement>) =
        ReasoningControl(choices.toList(), choices.first()) { level -> wire(apiName(level)) }

    /** Off and on, starting off. */
    private fun switch(
        off: Map<String, JsonElement>,
        on: Map<String, JsonElement>,
    ) = ReasoningControl(ReasoningControl.SWITCH, ReasoningLevel.OFF) { if (it == ReasoningLevel.ON) on else off }

    /**
     * A row from a provider's report, under the one policy: Off when [off]
     * is given, then [levels] lowest first, with Minimal left out beside Off
     * so a row never has more than four segments, starting at the first.
     * With Off and no levels, a switch to [on]; with fewer than two choices
     * otherwise, no row, sending [noRow].
     */
    private fun reported(
        off: Map<String, JsonElement>?,
        levels: List<ReasoningLevel>,
        level: (ReasoningLevel) -> Map<String, JsonElement>,
        on: Map<String, JsonElement>?,
        noRow: Map<String, JsonElement>,
    ): ReasoningControl {
        val named = if (off != null) levels - ReasoningLevel.MINIMAL else levels
        return when {
            off != null && named.isNotEmpty() -> ReasoningControl(listOf(ReasoningLevel.OFF) + named, ReasoningLevel.OFF) {
                if (it == ReasoningLevel.OFF) off else level(it)
            }
            off == null && named.size >= 2 -> ReasoningControl(named, named.first()) { level(it) }
            off != null && on != null -> switch(off, on)
            else -> fixed(noRow)
        }
    }

    /** The levels in a reported list that this app offers, lowest first:
     *  `none`, `xhigh`, `max` and names it doesn't know are left out. */
    private fun namedLevels(list: JsonElement?): List<ReasoningLevel> =
        (list as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(LEVEL_NAMES::get) }
            .distinct()
            .sorted()

    private val LEVEL_NAMES = mapOf(
        "minimal" to ReasoningLevel.MINIMAL,
        "low" to ReasoningLevel.LOW,
        "medium" to ReasoningLevel.MEDIUM,
        "high" to ReasoningLevel.HIGH,
    )

    /** A level's name in every API that takes it by name. */
    private fun apiName(level: ReasoningLevel): String = when (level) {
        ReasoningLevel.OFF -> "none"
        ReasoningLevel.MINIMAL -> "minimal"
        ReasoningLevel.LOW -> "low"
        ReasoningLevel.MEDIUM -> "medium"
        ReasoningLevel.HIGH -> "high"
        // resolve() hands a control only its own choices, and no level row
        // offers these.
        ReasoningLevel.UNSET, ReasoningLevel.ON -> error("$level is not a level")
    }

    private fun reasoningEffort(effort: String): Map<String, JsonElement> =
        mapOf("reasoning_effort" to JsonPrimitive(effort))

    private fun thinkingType(type: String): JsonObject = buildJsonObject { put("type", type) }
}
