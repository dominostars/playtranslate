package com.playtranslate.camera

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log

/**
 * Runtime truth for "does this device have a back camera?".
 *
 * The static FEATURE_CAMERA declaration lies on handheld ROMs built from
 * phone BSPs — the AYN Thor declares ten camera feature flags while the
 * camera service enumerates zero devices — so every camera gate (the Tools
 * cell, the activity's pre-permission check) keys on the service's device
 * list instead: the same source of truth the CameraX provider check consults
 * later, so all three layers agree. Permission-free, a few binder calls.
 */
object CameraAvailability {

    private const val TAG = "CameraAvailability"

    fun hasBackCamera(context: Context): Boolean {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return false
        return try {
            manager.cameraIdList.any { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            }
        } catch (e: Exception) {
            // Camera service unavailable or wedged: fall back to the ROM's
            // declaration rather than hiding a real camera behind a
            // transient service failure.
            Log.w(TAG, "camera enumeration failed; falling back to the feature flag", e)
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA)
        }
    }
}
