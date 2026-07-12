package com.playtranslate.translation.llm

import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import com.playtranslate.Prefs
import com.playtranslate.PtJson
import com.playtranslate.R
import java.util.Locale
import kotlinx.serialization.encodeToString

/**
 * A `{token}` a prompt template may contain, plus the localized
 * description shown in the prompt editor's keyword legend.
 */
data class KeywordInfo(val token: String, @param:StringRes val descRes: Int)

/**
 * One problem [LlmPromptTemplates.validate] found in an edited template.
 * Context-free constants (plus the offending token for [ForeignToken]) —
 * the editor Activity maps each to a localized message, so the validator
 * stays pure-JVM testable.
 */
sealed interface PromptIssue {
    // Fatal — the prompt cannot function; Save refuses.
    data object Blank : PromptIssue
    data object MissingText : PromptIssue
    data object MissingStrings : PromptIssue

    // Advisory — degraded but functional; Save allows a bypass.
    data object MissingSourceRef : PromptIssue
    data object MissingTargetRef : PromptIssue
    data object MissingCount : PromptIssue
    data class ForeignToken(val token: String) : PromptIssue
    data object TooLong : PromptIssue
}

/** Everything [LlmPromptTemplates.validate] found, split by severity. */
data class PromptValidation(
    val fatal: List<PromptIssue>,
    val advisory: List<PromptIssue>,
)

/**
 * The three user-editable prompt templates. Each constant carries its
 * built-in default, the keywords its editor legend documents (also the
 * set the substituter fills for that prompt), and the [Prefs] accessors
 * for its override — kept as lambdas so the enum stays Context-free.
 */
enum class PromptKind(
    val default: String,
    val keywords: List<KeywordInfo>,
    private val readPref: (Prefs) -> String?,
    private val writePref: (Prefs, String?) -> Unit,
) {
    SYSTEM(
        default = LlmPromptTemplates.DEFAULT_SYSTEM,
        keywords = LlmPromptTemplates.LANGUAGE_KEYWORDS,
        readPref = { it.llmSystemPrompt },
        writePref = { p, v -> p.llmSystemPrompt = v },
    ),
    TRANSLATION(
        default = LlmPromptTemplates.DEFAULT_TRANSLATION,
        keywords = LlmPromptTemplates.LANGUAGE_KEYWORDS + listOf(
            KeywordInfo(LlmPromptTemplates.TOKEN_TEXT, R.string.llm_prompt_kw_text_desc),
            KeywordInfo(LlmPromptTemplates.TOKEN_CONTEXT, R.string.llm_prompt_kw_context_desc),
        ),
        readPref = { it.llmTranslationPrompt },
        writePref = { p, v -> p.llmTranslationPrompt = v },
    ),
    BATCH(
        default = LlmPromptTemplates.DEFAULT_BATCH,
        keywords = LlmPromptTemplates.LANGUAGE_KEYWORDS + listOf(
            KeywordInfo(LlmPromptTemplates.TOKEN_COUNT, R.string.llm_prompt_kw_count_desc),
            KeywordInfo(LlmPromptTemplates.TOKEN_STRINGS, R.string.llm_prompt_kw_strings_desc),
            KeywordInfo(LlmPromptTemplates.TOKEN_CONTEXT, R.string.llm_prompt_kw_context_desc),
        ),
        readPref = { it.llmBatchPrompt },
        writePref = { p, v -> p.llmBatchPrompt = v },
    );

    fun read(prefs: Prefs): String? = readPref(prefs)
    fun write(prefs: Prefs, value: String?) = writePref(prefs, value)
}

