package edu.ucsd.healthcb.farred

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
import android.view.*
import androidx.annotation.WorkerThread
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
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

    private val args: FarRedFragmentArgs by navArgs()

    /** Detects, characterizes, and connects to a CameraDevice (used for all camera operations) */
    private val cameraManager: CameraManager by lazy {
        val context = requireContext().applicationContext
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }
    /** [CameraCharacteristics] corresponding to the provided Camera ID */
    private val characteristics: CameraCharacteristics by lazy {
        cameraManager.getCameraCharacteristics(args.cameraId)
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
            if (cameraFps > 0) setVideoFrameRate(args.fps)
            setOutputFile(outputFile?.absolutePath)

            setVideoSize(args.width, args.height)
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
        setVideoFrameRate(args.fps)
        outputFile = createFile(requireContext(),"mp4")//setupOutputFile("")
        setOutputFile(outputFile.absolutePath)
        setVideoEncodingBitRate(RECORDER_VIDEO_BITRATE)
        if (cameraFps > 0) setVideoFrameRate(args.fps)
        setVideoSize(args.width, args.height)
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

//    private val cameraId = "0"
    private val cameraWidth = 640
    private val cameraHeight = 480
    private val cameraFps = 30
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
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(args.fps, args.fps))
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
     * Begin all camera operations in a coroutine in the main thread. This function:
     * - Opens the camera
     * - Configures the camera session
     * - Starts the preview by dispatching a repeating request
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun initializeCamera() = GlobalScope.launch(Dispatchers.Main) {

        // Open the selected camera
        camera = openCamera(cameraManager, args.cameraId, cameraHandler)
        Log.d("preview", "camera opened".plus(args.cameraId))
        // Creates list of Surfaces where the camera will output frames
        val targets = listOf(binding.viewFinder.holder.surface, recorderSurface)


        Log.d("preview","targets assigned")
        // Start a capture session using our open camera and list of Surfaces where frames will go
        session = createCaptureSession(camera, targets, cameraHandler)

        // This will keep sending the capture request as frequently as possible until the
        // session is torn down or session.stopRepeating() is called
//        session.setRepeatingRequest(captureRequest.build(), null, cameraHandler)
        session.setRepeatingRequest(previewRequest, null, cameraHandler)

        binding.buttonTest.setOnClickListener {
            GlobalScope.launch(Dispatchers.IO) {
                session.stopRepeating()
                setupRecorder(recorderSurface,"STEP")
                session.setRepeatingRequest(recordRequest, null, cameraHandler)
                delay(1000)
                toggleTorch()
                delay(2000)
                toggleTorch()
                delay(5000)
                recorder?.stop()
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
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(args.fps, args.fps))
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

    }
}
