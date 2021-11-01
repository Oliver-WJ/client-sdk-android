package org.webrtc

import android.hardware.camera2.CameraManager

/**
 * A helper to access package-protected methods used in [Camera2Session]
 * @suppress
 */
internal class Camera2Helper {
    companion object {
        fun findClosestCaptureFormat(
            cameraManager: CameraManager,
            cameraId: String?,
            width: Int,
            height: Int
        ): Size {
            val sizes = Camera2Enumerator.getSupportedFormats(cameraManager, cameraId)
                ?.map { Size(it.width, it.height) }
                ?: emptyList()
            return CameraEnumerationAndroid.getClosestSupportedSize(sizes, width, height)
        }
    }
}