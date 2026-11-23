package com.playtranslate.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.playtranslate.CaptureSession
import com.playtranslate.OverlayMode
import com.playtranslate.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Auto Copy to Clipboard ([Prefs.autoCopyOriginal]): the original text of
 * [session] goes to the clipboard by itself when the capture reads it, for a
 * paste into another app or a clipboard reader on another machine.
 *
 * One listen of the capture, started by the surface that takes it and run
 * in this scope, which must go on while that surface is in the background:
 * the moment is the capture's, not a page's. A page's own collection of its
 * capture stops while the page is hidden and starts over, from the capture's
 * last state, when the page comes back. A copy made from there was missing
 * when the user had already switched to the app they meant to paste into,
 * and then came late, over whatever they had copied since. A listen from the
 * capture's start that is never restarted meets the text once
 * ([captureOriginalToRead]) and ends there, or with a capture that ends
 * without one. Every session reaches an end, one that another capture
 * replaced included (CaptureService's attachCancellationTerminal), so the
 * listen always ends.
 *
 * Which captures: the ones of the screen (the in-app page, the sheet over
 * the game, the full-screen page, the detached window's page) and the
 * Camera tool's snapshots; a re-read of one is a capture of its own and
 * copies its new text. Not an imported file's pages and not
 * select-to-translate, whose sheets start no listen
 * ([CaptureResultOverlay.copiesCaptureOriginal]), and not a capture up in a
 * mode of its own ([modeOverride], tap-to-lookup's Definitions), which is up
 * to look words up. Auto-translate's and hold-to-preview's results, an edit
 * and a History entry are no capture session's, so nothing listens for them.
 *
 * No toast of ours: from Android 13 the system confirms every copy itself,
 * and an app cannot turn that off. The clipboard service takes a write from
 * an app that is not in front, and drops one it refuses without throwing
 * (AOSP's ClipboardService).
 */
internal fun CoroutineScope.copyOriginalWhenRead(
    ctx: Context,
    session: CaptureSession,
    modeOverride: () -> OverlayMode? = { null },
): Job = launch {
    val (previous, state) = session.stateWithPrevious.first { (previous, state) ->
        state.isTerminal || captureOriginalToRead(previous, state, modeOverride()) != null
    }
    val text = captureOriginalToRead(previous, state, modeOverride()) ?: return@launch
    if (text.isBlank() || !Prefs(ctx).autoCopyOriginal) return@launch
    ctx.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("PlayTranslate", text))
}
