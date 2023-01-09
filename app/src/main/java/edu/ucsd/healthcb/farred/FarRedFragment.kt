package edu.ucsd.healthcb.farred

/*
Copyright Notice
This software is Copyright © 2XXX The Regents of the University of California. All Rights Reserved. Permission to copy, modify, and distribute this software and its documentation for educational, research and non-profit purposes, without fee, and without a written agreement is hereby granted, provided that the above copyright notice, this paragraph and the following three paragraphs appear in all copies. Permission to make commercial use of this software may be obtained by contacting:

Office of Innovation and Commercialization
9500 Gilman Drive, Mail Code 0910
University of California
La Jolla, CA 92093-0910
innovation@ucsd.edu

This software program and documentation are copyrighted by The Regents of the University of California. The software program and documentation are supplied “as is”, without any accompanying services from The Regents. The Regents does not warrant that the operation of the program will be uninterrupted or error-free. The end-user understands that the program was developed for research purposes and is advised not to rely exclusively on the program for any reason.

IN NO EVENT SHALL THE UNIVERSITY OF CALIFORNIA BE LIABLE TO ANY PARTY FOR DIRECT, INDIRECT, SPECIAL, INCIDENTAL, OR CONSEQUENTIAL DAMAGES, INCLUDING LOST PROFITS, ARISING OUT OF THE USE OF THIS SOFTWARE AND ITS DOCUMENTATION, EVEN IF THE UNIVERSITY OF CALIFORNIA HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGE. THE UNIVERSITY OF CALIFORNIA SPECIFICALLY DISCLAIMS ANY WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE. THE SOFTWARE PROVIDED HEREUNDER IS ON AN “AS IS” BASIS, AND THE UNIVERSITY OF CALIFORNIA HAS NO OBLIGATIONS TO PROVIDE MAINTENANCE, SUPPORT, UPDATES, ENHANCEMENTS, OR MODIFICATIONS.
*/

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.Camera
import android.hardware.camera2.*
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.TonemapCurve
import android.media.*
import android.os.*
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.*
import androidx.annotation.WorkerThread
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import edu.ucsd.healthcb.FirstFragment
import edu.ucsd.healthcb.Utils.EncoderWrapper
import edu.ucsd.healthcb.Utils.YuvToRgbConverter
import edu.ucsd.healthcb.Utils.getPreviewOutputSize
import edu.ucsd.healthcb.databinding.FragmentFarredBinding
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.pow

class FarRedFragment: Fragment() {
    private var _binding: FragmentFarredBinding? = null
    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    /** Detects, characterizes, and connects to a CameraDevice (used for all camera operations) */
    private val cameraManager: CameraManager by lazy {
        val context = requireContext().applicationContext
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }
    /** [CameraCharacteristics] corresponding to the provided Camera ID */
    private val characteristics: CameraCharacteristics by lazy {
        cameraManager.getCameraCharacteristics(cameraId)
    }

    /** File where the recording will be saved */
    private lateinit var outputFile: File  //by lazy { createFile(requireActivity(), "mp4") }

    /** [HandlerThread] where all camera operations run */
    private val cameraThread = HandlerThread("CameraThread").apply { start() }

    /** [Handler] corresponding to [cameraThread] */
    private val cameraHandler = Handler(cameraThread.looper)

    /** Captures frames from a [CameraDevice] for our video recording */
    private lateinit var session: CameraCaptureSession

    /** The [CameraDevice] that will be opened in this fragment */
    private lateinit var camera: CameraDevice

    /***************************************Camera Parameters *********************************/

    private lateinit var cameraDevice: CameraDevice
    private var torchOn = false
//    private val autoAE = CONTROL_AE_MODE_ON
    private val autoAE = CameraCharacteristics.CONTROL_AE_MODE_OFF

    //    private val autoAWB = CONTROL_AWB_MODE_AUTO
    private val autoAWB = CameraCharacteristics.CONTROL_AWB_MODE_OFF

