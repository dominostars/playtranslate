package com.playtranslate.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Translucent trampoline for the RECORD_AUDIO runtime grant, launched from
 * overlay surfaces — the floating workspace's audio picker page, whose
 * game-audio enable switch may need the permission mid-flow. An overlay
 * window can't run a permission request itself (the standing doctrine: a
 * surface must not offer a verb it can't perform — this trampoline IS how
 * the workspace performs it), so the workspace PARKS its window under this
 * activity while the system dialog is up and un-parks from [resultGate].
 *
 * Activity hosts never come here: they run their own permission contract
 * ([AudioSourcePickerActivity]'s launcher).
 */
class RecordAudioPermissionActivity : ComponentActivity() {

    private var granted = false

    private val requestRecordAudio =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { g ->
            granted = g
            finish()
        }

    /** One-shot [resultGate] delivery — finish() covers every close path;
     *  the onDestroy(isFinishing) call backs up task-swipe kills. NOT fired
     *  on configuration recreations (isFinishing false). */
    private fun deliverGateResult() {
        val gate = resultGate ?: return
        resultGate = null
        gate(granted)
    }

    override fun finish() {
        deliverGateResult()
        super.finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreation while the system dialog is up is recovered by the
        // registered launcher re-delivering its result.
        if (savedInstanceState != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            granted = true
            finish()
            return
        }
        requestRecordAudio.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onDestroy() {
        if (isFinishing) deliverGateResult()
        super.onDestroy()
    }

    companion object {
        /** One-shot result hook, invoked exactly once on the main thread as
         *  the trampoline finishes. Cleared on delivery AND overwritten by
         *  the next launch, so a stale hook can never receive a later
         *  grant's result. */
        var resultGate: ((granted: Boolean) -> Unit)? = null
    }
}
