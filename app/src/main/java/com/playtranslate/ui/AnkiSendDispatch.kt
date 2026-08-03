package com.playtranslate.ui

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
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
    /** Default (PlayTranslate) — the mode's field-based PlayTranslate
     *  model ([PtModels.WORD] / [PtModels.SENTENCE]), resolved (and
     *  lazily created) at dispatch time. */
    data class Default(
        val model: AnkiManager.ModelInfo,
    ) : ModelTarget
    /**
     * Anki Basic shape ({Front, Back} or {Front, Back, Picture}).
     * Bypasses the mapping system — fields are assembled at send time
     * from the current mode via [AnkiCardTypeMapper.assembleBasicNote].
     */
    data class Basic(
        val model: AnkiManager.ModelInfo,
    ) : ModelTarget
    /** Custom or mining-template card — uses the saved per-field mapping. */
    data class Structured(
        val model: AnkiManager.ModelInfo,
        val mapping: Map<String, ContentSource>,
    ) : ModelTarget
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
     *  Callers surface either flag to the user. */
    data class Success(
        val audioDropped: Boolean = false,
        val wordAudioDropped: Boolean = false,
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
}

/**
 * Shared "send a card to AnkiDroid" pipeline used by the review sheets
 * (via the [Fragment.dispatchSendToAnki] wrapper) and the one-tap
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
 *                         image and audio filenames.
 * @param structured       Lazy builder for the structured outputs;
 *                         receives the AnkiDroid-side image and audio
 *                         filenames.
 */
suspend fun Context.dispatchSendToAnki(
    deckId: Long,
    mode: CardMode,
    screenshotPath: String?,
    audioPath: String?,
    ptNote: (imageFilename: String?, audioFilename: String?, wordAudioFilenames: Map<String, String>) -> PtNote,
    structured: (imageFilename: String?, audioFilename: String?, wordAudioFilenames: Map<String, String>) -> CardOutputs,
    /** Per-target-word audio paths keyed by word. Uploaded individually
     *  via [AnkiManager.addMediaFromFile] in the same media pass as the
     *  screenshot and sentence audio. The returned filename map is then
     *  threaded into [ptNote] / [structured] so each word's row in
     *  the words table can carry a `[sound:…]` tag. */
    wordAudioPaths: Map<String, String> = emptyMap(),
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

    val (modelId, fields) = when (target) {
        is ModelTarget.Default -> {
            val note = ptNote(imageFilename, audioFilename, wordAudioFilenames)
            // Assemble against the model's ACTUAL field names (read back
            // from AnkiDroid) so user-added or reordered fields on our
            // note types keep working.
            val flds = PtModels.assemble(target.model.fieldNames, note)
            Log.d(TAG, "default send: model=${target.model.name} " +
                "fields=${flds.size} non-empty=${flds.count { it.isNotEmpty() }}")
            // Same first-field guard as the Structured branch below: a
            // renamed first field (assemble maps it to "") or a reorder
            // that put an empty-able field first would hit the
            // provider's duplicate-csum rejection as a generic
            // "Failed to add card" on every send after the first. Fail
            // with the actionable message instead. No NeedsMapping —
            // the field-mapping dialog doesn't apply to our own models;
            // the remedy is renaming the field back in AnkiDroid.
            if (flds.firstOrNull()?.isEmpty() == true) {
                val fieldName = target.model.fieldNames.firstOrNull().orEmpty()
                return AnkiSendResult.Failed(
                    R.string.anki_send_failed_message,
                    ctx.getString(R.string.anki_first_field_empty, fieldName),
                )
            }
            target.model.id to flds
        }
        is ModelTarget.Basic -> {
            val outputs = structured(imageFilename, audioFilename, wordAudioFilenames)
            val flds = AnkiCardTypeMapper.assembleBasicNote(
                target.model.fieldNames, mode, outputs)
            Log.d(TAG, "basic send: model=${target.model.name} mode=$mode " +
                "fields=${flds.size} non-empty=${flds.count { it.isNotEmpty() }}")
            target.model.id to flds
        }
        is ModelTarget.Structured -> {
            val outputs = structured(imageFilename, audioFilename, wordAudioFilenames)
            val flds = AnkiCardTypeMapper.assembleNote(
                target.model.fieldNames, target.mapping, outputs)
            Log.d(TAG, "structured send: model=${target.model.name} " +
                "fields=${flds.size} non-empty=${flds.count { it.isNotEmpty() }}")
            // First-field guard, mapped-but-empty arm — the unmapped
            // case bailed pre-upload in the target resolution block,
            // which also carries the full csum rationale. Here the
            // first field IS mapped, but its source produced no value
            // for this card (e.g. a picture source with no screenshot).
            // Only decidable post-assembly: the value can depend on
            // the uploaded media filenames. The mapping dialog can't
            // conjure missing data — fail with the full explanation
            // instead of reopening it and telling the user to map what
            // they already mapped.
            if (flds.firstOrNull()?.isEmpty() == true) {
                val fieldName = target.model.fieldNames.firstOrNull().orEmpty()
                return AnkiSendResult.Failed(
                    R.string.anki_send_failed_message,
                    ctx.getString(R.string.anki_first_field_empty, fieldName),
                )
            }
            target.model.id to flds
        }
    }

    val ok = withContext(Dispatchers.IO) { anki.addNote(modelId, deckId, fields) }
    if (!ok) return AnkiSendResult.Failed(R.string.anki_send_failed_message)
    // The note was added. Flag any audio that was requested but didn't
    // make it onto the card so callers can warn the user.
    return AnkiSendResult.Success(
        audioDropped = audioPath != null && audioFilename.isNullOrEmpty(),
        wordAudioDropped = wordAudioPaths.size > wordAudioFilenames.size,
    )
}

/**
 * Fragment-flavored wrapper around [Context.dispatchSendToAnki] that
 * also opens the field-mapping dialog when the dispatcher returns
 * [AnkiSendResult.NeedsMapping]. Existing review sheets call this so
 * the user gets the same "configure your mapping" UX they did before
 * the dispatcher was lifted to a Context extension. Overlay-context
 * callers call [Context.dispatchSendToAnki] directly and handle the
 * NeedsMapping result themselves (typically by re-launching the
 * review activity so the sheet's dialog is reachable).
 */
suspend fun Fragment.dispatchSendToAnki(
    deckId: Long,
    mode: CardMode,
    screenshotPath: String?,
    audioPath: String?,
    ptNote: (imageFilename: String?, audioFilename: String?, wordAudioFilenames: Map<String, String>) -> PtNote,
    structured: (imageFilename: String?, audioFilename: String?, wordAudioFilenames: Map<String, String>) -> CardOutputs,
    wordAudioPaths: Map<String, String> = emptyMap(),
): AnkiSendResult {
    val result = requireContext().dispatchSendToAnki(
        deckId = deckId,
        mode = mode,
        screenshotPath = screenshotPath,
        audioPath = audioPath,
        ptNote = ptNote,
        structured = structured,
        wordAudioPaths = wordAudioPaths,
    )
    if (result is AnkiSendResult.NeedsMapping) {
        // Use the model the dispatcher resolved at decision time — no
        // re-read of prefs, so a card-type change between dispatch and
        // dialog open can't redirect to the wrong model.
        showAnkiCardTypeMappingDialog(result.model, mode) { _, _ -> }
    }
    return result
}
