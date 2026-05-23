package com.playtranslate.mnn

import android.content.Context
import com.playtranslate.mnn.internal.MnnChatImpl

/**
 * Public entry point for the :mnn module. Mirrors :llama's [com.arm.aichat.AiChat].
 *
 * The translator layer (see `app/src/main/java/com/playtranslate/translation/mnn/MnnTranslator.kt`)
 * is the only intended caller; everything else routes through the
 * [TranslationBackendRegistry] waterfall and never sees this object directly.
 */
object MnnChat {
    /** Get (or lazily construct) the process-wide [InferenceEngine] singleton. */
    fun getInferenceEngine(context: Context): InferenceEngine = MnnChatImpl.getInstance(context)
}