/**
 * Single source of truth for the user-editable LLM prompt *content*:
 * built-in defaults, `{token}` substitution, override resolution, and
 * Save-time validation. The per-model chat templates
 * ([com.playtranslate.translation.qwen.QwenChatTemplate],
 * [com.playtranslate.translation.gemma.GemmaE2BChatTemplate],
 * [LlmBatchPrompt]) delegate their prose to this object and keep only
 * their envelope logic (role markers, `<bos>`, `<think>` suffixes).
 * [com.playtranslate.translation.hymt.HyMtChatTemplate] is deliberately
 * NOT wired here — Hunyuan-MT is a task-fine-tuned model whose model-card
 * prompt format must not be user-edited.
 *
 * Defaults are Kotlin constants, not string resources, so they can never
 * be localized by accident — the models are instruction-tuned in English
 * (see [languageDisplayName]).
 */
object LlmPromptTemplates {

    const val TOKEN_SOURCE = "{source}"
    const val TOKEN_TARGET = "{target}"
    const val TOKEN_SOURCE_CODE = "{source_code}"
    const val TOKEN_TARGET_CODE = "{target_code}"
    const val TOKEN_TEXT = "{text}"
    const val TOKEN_COUNT = "{N}"
    const val TOKEN_STRINGS = "{strings}"

    /** Recent source→translation pairs, or "" when the feature is off /
     *  nothing qualifies. The provider's non-empty value is a self-contained
     *  block ENDING IN "\n\n", so the default template renders byte-identical
     *  to the pre-context prompt whenever context is absent. */
    const val TOKEN_CONTEXT = "{context}"

    /** Advisory threshold — a template this long risks slowing (or
     *  overflowing the context of) the on-device models at prefill. */
    const val PROMPT_LENGTH_ADVISORY_CHARS = 4_000

    /** The persona/quality portion of the default system prompt — the part
     *  that is also safe to reuse on the batch path. */
    private const val DEFAULT_SYSTEM_PERSONA =
        "You are a professional {source} ({source_code}) to {target} ({target_code}) translator. " +
            "Your goal is to accurately convey the meaning and nuances of the original {source} text " +
            "while adhering to {target} grammar, vocabulary, and cultural sensitivities."

    /** The single-text output instruction. Kept OUT of [DEFAULT_SYSTEM_PERSONA]
     *  because it directly contradicts [BATCH_JSON_CONTRACT]'s respond-with-JSON
     *  requirement — [batchSystemPrompt] must never combine the two. */
    private const val DEFAULT_SYSTEM_OUTPUT =
        "Produce only the {target} translation, without any additional explanations or commentary."

    const val DEFAULT_SYSTEM = DEFAULT_SYSTEM_PERSONA + "\n\n" + DEFAULT_SYSTEM_OUTPUT

    const val DEFAULT_TRANSLATION =
        "{context}Please translate the following {source} text into {target}:\n\n{text}"

    // {context} prefixes the batch default for the same reason as the
    // single-text default: the user's context toggle must alter EVERY LLM
    // path it claims to (cloud multi-group live cycles batch), and an
    // empty provider renders byte-identical to the pre-context prompt.
    const val DEFAULT_BATCH = "{context}Translate each of these {N} strings:\n{strings}"

    /**
     * The batch response contract, appended verbatim (substituted, never
     * user-editable) after the persona on the cloud batch path. It carries
     * ALL of the machine-shape language — input framing, the per-string
     * translate instruction, and the output schema — because the backends'
     * response parsers depend on the `{"translations": [...]}` shape; only
     * persona/style is editable.
     */
    private const val BATCH_JSON_CONTRACT =
        "You will be given a JSON array of {source} strings. " +
            "Translate each string into {target}, preserving meaning and nuance. " +
            "Respond only with a JSON object of the form {\"translations\": [...]} containing " +
            "exactly one {target} string per input, in the same order. " +
            "Do not add commentary, prefixes, or notes."

    val LANGUAGE_KEYWORDS = listOf(
        KeywordInfo(TOKEN_SOURCE, R.string.llm_prompt_kw_source_desc),
        KeywordInfo(TOKEN_SOURCE_CODE, R.string.llm_prompt_kw_source_code_desc),
        KeywordInfo(TOKEN_TARGET, R.string.llm_prompt_kw_target_desc),
        KeywordInfo(TOKEN_TARGET_CODE, R.string.llm_prompt_kw_target_code_desc),
    )

