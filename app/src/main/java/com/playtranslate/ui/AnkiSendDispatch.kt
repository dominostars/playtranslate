package com.playtranslate.ui

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import com.playtranslate.AnkiManager
import com.playtranslate.Prefs
import com.playtranslate.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "AnkiSendDispatch"

/**
 * Outcome of resolving the user's selected card type at send time. The
 * sealed shape lets the dispatcher branch cleanly without smart-casting
 * against `Pair<Long, List<String>>` placeholders.
 */
private sealed interface ModelTarget {
    val model: AnkiManager.ModelInfo

    /** Default (PlayTranslate) — the mode's field-based PlayTranslate
     *  model ([PtModels.WORD] / [PtModels.SENTENCE]), resolved (and
     *  lazily created) at dispatch time. */
    data class Default(
        override val model: AnkiManager.ModelInfo,
    ) : ModelTarget
    /**
     * Anki Basic shape ({Front, Back} or {Front, Back, Picture}).
     * Bypasses the mapping system — fields are assembled at send time
     * from the current mode via [AnkiCardTypeMapper.assembleBasicNote].
     */
    data class Basic(
        override val model: AnkiManager.ModelInfo,
    ) : ModelTarget
    /** Custom or mining-template card — uses the saved per-field mapping. */
    data class Structured(
        override val model: AnkiManager.ModelInfo,
        val mapping: Map<String, ContentSource>,
    ) : ModelTarget
}

/** The one field name every assembler routes the screenshot to:
 *  [PtModels.assemble] (via `PtNote.toValues`) and
 *  [AnkiCardTypeMapper.assembleBasicNote] both key on it literally, so a
 *  model that lacks it drops the image no matter what we upload. */
private const val PICTURE_FIELD = "Picture"

/** True when a note assembled for this target has somewhere to put the
 *  screenshot. False means the image was never going to appear on the
 *  card — a plain Basic {Front, Back}, a mapping with no PICTURE source,
 *  or one of our own models whose Picture field was renamed in AnkiDroid
 *  — so a failed upload costs the user nothing and must not be reported
 *  as a loss. */
private val ModelTarget.holdsPicture: Boolean
    get() = when (this) {
        is ModelTarget.Default -> PICTURE_FIELD in model.fieldNames
        is ModelTarget.Basic   -> PICTURE_FIELD in model.fieldNames
        // Read the mapping the way [AnkiCardTypeMapper.assembleNote] does —
        // per LIVE field name, so a stale entry left behind by a renamed
        // field doesn't count as a destination.
        is ModelTarget.Structured ->
            model.fieldNames.any { mapping[it] == ContentSource.PICTURE }
    }

/**
 * Result of an attempted Anki send. Callers map this to user-visible
 * Toast / dismiss behavior.
 */
sealed interface AnkiSendResult {
    /** addNote succeeded — the caller dismisses the sheet.
     *  [audioDropped] is true when sentence audio was requested (a non-null
     *  audioPath) but its media upload failed, so the note was added
     *  without the `[sound:]` tag.
     *  [wordAudioDropped] is true when at least one per-target-word audio
     *  upload failed (requested count > uploaded count), so the
     *  corresponding word(s) in the card carry no `[sound:]` tag.
     *  [imageDropped] is true when a screenshot was requested (a non-null
     *  screenshotPath) and the target note had a field to hold it, but its
     *  media upload failed — the card landed with an empty Picture field.
     *  [definitionsSimplified] is true when the assembled note blew the
     *  binder parcel budget ([AnkiNoteSizeGuard]) and was re-assembled
     *  with plain-text definitions (no structured glossaries, no inlined
     *  dictionary CSS) — either with the user's consent (the sheets'
     *  oversize prompt) or automatically (one-tap, which has no UI to
     *  ask).
     *  Callers surface these flags via [Success.shortfallRes]. */
    data class Success(
        val audioDropped: Boolean = false,
        val wordAudioDropped: Boolean = false,
        val imageDropped: Boolean = false,
        val definitionsSimplified: Boolean = false,
    ) : AnkiSendResult
    /** The send failed — the caller shows the error and restores the
     *  save button. [messageRes] names the cause where the dispatcher
     *  knows it, and is generic otherwise. [message], when non-null, is
     *  pre-formatted text (runtime args like a field name already baked
     *  in) that callers show INSTEAD of [messageRes]. */
    data class Failed(
        @StringRes val messageRes: Int,
        val message: String? = null,
    ) : AnkiSendResult
    /** The user's picked card type has no configured mapping. The
     *  dispatcher already showed a Toast pointing this out; callers
     *  with Fragment infrastructure open the mapping dialog for
     *  [model], while overlay-context callers re-launch the
     *  permission/review activity so the sheet's dialog is reachable.
     *  [model] is the model the dispatcher resolved at decision time —
     *  threading it through avoids a prefs-race if the user changes
     *  the card type between dispatch and follow-up. */
    data class NeedsMapping(val model: AnkiManager.ModelInfo) : AnkiSendResult
    /** The user declined the oversize prompt (chose not to send a
     *  simplified card). Nothing was uploaded or inserted — the probe
     *  runs before the media pass — so callers just restore the save
     *  button, silently: the user made this choice a moment ago and
     *  needs no alert re-explaining it. */
    data object Declined : AnkiSendResult
}