    //    private val autoAF = CONTROL_AF_MODE_CONTINUOUS_VIDEO
    private val autoAF = CameraCharacteristics.CONTROL_AF_MODE_OFF

//    private var flashMode = CameraCharacteristics.FLASH_MODE_TORCH
    private var flashMode = CameraCharacteristics.FLASH_MODE_OFF

    private val toneMap: TonemapCurve = TonemapCurve(floatArrayOf(0.0F, 0.0F, 1.0F, 1.0F), floatArrayOf(0.0F, 0.0F, 1.0F, 1.0F), floatArrayOf(0.0F, 0.0F, 1.0F, 1.0F))

    private var focalRatio: Float = 1.0f
    private var boost = 100 //[100, 3199]
    private val frameDuration:Long = 33333333
    //    private val frameDuration:Long = 30333333
//    private val exposureTime:Long = 33338756 // [13611, 10170373248]
    private var exposureTimeMs: Double = 20.0
    private var exposureTime:Long = (exposureTimeMs * 10.0.pow(6)).toLong() // [13611, 10170373248]

    //    private var sensitivity = 55 // [55, 7111], default = 444
    private var sensitivity = 95
    //    private var sensitivity = 5000
    private var imageWidth = 640
    private var imageHeight = 480

    private val forceplotPadding = 1

    private var exposureMetadata = exposureTime
    private var sensitivityMetadata = sensitivity
    private var tonemapMetadata = toneMap
    private var focusDistanceMetadata = 0.0f
    //
//    private val colorCorrectinGain = RggbChannelVector(1.867188f, 1.0f, 1.0f, 2.0625f)
    private val colorCorrectinGain = RggbChannelVector(2.0f, 1.0f, 1.0f, 2.0f)
    //    private val colorCorrectionTransform =  intArrayOf(
//            192, 128, -89, 128, 24, 128,
//            -23, 128, 152, 128, -2, 128,
//            18, 128, -123, 128, 233, 128
//    )
    private val colorCorrectionTransform =  intArrayOf(
        1, 1, 0, 1, 0, 1,
        0, 1, 1, 1, 0, 1,
        0, 1, 0, 1, 1, 1
    )
    private val colorCorrectionMode = 0

    private var ae_lock = false
    private var awb_lock = false

    private var countDownJob: Job? = null

    /************************************* Preview Image Setup ***********************************/

    private val analysisThread = HandlerThread("AnalysisThread",
        Process.THREAD_PRIORITY_URGENT_DISPLAY
    ).apply { start() }
    private val analysisHandler = Handler(analysisThread.looper) //used for onimage available listener if needed

    private val mediaDir: String =
        "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)}/Camera"


    private var recorder: MediaRecorder? = null