    /**
     * All recognized tokens, longer alternatives first (defensive hygiene —
     * no current token is a prefix of another, but `{source_code}` before
     * `{source}` keeps that true if one ever becomes so).
     */
    private val TOKEN_REGEX = Regex(
        listOf(
            TOKEN_SOURCE_CODE, TOKEN_TARGET_CODE, TOKEN_STRINGS,
            TOKEN_SOURCE, TOKEN_TARGET, TOKEN_TEXT, TOKEN_COUNT,
            TOKEN_CONTEXT,
        ).joinToString("|") { Regex.escape(it) }
    )

    /**
     * Where the app installs the Prefs-backed override lookup — the one
     * composition-root hook, set once from
     * [com.playtranslate.PlayTranslateApplication.onCreate]. NOTE: this is
     * a deliberate static seam, not the codebase's usual constructor
     * injection — the consumers are stateless `object` templates and the
     * MnnTranslator singleton, where threading a constructor dependency
     * through would churn the riskiest file in the module. The `{ null }`
     * default means pure-JVM tests (and any pre-onCreate caller) resolve
     * to the built-in defaults.
     */
    @Volatile
    var overrideProvider: (PromptKind) -> String? = { null }

    /** Composition-root hook for `{context}` (recent source→translation
     *  pairs), same static-seam rationale as [overrideProvider]. Re-read on
     *  every [translationUserMessage] call, and called from backend threads
     *  (MNN's coroutine, cloud backends on IO) — implementations must be
     *  thread-safe. Must return "" (never null) when nothing qualifies, and
     *  a "\n\n"-terminated block otherwise (see [TOKEN_CONTEXT]). */
    @Volatile
    var contextProvider: (source: String, target: String) -> String = { _, _ -> "" }

    @VisibleForTesting
    fun resetOverrides() {
        overrideProvider = { null }
        contextProvider = { _, _ -> "" }
    }

    /** The raw template for [kind] — the user's override if saved, else the
     *  built-in default. Tokens NOT substituted; this is what the editor shows. */
    fun effectiveTemplate(kind: PromptKind): String =
        overrideProvider(kind) ?: kind.default

    /** The system-role prose for a single translation (also the persona
     *  portion of [batchSystemPrompt]). */
    fun systemPrompt(source: String, target: String): String =
        substitute(effectiveTemplate(PromptKind.SYSTEM), languageValues(source, target))

    /** The user-turn prose wrapped around one text to translate. `{context}`
     *  resolves through [contextProvider] here — backends and chat templates
     *  never see it, so no signature anywhere changes with the feature. */
    fun translationUserMessage(text: String, source: String, target: String): String =
        substitute(
            effectiveTemplate(PromptKind.TRANSLATION),
            languageValues(source, target) +
                mapOf(TOKEN_TEXT to text, TOKEN_CONTEXT to contextProvider(source, target)),
        )

    /**
     * The cloud batch system message: editable persona + fixed contract.
     * When the System prompt is unedited, only [DEFAULT_SYSTEM_PERSONA] is
     * used — NOT the full [DEFAULT_SYSTEM] — because its "produce only the
     * translation" output instruction contradicts the contract's
     * respond-with-JSON requirement, and non-schema-enforcing endpoints
     * (OpenAI-compatible servers without strict mode) can follow the wrong
     * one and break the batch parser. A user override is used verbatim —
     * their wording, their risk — with the contract appended last, which
     * models weight heaviest.
     */
    fun batchSystemPrompt(source: String, target: String): String {
        val values = languageValues(source, target)
        val persona = overrideProvider(PromptKind.SYSTEM) ?: DEFAULT_SYSTEM_PERSONA
        return substitute(persona, values) + "\n\n" + substitute(BATCH_JSON_CONTRACT, values)
    }