/**
 * Binder budget for the AnkiDroid ContentProvider note insert. The whole
 * `flds` payload crosses in ONE binder transaction, and Android hard-caps
 * a transaction at ~1MB (shared with every other in-flight transaction in
 * the process). A function-word-dense sentence card with styled Yomitan
 * definitions was recorded failing at a 1,684,376-byte parcel — the
 * insert dies in the kernel, ContentResolver.insert swallows the
 * RemoteException as a null return, and without this guard the user was
 * told "make sure AnkiDroid is running".
 *
 * Java Strings marshal as UTF-16, so the estimate is 2 bytes per char
 * plus a small allowance for the ContentValues keys, the non-field
 * values, and binder framing.
 */
internal object AnkiNoteSizeGuard {
    /** Degrade threshold: above this, the dispatcher re-assembles with
     *  plain-text definitions. Leaves ~25% headroom under the 1MB
     *  transaction cap for concurrent binder traffic and estimate slack
     *  (the probe omits media filenames, worth a few hundred bytes). */
    const val BUDGET_BYTES = 750_000

    /** Honest-failure backstop just under the real wall: a final field
     *  set above this returns the too-large error instead of letting the
     *  insert die in the binder as a misleading generic failure. With the
     *  probe in front it should never fire. */
    const val HARD_LIMIT_BYTES = 900_000

    fun estimatedParcelBytes(fields: List<String>): Int =
        fields.sumOf { it.length } * 2 + 4_096
}

/**
 * The message naming what this otherwise-successful send could not
 * carry, or null when the card landed complete. One resolver for every
 * send surface so a partial send reads the same everywhere: the review
 * sheets (silent on a clean send) toast only when this is non-null,
 * while the one-tap surfaces fall back to their mode-named success
 * message.
 *
 * Screenshot and audio each get their own message, plus a combined one:
 * whatever breaks AnkiDroid's media store tends to break it for every
 * upload in the pass, and naming only half of what's missing leaves the
 * user to find the rest in their deck later. A media loss outranks the
 * simplified-definitions note: missing media is data the card will never
 * have, while simplified definitions still carry every sense as text.
 */
@StringRes
fun AnkiSendResult.Success.shortfallRes(): Int? {
    val audioMissing = audioDropped || wordAudioDropped
    return when {
        imageDropped && audioMissing -> R.string.anki_added_no_screenshot_or_audio
        imageDropped -> R.string.anki_added_no_screenshot
        audioMissing -> R.string.anki_added_no_audio
        definitionsSimplified -> R.string.anki_added_simplified
        else -> null
    }
}

/**
 * Shared "send a card to AnkiDroid" pipeline used by the review sheets
 * (via the Fragment-flavored sendSentenceCard / sendWordCard wrappers
 * in AnkiSendPipeline) and the one-tap
 * helpers. Resolves the chosen card type, uploads media, builds the
 * field array (default PlayTranslate model / Basic / structured
 * per-mapping), and writes the note. Surface UX (mapping dialog open,
 * button restore, fragment-result post) is the caller's job — the
 * dispatcher only shows the explanatory Toast on the NeedsMapping
 * branches.
 *
 * Returns [AnkiSendResult.NeedsMapping] carrying the resolved model
 * when the user's picked card type has no configured mapping. Callers
 * with Fragment infrastructure (the review sheets) open the mapping
 * dialog for that model; overlay-context callers re-launch the
 * review activity so the user can configure it inside the sheet.
 *
 * @param mode             Which sheet flow this came from — picks the
 *                         default PlayTranslate model, and is relayed
 *                         to the mapping dialog by callers that open
 *                         one (Basic-shape templates rely on `mode`
 *                         for their defaults).
 * @param screenshotPath   Path to the screenshot to attach to the
 *                         Picture field, or null.
 * @param audioPath        Path to the synthesized TTS audio file to
 *                         attach, or null.
 * @param ptNote           Lazy builder for the default PlayTranslate
 *                         note payload; receives the AnkiDroid-side
 *                         image and audio filenames, and the
 *                         `simplified` flag (true = render plain-text
 *                         definitions: no structured glossaries, no
 *                         inlined dictionary CSS).
 * @param structured       Lazy builder for the structured outputs;
 *                         same arguments as [ptNote].
 */
