package com.playtranslate.translation

import android.content.Context
import com.playtranslate.R
import com.playtranslate.translation.llm.OnDeviceLlmBackend
import com.playtranslate.translation.llm.StatusStringIds
import com.playtranslate.translation.hymt.HyMtModel
import com.playtranslate.translation.translategemma.PromptStyle

class HyMtBackend(
    context: Context,
    enabledProvider: () -> Boolean,
) : OnDeviceLlmBackend(context, enabledProvider) {

    override val id: BackendId = "hymt"
    override val displayName: String = context.getString(R.string.hymt_display_name)
    override val priority: Int = PRIORITY
    override val quality: BackendQuality = BackendQuality.Okay
    override val speed: BackendSpeed = BackendSpeed.QuiteSlow
    override val modelHelper = HyMtModel
    override val promptStyle = PromptStyle.StandardChat

    override val availMemFloorBytes: Long = 1_000_000_000L
    override val totalMemFloorBytes: Long = 4_000_000_000L

    override val statusStringIds = StatusStringIds(
        notDownloaded = R.string.hymt_status_not_downloaded,
        disabled = R.string.hymt_status_downloaded_disabled,
        ready = R.string.hymt_status_ready,
    )

    companion object {
        const val PRIORITY = 28
    }
}