    /** The cloud batch user message; [texts] ride along as a JSON array.
     *  `{context}` resolves here exactly like the single-text path — the
     *  JSON contract's "exactly one string per input" keeps context lines
     *  out of the response array. */
    fun batchUserMessage(texts: List<String>, source: String, target: String): String =
        substitute(
            effectiveTemplate(PromptKind.BATCH),
            languageValues(source, target) + mapOf(
                TOKEN_COUNT to texts.size.toString(),
                TOKEN_STRINGS to PtJson.lenient.encodeToString(texts),
                TOKEN_CONTEXT to contextProvider(source, target),
            ),
        )

    /**
     * Save-time validation of a raw edited [text] for [kind]. Literal
     * substring checks — matching what [substitute] will actually replace.
     */
    fun validate(kind: PromptKind, text: String): PromptValidation {
        if (text.isBlank()) return PromptValidation(listOf(PromptIssue.Blank), emptyList())

        val fatal = mutableListOf<PromptIssue>()
        val advisory = mutableListOf<PromptIssue>()
        when (kind) {
            PromptKind.SYSTEM -> Unit
            PromptKind.TRANSLATION -> if (TOKEN_TEXT !in text) fatal += PromptIssue.MissingText
            PromptKind.BATCH -> if (TOKEN_STRINGS !in text) fatal += PromptIssue.MissingStrings
        }
        // Source/target refs are advised on SYSTEM and TRANSLATION only —
        // the batch user message legitimately omits them (its own default
        // does; the languages are named by the batch *system* message).
        if (kind != PromptKind.BATCH) {
            if (TOKEN_SOURCE !in text && TOKEN_SOURCE_CODE !in text) advisory += PromptIssue.MissingSourceRef
            if (TOKEN_TARGET !in text && TOKEN_TARGET_CODE !in text) advisory += PromptIssue.MissingTargetRef
        }
        if (kind == PromptKind.BATCH && TOKEN_COUNT !in text) advisory += PromptIssue.MissingCount
        // Recognized tokens that this prompt never fills — they'd be sent
        // as literal braces, the likeliest silent user confusion.
        val available = kind.keywords.map { it.token }
        val allTokens = PromptKind.entries.flatMap { k -> k.keywords.map { it.token } }.distinct()
        for (token in allTokens - available.toSet()) {
            if (token in text) advisory += PromptIssue.ForeignToken(token)
        }
        if (text.length > PROMPT_LENGTH_ADVISORY_CHARS) advisory += PromptIssue.TooLong
        return PromptValidation(fatal, advisory)
    }

    /** What to persist for an accepted edit: trimmed text, or null when it
     *  equals the built-in default so future default improvements flow. */
    fun normalize(kind: PromptKind, text: String): String? =
        text.trim().takeIf { it != kind.default }

    private fun languageValues(source: String, target: String): Map<String, String> {
        val src = source.lowercase(Locale.ROOT)
        val tgt = target.lowercase(Locale.ROOT)
        return mapOf(
            TOKEN_SOURCE to languageDisplayName(src),
            TOKEN_TARGET to languageDisplayName(tgt),
            TOKEN_SOURCE_CODE to src,
            TOKEN_TARGET_CODE to tgt,
        )
    }

    /**
     * Single-pass token replacement. The function-transform overload of
     * [Regex.replace] inserts replacements *literally* — load-bearing,
     * because the `{strings}` JSON payload routinely contains `\"`/`\n`
     * escapes and user `{text}` may contain `$`, which the String-overload
     * would reinterpret as group references. Single-pass also means
     * injected values are never re-scanned for tokens, and tokens absent
     * from [values] (foreign to this prompt) pass through untouched.
     */
    private fun substitute(template: String, values: Map<String, String>): String =
        TOKEN_REGEX.replace(template) { match -> values[match.value] ?: match.value }
}