suspend fun Context.dispatchSendToAnki(
    deckId: Long,
    mode: CardMode,
    screenshotPath: String?,
    audioPath: String?,
    ptNote: (imageFilename: String?, audioFilename: String?, wordAudioFilenames: Map<String, String>, simplified: Boolean) -> PtNote,
    structured: (imageFilename: String?, audioFilename: String?, wordAudioFilenames: Map<String, String>, simplified: Boolean) -> CardOutputs,
    /** Per-target-word audio paths keyed by word. Uploaded individually
     *  via [AnkiManager.addMediaFromFile] in the same media pass as the
     *  screenshot and sentence audio. The returned filename map is then
     *  threaded into [ptNote] / [structured] so each word's row in
     *  the words table can carry a `[sound:…]` tag. */
    wordAudioPaths: Map<String, String> = emptyMap(),
    /** Asked when the fully-styled note would blow the binder budget but
     *  a simplified one fits: return true to send simplified, false to
     *  abort ([AnkiSendResult.Declined]). Null (one-tap and every other
     *  surface with no UI to ask) auto-consents — a simplified card beats
     *  no card on a flow the user isn't watching. Runs BEFORE the media
     *  pass, so declining uploads nothing and orphans nothing. */
    oversizePrompt: (suspend () -> Boolean)? = null,
): AnkiSendResult {
    val ctx = this
    val prefs = Prefs(ctx)
    val anki = AnkiManager(ctx)

    // Resolve the target model + mapping FIRST, before uploading any
    // media. Every bail path below — `Failed(models_unavailable)`,
    // `NeedsMapping`, and a default-model create failure — would
    // otherwise leave the screenshot, sentence audio, and every
    // per-target-word audio file orphaned in AnkiDroid's media folder.
    // Only the mapped-but-empty first-field bail stays after uploads:
    // it needs the assembled fields.
    val pickedId = prefs.ankiModelId
    val target: ModelTarget = when {
        pickedId == -1L -> {
            val model = withContext(Dispatchers.IO) {
                anki.getOrCreatePtModel(PtModels.specFor(mode))
            } ?: return AnkiSendResult.Failed(R.string.anki_send_failed_message)
            ModelTarget.Default(model)
        }
        else -> {
            val models = withContext(Dispatchers.IO) { anki.getModels() }
            // Empty list always means transient query/permission
            // failure: a working AnkiDroid install ships built-in
            // Basic + Cloze note types, so a real install never has
            // zero models. Abort rather than treating it as "model
            // deleted" — that would destructively reset prefs and
            // silently insert into a default PlayTranslate model, leaving
            // the user with a card in the wrong place under a
            // "success" toast. The healing pass at
            // AnkiUiHelper.addAnkiSection's healing applies the same guard.
            if (models.isEmpty()) {
                return AnkiSendResult.Failed(R.string.anki_models_unavailable)
            }
            val picked = models.firstOrNull { it.id == pickedId }
            if (picked == null) {
                // Card type was deleted/renamed away in AnkiDroid since
                // the user picked it. Safe to reset prefs because we
                // already know `models` is non-empty (the genuine
                // "model is gone" signal). Fall back to the default
                // PlayTranslate model for this mode.
                prefs.ankiModelId = -1L
                prefs.ankiModelName = ""
                Toast.makeText(ctx, R.string.anki_card_type_stale_fallback,
                    Toast.LENGTH_SHORT).show()
                val model = withContext(Dispatchers.IO) {
                    anki.getOrCreatePtModel(PtModels.specFor(mode))
                } ?: return AnkiSendResult.Failed(R.string.anki_send_failed_message)
                ModelTarget.Default(model)
            } else if (AnkiCardTypeMapper.isBasicShape(picked.fieldNames)) {
                // Basic-shape templates don't carry a stored mapping —
                // assembleBasicNote derives Front/Back from the current
                // send mode at dispatch time. See AnkiCardTypeMapper
                // for the full rationale.
                ModelTarget.Basic(picked)
            } else {
                val mapping = prefs.getAnkiFieldMapping(pickedId)
                if (mapping.values.none { it != ContentSource.NONE }) {
                    // User picked a card type but never configured (or
                    // wiped) the mapping. Don't ship an empty note —
                    // surface NeedsMapping so the caller can open the
                    // mapping dialog (Fragment wrapper) or fall back to
                    // the Activity flow (overlay-context callers).
                    Toast.makeText(ctx, R.string.anki_field_mapping_unconfigured,
                        Toast.LENGTH_LONG).show()
                    return AnkiSendResult.NeedsMapping(picked)
                }
                // First-field preflight: Anki's duplicate-detection
                // checksum (`csum`) is computed from the note's FIRST
                // field — NOT the model's browser sort field, which
                // plays no role in note identity. Inserting with an
                // empty first field gives every note the same csum, and
                // AnkiDroid rejects the second one onwards as a
                // duplicate (null URI → generic "Failed to add card").
                // The canonical trigger is JPMN's leading `Key` field,
                // which PT's defaults intentionally leave unmapped so
                // the user picks what uniquely identifies their cards.
                // An unmapped first field always assembles to "", so
                // bail HERE, before the media pass below — each upload
                // for a send that can't complete is an orphan in
                // AnkiDroid's media store (no content dedup; every
                // attempt mints a fresh uniquely-suffixed file). (An
                // earlier version keyed this guard on the sort field
                // after assembly — indistinguishable on JPMN, where
                // `Key` is both — and hard-blocked Senren-style note
                // types whose `freqSort` sort field sits mid-list and
                // is legitimately empty for words with no frequency
                // data.)
                val firstFieldName = picked.fieldNames.firstOrNull().orEmpty()
                if ((mapping[firstFieldName] ?: ContentSource.NONE) == ContentSource.NONE) {
                    Toast.makeText(
                        ctx,
                        ctx.getString(R.string.anki_first_field_unmapped, firstFieldName),
                        Toast.LENGTH_LONG,
                    ).show()
                    return AnkiSendResult.NeedsMapping(picked)
                }
                ModelTarget.Structured(picked, mapping)
            }
        }
    }

    // Pure per-target field assembly, shared by the size probe below and
    // the final post-upload build. The first-field guards and logging
    // stay at the final build — the probe's null media filenames would
    // false-trip a first field mapped to PICTURE.
    fun assembleFields(
        imageFilename: String?,
        audioFilename: String?,
        wordAudioFilenames: Map<String, String>,
        simplified: Boolean,
    ): List<String> = when (target) {
        is ModelTarget.Default -> PtModels.assemble(
            target.model.fieldNames,
            ptNote(imageFilename, audioFilename, wordAudioFilenames, simplified),
        )
        is ModelTarget.Basic -> AnkiCardTypeMapper.assembleBasicNote(
            target.model.fieldNames, mode,
            structured(imageFilename, audioFilename, wordAudioFilenames, simplified),
        )
        is ModelTarget.Structured -> AnkiCardTypeMapper.assembleNote(
            target.model.fieldNames, target.mapping,
            structured(imageFilename, audioFilename, wordAudioFilenames, simplified),
        )
    }

    // Size probe, BEFORE the media pass: the note insert is one binder
    // transaction and dies past ~1MB (see [AnkiNoteSizeGuard]), so a note
    // that can't fit must be simplified or abandoned while abandoning is
    // still free — every media file uploaded for a send that then fails
    // is an orphan in AnkiDroid's media store. Probed with null media
    // filenames; the missing `[sound:]`/filename text is a few hundred
    // bytes against the budget's headroom.
    var simplified = false
    val probeBytes = withContext(Dispatchers.Default) {
        AnkiNoteSizeGuard.estimatedParcelBytes(
            assembleFields(null, null, emptyMap(), simplified = false))
    }
    if (probeBytes > AnkiNoteSizeGuard.BUDGET_BYTES) {
        val simplifiedBytes = withContext(Dispatchers.Default) {
            AnkiNoteSizeGuard.estimatedParcelBytes(
                assembleFields(null, null, emptyMap(), simplified = true))
        }
        Log.w(TAG, "note over binder budget: est=${probeBytes}B " +
            "simplified=${simplifiedBytes}B budget=${AnkiNoteSizeGuard.BUDGET_BYTES}B")
        if (simplifiedBytes > AnkiNoteSizeGuard.BUDGET_BYTES) {
            // Even plain-text definitions don't fit. Fail honestly — the
            // generic message would blame AnkiDroid not running.
            return AnkiSendResult.Failed(R.string.anki_card_too_large_failed)
        }
        if (oversizePrompt?.invoke() == false) return AnkiSendResult.Declined
        simplified = true
    }

    // Target is resolved and every preflightable bail is past. Now
    // upload media — anything we upload from here has a real shot at
    // being attached to a successfully-inserted note. The residual
    // orphan surface is the mapped-but-empty first-field bail and
    // addNote itself failing, both only knowable post-upload.
    val imageFilename = screenshotPath?.let {
        withContext(Dispatchers.IO) { anki.addMediaFromFile(File(it)) }
    }
    val audioFilename = audioPath?.let {
        withContext(Dispatchers.IO) { anki.addMediaFromFile(File(it)) }
    }
    // Per-word media uploads. Words whose upload returns null
    // (transient failure) are absent from the resulting map; the
    // wordAudioDropped flag on Success reports the partial-failure
    // count so callers can surface it.
    val wordAudioFilenames: Map<String, String> = withContext(Dispatchers.IO) {
        wordAudioPaths.mapNotNull { (word, path) ->
            anki.addMediaFromFile(File(path))?.let { word to it }
        }.toMap()
    }

    // Assemble against the model's ACTUAL field names (read back from
    // AnkiDroid) so user-added or reordered fields on our note types keep
    // working — assembleFields runs the same per-shape builders the log
    // lines below name.
    val fields = assembleFields(imageFilename, audioFilename, wordAudioFilenames, simplified)
    val estBytes = AnkiNoteSizeGuard.estimatedParcelBytes(fields)
    val shape = when (target) {
        is ModelTarget.Default -> "default"
        is ModelTarget.Basic -> "basic"
        is ModelTarget.Structured -> "structured"
    }
    Log.d(TAG, "$shape send: model=${target.model.name} mode=$mode " +
        "fields=${fields.size} non-empty=${fields.count { it.isNotEmpty() }} " +
        "est=${estBytes}B simplified=$simplified")
    // First-field guard: Anki's duplicate-detection checksum (`csum`) is
    // computed from the note's FIRST field, so inserting with an empty
    // first field gives every note the same csum and AnkiDroid rejects
    // the second one onwards as a duplicate (null URI → generic "Failed
    // to add card"). Two arms share this check:
    //  - Default: a renamed first field (assemble maps it to "") or a
    //    reorder that put an empty-able field first. No NeedsMapping —
    //    the mapping dialog doesn't apply to our own models; the remedy
    //    is renaming the field back in AnkiDroid.
    //  - Structured, mapped-but-empty: the unmapped case bailed
    //    pre-upload in the target resolution block (which carries the
    //    full csum rationale); here the first field IS mapped but its
    //    source produced no value for this card (e.g. a picture source
    //    with no screenshot). Only decidable post-assembly — the value
    //    can depend on the uploaded media filenames. The mapping dialog
    //    can't conjure missing data, so fail with the explanation
    //    instead of reopening it.
    // Basic shapes skip it: Front derives from the send mode's core text
    // and can't assemble empty.
    if (target !is ModelTarget.Basic && fields.firstOrNull()?.isEmpty() == true) {
        val fieldName = target.model.fieldNames.firstOrNull().orEmpty()
        return AnkiSendResult.Failed(
            R.string.anki_send_failed_message,
            ctx.getString(R.string.anki_first_field_empty, fieldName),
        )
    }
    // Backstop behind the probe: never hand the provider a payload that
    // will die in the binder — that failure surfaces as the misleading
    // "make sure AnkiDroid is running" message. Should be unreachable
    // (the probe already bounded the size), hence Failed rather than a
    // second prompt.
    if (estBytes > AnkiNoteSizeGuard.HARD_LIMIT_BYTES) {
        Log.e(TAG, "assembled note over binder hard limit: est=${estBytes}B")
        return AnkiSendResult.Failed(R.string.anki_card_too_large_failed)
    }

    val ok = withContext(Dispatchers.IO) { anki.addNote(target.model.id, deckId, fields) }
    if (!ok) return AnkiSendResult.Failed(R.string.anki_send_failed_message)
    // The note was added. Flag any media that was requested but didn't
    // make it onto the card so callers can warn the user. The screenshot
    // is only a loss when the note had a field to hold it — see
    // [holdsPicture]; the audio flags carry no such gate because their
    // upload is already conditioned on the user's audio toggle.
    return AnkiSendResult.Success(
        audioDropped = audioPath != null && audioFilename.isNullOrEmpty(),
        wordAudioDropped = wordAudioPaths.size > wordAudioFilenames.size,
        imageDropped = screenshotPath != null && imageFilename.isNullOrEmpty() &&
            target.holdsPicture,
        definitionsSimplified = simplified,
    )
}

