package com.playtranslate.ui

import android.content.Context
import com.playtranslate.PanelState
import com.playtranslate.RegionEntry
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageProfiles
import java.util.Locale

/**
 * What a results page shows for a state of the service's background stream
 * ([com.playtranslate.CaptureService.panelState]: auto-translate's results,
 * and a hold-to-preview's). One mapping for the pages that follow that
 * stream, the app's own (MainActivity) and the detached window's
 * ([DetachedPage.followLive]), so the two cannot come to say different
 * things about the same state.
 *
 * [PanelState.Idle] is the stream's initial and cleared value and shows
 * nothing: a host that wants its idle prompt shows it itself. Whether a
 * state is this host's to show at all (the app's page takes Searching and
 * NoText only while auto-translate runs; the window's page follows the
 * stream only then) is the host's own gate, ahead of this.
 */
fun TranslationResultViewModel.showPanelState(
    state: PanelState,
    appCtx: Context,
    searching: () -> String,
) {
    when (state) {
        PanelState.Idle -> Unit
        PanelState.Searching -> showStatus(searching())
        // A cycle looked and found nothing: the status a capture's no-text
        // shows (tappable source language, OCR gear), with no pinned frame,
        // since the loop is what acts on a switch of OCR tool.
        is PanelState.NoText -> showStatus(state.message, ocrProvenance = state.ocrProvenance)
        is PanelState.Result -> displayServiceResult(state.result, appCtx)
        is PanelState.Error -> showError(state.message)
    }
}

/** The status a page shows from an auto-translate start until its first
 *  cycle lands: "Searching for X in the Y area". */
fun liveSearchingStatus(ctx: Context, sourceLang: SourceLangId, region: RegionEntry): String {
    val code = SourceLanguageProfiles[sourceLang].translationCode
    val lang = Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault())
        .replaceFirstChar { it.uppercase(Locale.getDefault()) }
    return "Searching for $lang in the \"${region.displayName(ctx)}\" area"
}
