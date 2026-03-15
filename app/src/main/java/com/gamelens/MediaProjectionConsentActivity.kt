package com.gamelens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Fully transparent Activity that exists only to request MediaProjection
 * consent. By being transparent, the game stays visible behind the system
 * consent dialog, so on API 34+ the user can select the game's task for
 * capture (rather than our app).
 *
 * After consent is granted (or denied), this Activity finishes itself.
 */
class MediaProjectionConsentActivity : AppCompatActivity() {

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            CaptureService.instance?.setMediaProjection(result.resultCode, result.data!!)
        }
        // Notify the caller and close
        onConsentResult?.invoke(result.resultCode == RESULT_OK && result.data != null)
        onConsentResult = null
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        launcher.launch(mgr.createScreenCaptureIntent())
    }

    companion object {
        /** Called after consent with true (granted) or false (denied). */
        var onConsentResult: ((Boolean) -> Unit)? = null

        fun launch(context: Context, onResult: (Boolean) -> Unit) {
            onConsentResult = onResult
            val intent = Intent(context, MediaProjectionConsentActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
