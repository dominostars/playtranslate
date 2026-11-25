package com.playtranslate.ui

import android.content.Context
import com.playtranslate.R
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.model.TranslationLangContext
import com.playtranslate.overlayThemedContext
import com.playtranslate.themeColor
import com.playtranslate.translation.TranslationBackend
import com.playtranslate.translation.TranslationBackendRegistry

/**
 * Populates an [OverlayAlert.Builder] as the "Choose translation service"
 * picker for a result on show: every service that could translate the
 * result's language pair if asked now
 * ([TranslationBackendRegistry.setUpFor]), in the card every picker of one
 * service for one run has ([populateServicePicker]).
 *
 * The twin of [OcrPicker], and unlike it in one way that its message says:
 * a pick here changes no setting. It asks the one service picked to
 * translate the result on show again ([ChosenServiceTranslation]); the
 * services, their order and their switches stay as they are, so a service
 * that is switched off is listed and can be picked, and so can one in a
 * cooldown. That is also why every button runs, the one already credited
 * included: asking the same service again is another reading from an AI
 * model, or a second try after a failure, never a no-op to hide.
 *
 * The accent marks the service named in the result's credit
 * ([creditName], [com.playtranslate.model.TranslationResult.backendDisplayName]).
 * A result records its service by name alone, so two services with one name
 * (two keys of one provider) both take it.
 *
 * Shared by every surface that shows the credit, so the list, its order and
 * its look cannot drift. The caller constructs the right builder (an overlay
 * window, an activity, the workspace's modal layer), shows what this
 * returns, and says what a pick and the settings button do on its surface.
 */
object TranslationPicker {

    fun populate(
        builder: OverlayAlert.Builder,
        ctx: Context,
        langContext: TranslationLangContext,
        creditName: String?,
        onPick: (TranslationBackend) -> Unit,
        onOpenServices: () -> Unit,
    ): OverlayAlert.Builder = populateServicePicker(
        builder, ctx,
        ServicePickerWords(
            title = R.string.translation_picker_title,
            message = R.string.translation_picker_message,
            onlineHeader = R.string.settings_header_online_translations,
            offlineHeader = R.string.settings_header_offline_translations,
        ),
        listed = TranslationBackendRegistry.setUpFor(
            SourceLanguageProfiles[langContext.sourceLangId].translationCode,
            langContext.targetLang,
        ),
        creditName = creditName,
        onPick = onPick,
        onOpenServices = onOpenServices,
    )
}

/** What a picker of one service says of itself ([populateServicePicker]):
 *  its title, the message that says what a pick does, and the headers over
 *  its online and its offline services. */
internal class ServicePickerWords(
    val title: Int,
    val message: Int,
    val onlineHeader: Int,
    val offlineHeader: Int,
)

/**
 * Populates [builder] as a picker of one service for one run, which changes
 * no setting: the translation's ([TranslationPicker]) and the Ask section's
 * ([AskPicker]). One card for both, so a pick of one service reads the same
 * wherever it is offered.
 *
 * [listed] are the services to pick from, in the order they are tried: the
 * online ones under one header and the offline ones under another, a group
 * with nothing in it having no header. The message says what a pick does,
 * so the lists come right under it. Past a rule under the lists is the
 * alert's footer ([OverlayAlert.Builder.beginFooter]): the way to the
 * Translation services page, an outlined button of the services' own size
 * (a common thing to want from here, so it is a button and not a link, and
 * the rule and the outline keep it from reading as one more offline
 * service), and Cancel. In a wide window, a phone on its side, the two
 * lists stand side by side and the footer's two buttons share a row that
 * stays on screen.
 *
 * The accent marks the service named [creditName], the one credited with
 * what is on show; every button runs, that one included.
 */
internal fun populateServicePicker(
    builder: OverlayAlert.Builder,
    ctx: Context,
    words: ServicePickerWords,
    listed: List<TranslationBackend>,
    creditName: String?,
    onPick: (TranslationBackend) -> Unit,
    onOpenServices: () -> Unit,
): OverlayAlert.Builder {
    // Colours and words on the context the alert themes its own
    // ([overlayThemedContext]): an over-game surface's own context has
    // the platform's theme, where the app's colours read as 0.
    val themed = overlayThemedContext(ctx)
    builder.setTitle(themed.getString(words.title))
        .setMessage(themed.getString(words.message))
    val (online, offline) = listed.partition { it.requiresInternet }
    fun group(headerRes: Int, backends: List<TranslationBackend>) {
        if (backends.isEmpty()) return
        builder.addSectionLabel(themed.getString(headerRes))
        for (backend in backends) {
            val credited = backend.displayName == creditName
            val bg = if (credited) themed.themeColor(R.attr.ptAccent) else themed.themeColor(R.attr.ptDivider)
            val fg = if (credited) themed.themeColor(R.attr.ptAccentOn) else themed.themeColor(R.attr.ptText)
            builder.addButton(backend.displayName, bg, fg) { onPick(backend) }
        }
    }
    group(words.onlineHeader, online)
    group(words.offlineHeader, offline)
    // The lists have ended: what follows is not a choice among them.
    // First the way to add a service, give one its key or download a
    // model, an outline where the services are filled.
    builder.beginFooter()
    builder.addOutlinedButton(
        themed.getString(R.string.settings_cell_translation_services),
        R.drawable.ic_settings,
    ) { onOpenServices() }
    return builder.addCancelButton(themed.getString(R.string.btn_cancel))
}
