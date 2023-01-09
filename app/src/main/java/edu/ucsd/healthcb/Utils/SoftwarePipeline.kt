/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.ucsd.healthcb.Utils

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.util.Log
import android.util.Range
import android.view.Surface
import android.view.SurfaceView
import edu.ucsd.healthcb.Utils.AutoFitSurfaceView
import edu.ucsd.healthcb.Utils.EncoderWrapper

class SoftwarePipeline(width: Int, height: Int, fps: Int, filterOn: Boolean,
                       characteristics: CameraCharacteristics, encoder: EncoderWrapper,
                       viewFinder: SurfaceView) : Pipeline(width, height, fps, filterOn,
                characteristics, encoder, viewFinder) {
    private val imageFormat = ImageFormat.YUV_420_888
    //    private val imageFormat = ImageFormat.YUV_444_888
//    private val imageFormat = ImageFormat.RAW_SENSOR

    override fun createPreviewRequest(session: CameraCaptureSession,
            previewStabilization: Boolean): CaptureRequest? {
        // Capture request holds references to target surfaces
        return session.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            // Add the preview surface target
            addTarget(viewFinder.holder.surface)
            Log.d("preview", "software pipeline previewRequest")
            if (previewStabilization) {
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        2)
            }
        }.build()
    }

    override fun createRecordRequest(session: CameraCaptureSession,
            previewStabilization: Boolean): CaptureRequest {
        // Capture request holds references to target surfaces
        return session.device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            // Add the preview and recording surface targets
            addTarget(viewFinder.holder.surface)
//            addTarget(encoder.getInputSurface())

            // Sets user requested FPS for all targets
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))

            if (previewStabilization) {
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        2)
            }
        }.build()
    }

    override fun getTargets(): List<Surface> {
        return listOf(viewFinder.holder.surface)//, encoder.getInputSurface())
    }
}