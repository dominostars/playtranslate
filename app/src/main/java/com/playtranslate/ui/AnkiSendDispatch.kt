package com.playtranslate.ui

import android.util.Log
import android.widget.Toast
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
    /** Default (PlayTranslate) — write to v004 via the legacy path. */
    data object Legacy : ModelTarget
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
    /** addNote succeeded — callers Toast success and dismiss. */
    data object Success : AnkiSendResult
    /** addNote attempted but the content provider rejected it — Toast
     *  generic failure. */
    data object Failed : AnkiSendResult
    /** Dispatcher diverted to the mapping dialog because the user's
     *  picked card type had no configured mapping. A Toast was already
     *  shown by the dispatcher; callers should NOT show another. */
    data object NeedsMapping : AnkiSendResult
}

/**
 * Shared "send a card to AnkiDroid" pipeline for the two review sheets.
 * Resolves the chosen card type, builds the field array (legacy v004
 * or structured per-mapping), and writes the note. Returns `true` on
 * success.
 *
 * Special return: `false` when the user has picked a non-default card
 * type but never configured a mapping for it — in that case the helper
 * already showed a Toast and re-opened the mapping dialog, so the
 * caller should treat the result as "no further user-visible action".
 *
 * @param mode             Which sheet flow this came from (informs the
 *                         mapping dialog's Basic-shape defaults on the
 *                         re-open path).
 * @param screenshotPath   Path to the screenshot to attach to the
 *                         Picture field, or null.
 * @param legacyFront      Lazy builder for the legacy v004 front HTML.
 * @param legacyBack       Lazy builder for the legacy v004 back HTML;
 *                         receives the AnkiDroid-side image filename.
 * @param structured       Lazy builder for the structured outputs;
 *                         receives the AnkiDroid-side image filename.
 */
suspend fun Fragment.dispatchSendToAnki(
    deckId: Long,
    mode: CardMode,
    screenshotPath: String?,
    legacyFront: () -> String,
    legacyBack: (imageFilename: String?) -> String,
    structured: (imageFilename: String?) -> CardOutputs,
): AnkiSendResult {
    val ctx = requireContext()
    val prefs = Prefs(ctx)
    val anki = AnkiManager(ctx)

    val imageFilename = screenshotPath?.let {
        withContext(Dispatchers.IO) { anki.addMediaFromFile(File(it)) }
    }

    val pickedId = prefs.ankiModelId
    val target: ModelTarget = when {
        pickedId == -1L -> ModelTarget.Legacy
        else -> {
            val models = withContext(Dispatchers.IO) { anki.getModels() }
            val picked = models.firstOrNull { it.id == pickedId }
            if (picked == null) {
                // Card type was deleted/renamed away in AnkiDroid since
                // the user picked it. Revert to default and notify.
                prefs.ankiModelId = -1L
                prefs.ankiModelName = ""
                Toast.makeText(ctx, R.string.anki_card_type_stale_fallback,
                    Toast.LENGTH_SHORT).show()
                ModelTarget.Legacy
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
                    // open the mapping dialog so they can wire it up.
                    Toast.makeText(ctx, R.string.anki_field_mapping_unconfigured,
                        Toast.LENGTH_LONG).show()
                    showAnkiCardTypeMappingDialog(picked, mode) { _, _ -> }
                    return AnkiSendResult.NeedsMapping
                }
                ModelTarget.Structured(picked, mapping)
            }
        }
    }

    val (modelId, fields) = when (target) {
        ModelTarget.Legacy -> {
            val v004 = withContext(Dispatchers.IO) { anki.getOrCreateModel() }
                ?: return AnkiSendResult.Failed
            v004 to listOf(legacyFront(), legacyBack(imageFilename))
        }
        is ModelTarget.Basic -> {
            val outputs = structured(imageFilename)
            val flds = AnkiCardTypeMapper.assembleBasicNote(
                target.model.fieldNames, mode, outputs)
            Log.d(TAG, "basic send: model=${target.model.name} mode=$mode " +
                "fields=${flds.size} non-empty=${flds.count { it.isNotEmpty() }}")
            target.model.id to flds
        }
        is ModelTarget.Structured -> {
            val outputs = structured(imageFilename)
            val flds = AnkiCardTypeMapper.assembleNote(
                target.model.fieldNames, target.mapping, outputs)
            Log.d(TAG, "structured send: model=${target.model.name} " +
                "fields=${flds.size} non-empty=${flds.count { it.isNotEmpty() }}")
            // Sort field guard: AnkiDroid (and Anki desktop) compute
            // a checksum of `fields[sortf]` for duplicate detection.
            // An empty sort field means every note we insert has the
            // same csum — AnkiDroid's content provider rejects the
            // second one onwards as a duplicate (returns null URI,
            // surfacing as a generic "Failed to add card" toast). The
            // canonical trigger is JPMN's leading `Key` field, which
            // PT's defaults intentionally leave unmapped so the user
            // can pick what uniquely identifies their cards. Catch
            // that here with a clear actionable error instead of the
            // mysterious silent failure.
            val sortf = target.model.sortf
            if (sortf in flds.indices && flds[sortf].isEmpty()) {
                val sortFieldName = target.model.fieldNames.getOrNull(sortf).orEmpty()
                Toast.makeText(
                    ctx,
                    ctx.getString(R.string.anki_sort_field_empty, sortFieldName),
                    Toast.LENGTH_LONG,
                ).show()
                showAnkiCardTypeMappingDialog(target.model, mode) { _, _ -> }
                return AnkiSendResult.NeedsMapping
            }
            target.model.id to flds
        }
    }

    val ok = withContext(Dispatchers.IO) { anki.addNote(modelId, deckId, fields) }
    return if (ok) AnkiSendResult.Success else AnkiSendResult.Failed
}