    private fun setupRecorder(surface: Surface, expName: String) {
        recorder?.release()
        outputFile = createFile(requireContext(),"mp4")
        recorder = MediaRecorder().apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncodingBitRate(RECORDER_VIDEO_BITRATE)
            if (cameraFps > 0) setVideoFrameRate(cameraFps)
            setOutputFile(outputFile?.absolutePath)

            setVideoSize(cameraWidth, cameraHeight)
            setVideoEncoder(MediaRecorder.VideoEncoder.HEVC)
            setInputSurface(surface)
            prepare() //for recorder prepared failed, check for valid width, height, fps, cameraid
            start()
        }
    }

    /** Creates a [MediaRecorder] instance using the provided [Surface] as input */
    private fun createRecorder(surface: Surface) = MediaRecorder().apply {
//        setAudioSource(MediaRecorder.AudioSource.MIC)
        setVideoSource(MediaRecorder.VideoSource.SURFACE)
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setVideoFrameRate(cameraFps)
        outputFile = createFile(requireContext(),"mp4")//setupOutputFile("")
        setOutputFile(outputFile.absolutePath)
        setVideoEncodingBitRate(RECORDER_VIDEO_BITRATE)
        if (cameraFps > 0) setVideoFrameRate(cameraFps)
        setVideoSize(cameraWidth, cameraHeight)
        setVideoEncoder(MediaRecorder.VideoEncoder.HEVC)
//        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        setInputSurface(surface)
    }

    /**
     * Setup a persistent [Surface] for the recorder so we can use it as an output target for the
     * camera session without preparing the recorder
     */
    private val recorderSurface: Surface by lazy {

        // Get a persistent Surface from MediaCodec, don't forget to release when done
        val surface = MediaCodec.createPersistentInputSurface()

        // Prepare and release a dummy MediaRecorder with our new surface
        // Required to allocate an appropriately sized buffer before passing the Surface as the
        //  output target to the capture session
        createRecorder(surface).apply {
            prepare()
            release()
        }

        surface
    }

    private var cameraId = "0"
    private var cameraWidth = 640
    private var cameraHeight = 480
    private var cameraFps = 30
    private val imageFormat = ImageFormat.YUV_420_888

    /** Requests used for preview only in the [CameraCaptureSession] */
    private val previewRequest: CaptureRequest by lazy {
        // Capture request holds references to target surfaces
        session.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            // Add the preview surface target
            this[CaptureRequest.FLASH_MODE] = flashMode
            this[CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE] =
                CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_OFF
//            this[CaptureRequest.LENS_APERTURE] = characteristics[LENS_INFO_AVAILABLE_APERTURES]!![0]
            Log.d("rt", characteristics[CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE]!!.toString())
            Log.d("rt", characteristics[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE]!!.toString())
            Log.d("rt", characteristics[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE]!!.toString())
            this[CaptureRequest.CONTROL_AE_MODE] = autoAE
            this[CaptureRequest.CONTROL_AWB_MODE] = autoAWB
            this[CaptureRequest.CONTROL_AE_LOCK] = ae_lock
            this[CaptureRequest.CONTROL_AWB_LOCK] = awb_lock
            this[CaptureRequest.SENSOR_SENSITIVITY] = sensitivity
            this[CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST] = boost
            this[CaptureRequest.SENSOR_FRAME_DURATION] = frameDuration
            this[CaptureRequest.SENSOR_EXPOSURE_TIME] = exposureTime
            this[CaptureRequest.COLOR_CORRECTION_GAINS] = colorCorrectinGain
            this[CaptureRequest.COLOR_CORRECTION_TRANSFORM] = ColorSpaceTransform(colorCorrectionTransform)
            this[CaptureRequest.COLOR_CORRECTION_MODE] = colorCorrectionMode
            this[CaptureRequest.TONEMAP_MODE] = CameraCharacteristics.TONEMAP_MODE_CONTRAST_CURVE
            this[CaptureRequest.TONEMAP_CURVE] = toneMap
            this[CaptureRequest.CONTROL_AF_MODE] = autoAF
            this[CaptureRequest.LENS_FOCUS_DISTANCE] =
                focalRatio * characteristics[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE]!!
            addTarget(binding.viewFinder.holder.surface)
        }.build()
    }

    /** Requests used for preview and recording in the [CameraCaptureSession] */
    private val recordRequest: CaptureRequest by lazy {
        // Capture request holds references to target surfaces
        session.device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            // Add the preview and recording surface targets
            this[CaptureRequest.FLASH_MODE] = flashMode
            this[CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE] =
                CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_OFF
            this[CaptureRequest.CONTROL_AE_MODE] = autoAE
            this[CaptureRequest.CONTROL_AWB_MODE] = autoAWB
            this[CaptureRequest.CONTROL_AE_LOCK] = ae_lock
            this[CaptureRequest.CONTROL_AWB_LOCK] = awb_lock
            this[CaptureRequest.SENSOR_SENSITIVITY] = sensitivity
            this[CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST] = boost
            this[CaptureRequest.SENSOR_FRAME_DURATION] = frameDuration
            this[CaptureRequest.SENSOR_EXPOSURE_TIME] = exposureTime
            this[CaptureRequest.COLOR_CORRECTION_GAINS] = colorCorrectinGain
            this[CaptureRequest.COLOR_CORRECTION_TRANSFORM] = ColorSpaceTransform(colorCorrectionTransform)
            this[CaptureRequest.COLOR_CORRECTION_MODE] = colorCorrectionMode
            this[CaptureRequest.TONEMAP_MODE] = CameraCharacteristics.TONEMAP_MODE_CONTRAST_CURVE
            this[CaptureRequest.TONEMAP_CURVE] = toneMap
            this[CaptureRequest.CONTROL_AF_MODE] = autoAF
            this[CaptureRequest.LENS_FOCUS_DISTANCE] =
                focalRatio * characteristics[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE]!!
            addTarget(binding.viewFinder.holder.surface)
            addTarget(recorderSurface)
            // Sets user requested FPS for all targets
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(cameraFps, cameraFps))
        }.build()
    }

    @Volatile
    private var recordingStarted = false

    @Volatile
    private var recordingComplete = false





    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {

        _binding = FragmentFarredBinding.inflate(inflater, container, false)
        return binding.root

    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.viewFinder.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceDestroyed(holder: SurfaceHolder) {
            }
            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int) = Unit

            override fun surfaceCreated(holder: SurfaceHolder) {

                // Selects appropriate preview size and configures view finder
                val previewSize = getPreviewOutputSize(
                    binding.viewFinder.display, characteristics, SurfaceHolder::class.java)
                Log.d(TAG, "View finder size: ${binding.viewFinder.width} x ${binding.viewFinder.height}")
                Log.d(TAG, "Selected preview size: $previewSize")
                // To ensure that size is set, initialize camera in the view's thread
                binding.viewFinder.post {
                    initializeCamera()
                    Log.d("preview", "initialized camera")
                }
            }
        })
    }

    private fun isCurrentlyRecording(): Boolean {
        return recordingStarted && !recordingComplete
    }

    /**
     * creates list of all camera potions and chooses highest framerate with highest FPS
     */
    private fun setCameraParameters(){
        //options of all cameras
        val cameraList = enumerateVideoCameras(cameraManager)
        for (item in cameraList){
            if (item.orientation == "Back" && item.size.height>cameraHeight
                && item.size.width>cameraWidth && item.fps>cameraFps){
                cameraHeight=item.size.height
                cameraWidth=item.size.width
                cameraFps=item.fps
                cameraId=item.cameraId

            }
        }
    }

    /**
     * Begin all camera operations in a coroutine in the main thread. This function:
     * - Opens the camera
     * - Configures the camera session
     * - Starts the preview by dispatching a repeating request
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun initializeCamera() = GlobalScope.launch(Dispatchers.Main) {

        setCameraParameters()
        // Open the selected camera
        camera = openCamera(cameraManager, cameraId, cameraHandler)
        Log.d("preview", "camera opened".plus(cameraId))
        // Creates list of Surfaces where the camera will output frames
        val targets = listOf(binding.viewFinder.holder.surface, recorderSurface)


        Log.d("preview","targets assigned")
        // Start a capture session using our open camera and list of Surfaces where frames will go
        session = createCaptureSession(camera, targets, cameraHandler)

        // This will keep sending the capture request as frequently as possible until the
        // session is torn down or session.stopRepeating() is called
//        session.setRepeatingRequest(captureRequest.build(), null, cameraHandler)
        session.setRepeatingRequest(previewRequest, null, cameraHandler)

        // Button press triggers PLR test
        binding.buttonTest.setOnClickListener {
            GlobalScope.launch(Dispatchers.IO) {
                binding.buttonTest.text="Testing"
                binding.buttonTest.isEnabled=false
                session.stopRepeating()
                setupRecorder(recorderSurface,"STEP")
                session.setRepeatingRequest(recordRequest, null, cameraHandler)
                delay(1000) //time before light on
                toggleTorch() //toggle flashlight on
                delay(2000) //time with light on
                toggleTorch() //toggle flashlight off
                delay(5000) //time after light off
                recorder?.stop()
                binding.buttonTest.text="Start Test"
                binding.buttonTest.isEnabled=true
            }
        }
    }

    private fun toggleTorch(){
            GlobalScope.launch {
//                session.stopRepeating()
                Log.d("torch", "button press")
                if (torchOn){
                    flashMode = CameraCharacteristics.FLASH_MODE_OFF
                    Log.d("torch", "flash off")
                }else{
                    flashMode = CameraCharacteristics.FLASH_MODE_TORCH
                    Log.d("torch", "flash on")
                }

//                cameraManager.setTorchMode("0",true)
                session.setRepeatingRequest(session.device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    // Add the preview and recording surface target
                    this[CaptureRequest.FLASH_MODE] = flashMode
                    this[CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE] =
                        CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_OFF
                    this[CaptureRequest.CONTROL_AE_MODE] = autoAE
                    this[CaptureRequest.CONTROL_AWB_MODE] = autoAWB
                    this[CaptureRequest.CONTROL_AE_LOCK] = ae_lock
                    this[CaptureRequest.CONTROL_AWB_LOCK] = awb_lock
                    this[CaptureRequest.SENSOR_SENSITIVITY] = sensitivity
                    this[CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST] = boost
                    this[CaptureRequest.SENSOR_FRAME_DURATION] = frameDuration
                    this[CaptureRequest.SENSOR_EXPOSURE_TIME] = exposureTime
                    this[CaptureRequest.COLOR_CORRECTION_GAINS] = colorCorrectinGain
                    this[CaptureRequest.COLOR_CORRECTION_TRANSFORM] = ColorSpaceTransform(colorCorrectionTransform)
                    this[CaptureRequest.COLOR_CORRECTION_MODE] = colorCorrectionMode
                    this[CaptureRequest.TONEMAP_MODE] = CameraCharacteristics.TONEMAP_MODE_CONTRAST_CURVE
                    this[CaptureRequest.TONEMAP_CURVE] = toneMap
                    this[CaptureRequest.CONTROL_AF_MODE] = autoAF
                    this[CaptureRequest.LENS_FOCUS_DISTANCE] =
                        focalRatio * characteristics[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE]!!
                    addTarget(binding.viewFinder.holder.surface)
                    addTarget(recorderSurface)
                    // Sets user requested FPS for all targets
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(cameraFps, cameraFps))
                }.build(), null, cameraHandler)
                torchOn=!torchOn

            }
    }
    /** Opens the camera and returns the opened device (as the result of the suspend coroutine) */
    @SuppressLint("MissingPermission")
    private suspend fun openCamera(
        manager: CameraManager,
        cameraId: String,
        handler: Handler? = null
    ): CameraDevice = suspendCancellableCoroutine { cont ->
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                cont.resume(device)
            }
            override fun onDisconnected(device: CameraDevice) {
                Log.w(TAG, "Camera $cameraId has been disconnected")
                requireActivity().finish()
            }

            override fun onError(device: CameraDevice, error: Int) {
                val msg = when(error) {
                    ERROR_CAMERA_DEVICE -> "Fatal (device)"
                    ERROR_CAMERA_DISABLED -> "Device policy"
                    ERROR_CAMERA_IN_USE -> "Camera in use"
                    ERROR_CAMERA_SERVICE -> "Fatal (service)"
                    ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                    else -> "Unknown"
                }
                val exc = RuntimeException("Camera $cameraId error: ($error) $msg")
                Log.e(TAG, exc.message, exc)
                if (cont.isActive) cont.resumeWithException(exc)
            }
        }, handler)
    }

    /**
     * Creates a [CameraCaptureSession] and returns the configured session (as the result of the
     * suspend coroutine)
     */
    private suspend fun createCaptureSession(
        device: CameraDevice,
        targets: List<Surface>,
        handler: Handler? = null
    ): CameraCaptureSession = suspendCoroutine { cont ->
        val stateCallback = object: CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)

            override fun onConfigureFailed(session: CameraCaptureSession) {
                val exc = RuntimeException("Camera ${device.id} session configuration failed")
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }

            /** Called after all captures have completed - shut down the encoder */
            /** Called after all captures have completed - shut down the encoder */
            override fun onReady(session: CameraCaptureSession) {
                if (!isCurrentlyRecording()) {
                    return
                }

                recordingComplete = true
                recorder?.stop()
            }
        }

        device.createCaptureSession(targets, stateCallback, handler)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        cameraThread.quitSafely()
        recorder?.release()
        recorderSurface.release()
    }
    companion object {
        private val TAG = FarRedFragment::class.java.simpleName

        private const val RECORDER_VIDEO_BITRATE: Int = 10_000_000
        private const val MIN_REQUIRED_RECORDING_TIME_MILLIS: Long = 1000L

        /** Creates a [File] named with the current date and time */
        private fun createFile(context: Context, extension: String): File {
            val mediaDir: String =
                "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)}/Camera"
            val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
            return File(mediaDir, "VID_${sdf.format(Date())}.$extension")
//            return File(context.filesDir, "VID_${sdf.format(Date())}.$extension")
        }
        private data class CameraInfo(
            val name: String,
            val orientation: String,
            val cameraId: String,
            val size: Size,
            val fps: Int)

        /** Converts a lens orientation enum into a human-readable string */
        private fun lensOrientationString(value: Int) = when (value) {
            CameraCharacteristics.LENS_FACING_BACK -> "Back"
            CameraCharacteristics.LENS_FACING_FRONT -> "Front"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
            else -> "Unknown"
        }
        /** Lists all video-capable cameras and supported resolution and FPS combinations */
        @SuppressLint("InlinedApi")
        private fun enumerateVideoCameras(cameraManager: CameraManager): List<FarRedFragment.Companion.CameraInfo> {
            val availableCameras: MutableList<FarRedFragment.Companion.CameraInfo> = mutableListOf()

            // Iterate over the list of cameras and add those with high speed video recording
            //  capability to our output. This function only returns those cameras that declare
            //  constrained high speed video recording, but some cameras may be capable of doing
            //  unconstrained video recording with high enough FPS for some use cases and they will
            //  not necessarily declare constrained high speed video capability.
            cameraManager.cameraIdList.forEach { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val orientation = FarRedFragment.lensOrientationString(
                    characteristics.get(CameraCharacteristics.LENS_FACING)!!
                )

                // Query the available capabilities and output formats
                val capabilities = characteristics.get(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)!!
                val cameraConfig = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!

                // Return cameras that declare to be backward compatible
                if (capabilities.contains(
                        CameraCharacteristics
                            .REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) {
                    // Recording should always be done in the most efficient format, which is
                    //  the format native to the camera framework
                    val targetClass = MediaRecorder::class.java

                    // For each size, list the expected FPS
                    cameraConfig.getOutputSizes(targetClass).forEach { size ->
                        // Get the number of seconds that each frame will take to process
                        val secondsPerFrame =
                            cameraConfig.getOutputMinFrameDuration(targetClass, size) /
                                    1_000_000_000.0
                        // Compute the frames per second to let user select a configuration
                        val fps = if (secondsPerFrame > 0) (1.0 / secondsPerFrame).toInt() else 0
                        val fpsLabel = if (fps > 0) "$fps" else "N/A"
                        availableCameras.add(
                            FarRedFragment.Companion.CameraInfo(
                                "$orientation ($id) $size $fpsLabel FPS", orientation, id, size, fps
                            )
                        )
                    }
                }
            }

            return availableCameras
        }

    }
}
