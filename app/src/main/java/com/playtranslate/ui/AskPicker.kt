package com.playtranslate.ui

import android.content.Context
import com.playtranslate.R
import com.playtranslate.translation.TranslationBackend
import com.playtranslate.translation.TranslationBackendRegistry

/**
 * Populates an [OverlayAlert.Builder] as the "Choose who answers" picker
 * for an answer on show in the Ask section: every service that could be put
 * a question if asked now ([TranslationBackendRegistry.setUpToAnswer]: the
 * AI models, online and on the device, and a service that answers
 * questions itself), in the card the translation's picker has
 * ([populateServicePicker]).
 *
 * The twin of [TranslationPicker], and like it a pick changes no setting.
 * It asks the one service picked the question on show again
 * ([com.playtranslate.ask.AskSession.askWith]); the services, their order
 * and their switches stay as they are, so a service that is switched off is
 * listed and can be picked, and so can one in a cooldown. Every button
 * runs, the one already credited included: asking the same model again is
 * another reading.
 *
 * The accent marks the service named in the answer's credit ([creditName],
 * [com.playtranslate.ask.AskState.Answered.answeredBy]). An answer records
 * its service by name alone, so two services with one name both take it.
 *
 * The headers are this picker's own: the Translation services page's
 * ("Online translations", "Offline translations") would name a list of
 * answerers by something they are not being asked for.
 */
object AskPicker {

    fun populate(
        builder: OverlayAlert.Builder,
        ctx: Context,
        creditName: String?,
        onPick: (TranslationBackend) -> Unit,
        onOpenServices: () -> Unit,
    ): OverlayAlert.Builder = populateServicePicker(
        builder, ctx,
        ServicePickerWords(
            title = R.string.ask_picker_title,
            message = R.string.ask_picker_message,
            onlineHeader = R.string.ask_picker_header_online,
            offlineHeader = R.string.ask_picker_header_offline,
        ),
        listed = TranslationBackendRegistry.setUpToAnswer(),
        creditName = creditName,
        onPick = onPick,
        onOpenServices = onOpenServices,
    )
}
