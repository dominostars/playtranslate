package com.playtranslate.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import com.playtranslate.R

/** A long press's copy of a text on show: the Original's, the
 *  Translation's ([TranslationSectionBinder]), an Ask answer's
 *  ([AskSectionBinder]). The text goes onto the clipboard, then "Copied". */
internal fun copyToClipboard(ctx: Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("PlayTranslate", text))
    Toast.makeText(ctx, ctx.getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
}
