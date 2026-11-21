package com.playtranslate.ui

import android.content.Context
import com.playtranslate.R
import com.playtranslate.TranslationCache
import com.playtranslate.model.TranslationResult
import com.playtranslate.translation.BackendFailure
import com.playtranslate.translation.BackendId
import com.playtranslate.translation.ChosenServiceFailedException
import com.playtranslate.translation.GroupTranslator
import com.playtranslate.translation.TranslationBackend
import com.playtranslate.translation.TranslationError
import com.playtranslate.translation.classifyBackendFailure
import kotlinx.coroutines.CancellationException

/**
 * A result on show translated again by the one service the user picked for
 * it ([TranslationPicker]): the same texts the result's translation was made
 * from ([TranslationResult.translationUnits]), under the pair it was made
 * under, through that service alone
 * ([GroupTranslator.translateWith], which says what such a run leaves
 * untouched: the cache, the settings, the icon's degraded state).
 *
 * The one place a surface gets such a translation from, so the units, the
 * pair and what counts as a failure are the same on every surface. What a
 * surface does with the outcome is its own: the results pages land it
 * through their view-model ([TranslationResultViewModel.retranslateWith]),
 * the capture sheet through its bind funnel. Needs no capture service: the
 * services are the process's ([com.playtranslate.translation.TranslationBackendRegistry]).
 */
internal object ChosenServiceTranslation {

    sealed interface Outcome {
        /** The service translated every unit: [perUnit] in the units'
         *  order, which is the order of a capture's boxes. */
        class Translated(val perUnit: List<String>, val backendDisplayName: String) : Outcome {
            /** The result's translation: the units' joined as a capture
             *  joins its groups'. */
            val text: String get() = perUnit.joinToString(TranslationResult.UNIT_SEPARATOR)
        }

        /** The service did not translate the result. [failure] is what an
         *  online service answered or how it could not be reached, for the
         *  reason line; null where there is nothing to say beyond that it
         *  failed (an on-device model, or a service no longer there). */
        class Failed(
            val serviceId: BackendId,
            val serviceName: String,
            val failure: BackendFailure?,
        ) : Outcome
    }

    /** Translate [result]'s units with [backend]. Never throws but for a
     *  cancellation. All or nothing, by the run's own rule
     *  ([com.playtranslate.translation.TranslationBackendRegistry.translateWith]):
     *  a unit the service threw for, or one that has text and came back
     *  with none, fails the whole run, however many of the others were
     *  translated. Landing a blank would put it where the translation on
     *  show has that unit's and take its box off the game screen
     *  ([com.playtranslate.refillOneShotOverlayData] drops a blank one). */
    suspend fun run(appCtx: Context, backend: TranslationBackend, result: TranslationResult): Outcome =
        try {
            val perUnit = GroupTranslator(appCtx, TranslationCache.shared)
                .translateWith(backend.id, result.translationUnits, result.langContext)
            Outcome.Translated(perUnit.map { it.text }, backend.displayName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChosenServiceFailedException) {
            // The reason line names a failure the user can act on, so only
            // an online service's is carried (as the Ask section's is).
            failed(backend, e.cause?.takeIf { backend.requiresInternet }?.let(::classifyBackendFailure))
        } catch (_: Exception) {
            failed(backend, null)
        }

    private fun failed(backend: TranslationBackend, failure: BackendFailure?) =
        Outcome.Failed(backend.id, backend.displayName, failure)

    /** The words for [failed], under the alert's title: an online service's
     *  failure as the translation error pill words it (the service's name
     *  and the reason), anything else the plain statement. */
    fun message(ctx: Context, failed: Outcome.Failed): String =
        failed.failure?.let {
            TranslationErrorMessages.text(ctx, TranslationError.Service(failed.serviceId, failed.serviceName, it))
        } ?: ctx.getString(R.string.translation_pick_failed_generic, failed.serviceName)

    /** The alert that says a pick failed, on [builder]: nothing on show
     *  changed, so it only has to be read. */
    fun failureAlert(builder: OverlayAlert.Builder, ctx: Context, failed: Outcome.Failed): OverlayAlert.Builder =
        builder.setTitle(ctx.getString(R.string.translation_pick_failed_title))
            .setMessage(message(ctx, failed))
            .addCancelButton(ctx.getString(R.string.btn_ok))
}
