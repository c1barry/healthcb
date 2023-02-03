package edu.ucsd.healthcb
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
import android.Manifest
import android.Manifest.permission.CAMERA
import android.Manifest.permission.VIBRATE
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues.TAG
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.*
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.StreamConfigurationMap
import android.hardware.camera2.params.TonemapCurve
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaRecorder
import android.os.*
import android.util.Log
import android.util.Range
import android.view.*
import android.widget.*
import androidx.annotation.RequiresApi
import androidx.annotation.WorkerThread
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContentProviderCompat.requireContext
import androidx.lifecycle.lifecycleScope
import com.androidplot.util.Redrawer
import com.androidplot.xy.*
import com.github.psambit9791.jdsp.filter.Butterworth
import com.github.psambit9791.jdsp.misc.UtilMethods
import com.jcraft.jsch.*
import edu.ucsd.healthcb.Utils.YuvToRgbConverter
import kotlinx.coroutines.*
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc
import java.io.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.*
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt


class VibroBPActivity : AppCompatActivity(), SensorEventListener {

    /************************************Sensor and Plotting Related**********************************/
    private lateinit var CalibrationButton: Button
    private lateinit var isoButton: Button
    private lateinit var sensorManager: SensorManager
    private var mAccelerometer: Sensor? = null
    private var mGyro: Sensor? = null
    private var mVibrator: Vibrator? = null
    private var isVibrating = false
    private var isScreenLocked = false
    private var isRecording = false
//    private lateinit var vibrationManager: VibratorManager
    private var xaccelMeasurements: FloatArray = floatArrayOf()
    private var yaccelMeasurements: FloatArray = floatArrayOf()
    private var zaccelMeasurements: FloatArray = floatArrayOf()
    private lateinit var root: File
    private lateinit var ppgroot:File
    private lateinit var gyroroot: File
    private lateinit var writer: FileWriter
    private lateinit var gyrowriter: FileWriter
    private lateinit var ppgwriter: FileWriter
    private val REQUEST_EXTERNAL_STORAGE = 1
    @RequiresApi(Build.VERSION_CODES.R)
    private val PERMISSIONS_STORAGE = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.MANAGE_EXTERNAL_STORAGE,
        Manifest.permission.MANAGE_DOCUMENTS
    )
    private val xformatter by lazy { LineAndPointFormatter(Color.RED, null, null, null) }
    private val guideformatter by lazy {LineAndPointFormatter(Color.GREEN, null, null, null)}
    private val yformatter by lazy { LineAndPointFormatter(Color.GREEN, null, null, null) }
    private val zformatter by lazy { LineAndPointFormatter(Color.BLUE, null, null, null) }
    private val xRange:Int = 10*4
    private val ppgRange:Int=90
    private var xaccelSeries = accelModel(xRange, xRange)
    private var yaccelSeries = accelModel(xRange, xRange)
    private var zaccelSeries = accelModel(xRange, xRange)
    private var forceaccelSeries = accelModel(xRange, xRange)
    private var forceGuideSeries = accelModel(xRange, xRange)
    private var xgyroSeries = accelModel(xRange, xRange)
    private var ygyroSeries = accelModel(xRange, xRange)
    private var zgyroSeries = accelModel(xRange, xRange)
    private var ppgSeries = ppgModel(ppgRange, ppgRange)
    private var calibrationMode = false
    private var accelCounter = 0
    private var gyroCounter = 0
    private var ppgCounter = 0
    private var accelMax = 0.0
    private var accelMin = 0.0
    private var currentChannel = 0
    private var forceGuideVal = 0.0

    /***************************************** Filtering Related *****************************/
    private var bufferSize = 15
    private var dataBufferQueue: Queue<Double> = LinkedList<Double>()
    private var useFilter = true
    private var useFilter2 = false
    private var flt: Butterworth = Butterworth(30.0)
    private var accelBufferSize = 150
    private var xaccelBufferQueue: Queue<Double> = LinkedList<Double>()
    private var yaccelBufferQueue: Queue<Double> = LinkedList<Double>()
    private var zaccelBufferQueue: Queue<Double> = LinkedList<Double>()
    private var gyroBufferSize = 150
    private var xgyroBufferQueue: Queue<Double> = LinkedList<Double>()
    private var ygyroBufferQueue: Queue<Double> = LinkedList<Double>()
    private var zgyroBufferQueue: Queue<Double> = LinkedList<Double>()
    private var pixel4freq = 423.0
    private var a53freq = 522.0
    private var filterFreqMap = mapOf<String, Double>("Pixel 4" to pixel4freq, "SM-A536U1" to a53freq, "Pixel 7 Pro" to 500.0, "moto g pure" to 200.0)
    private lateinit var accelflt: Butterworth
    private var fltfrq = 0.001
    private var accelfltfrq: Double = 200.0

    /*********************************** Test/Force Related ********************************************/
    private var seconds = 40
    private lateinit var vibe: VibrationEffect
    private var ISOset = true
    private var correctISO = false
    private var pixel4accelparams = doubleArrayOf( 107.0, 28.0, -20.0, 5.0,  0.0,  0.0,0.0,0.0)
    private var pixel4params = doubleArrayOf( 64.99246475124983, -4.39988817e+00, -6.80585708e+01,  2.87924786e+01,  9.15000456e+03,
            -2.00775287e+03, -1.89773635e+03)

    private var a53accelparams = doubleArrayOf( 226.6831654419109, -108.57244479, -104.67679714,  0.0,  0.0,0.0,0.0)
//    private var pixel4params = doubleArrayOf( 470.51, -13.06011158,   -79.6883385,    -11.09071467,  7449.08359999, -1903.76515038, -1640.82724184)
    private var a53params = doubleArrayOf(1200.6520012735218, -93.14648391,  -206.7670984,    -98.77187847,  -245.77843109, -1209.21048107,  1541.51684023)
    private lateinit var forceParam:DoubleArray
    private val accel_only=true
    private var forceParamMap = mapOf<String, DoubleArray>("Pixel 4" to pixel4params, "SM-A536U1" to a53params)
    private var accelForceParamMap = mapOf<String, DoubleArray>("Pixel 4" to pixel4accelparams, "SM-A536U1" to a53accelparams)
    private var xgyro = 0.0
    private var ygyro = 0.0
    private var zgyro = 0.0
    private var pixel4camlocation = 585
    private var a53camlocation =0
    private var cameraLocationMap = mapOf<String, Int>("Pixel 4" to pixel4camlocation, "SM-A536U1" to a53camlocation)
    private var fingerSize = 0

    /********************************** File Saving ***********************************/
    private var filestoupload: MutableList<File> = mutableListOf()
    private var userIDnumber = 0
    private var trialNumber = 0
    private var pulse = 0
    private var sys=0
    private var dia=0

    /** Creates a [MediaRecorder] instance using the provided [Surface] as input */
    private fun createRecorder(surface: Surface) = MediaRecorder().apply {
//        setAudioSource(MediaRecorder.AudioSource.MIC)
        setVideoSource(MediaRecorder.VideoSource.SURFACE)
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        outputFile = setupOutputFile("")
        setOutputFile(outputFile?.absolutePath)
        setVideoEncodingBitRate(RECORDER_VIDEO_BITRATE)
        setVideoFrameRate(camera_fps)
        setVideoSize(img_height, img_width)
        setVideoEncoder(MediaRecorder.VideoEncoder.HEVC)
//        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        setInputSurface(surface)
    }
    /************************************Thread Handler Related**********************************/
    /** [HandlerThread] where all camera operations run */
    private val cameraThread = HandlerThread("CameraThread", -19).apply { start() }

    /** [Handler] corresponding to [cameraThread] */
    private val cameraHandler = Handler(cameraThread.looper)

    private val analysisThread = HandlerThread("AnalysisThread",
        Process.THREAD_PRIORITY_URGENT_DISPLAY
    ).apply { start() }
    private val analysisHandler = Handler(analysisThread.looper)


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
//            prepare()
//            release()
        }

        surface
    }

    /** Saves the video recording */
    private var recorder: MediaRecorder? = null
    private val mediaDir: String =
        "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)}/Camera"
    private val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
    //    private var fileName = sdf.format(Date())
    private var sbjTrlName = "sub_0_trl_0"

    private var recording: Boolean = false

    private val imageFormat = ImageFormat.YUV_420_888
    private val img_width = 1920
    private val img_height = 1080
    private var minThresh = 1.0
    private var maxThresh = 256.0

    //    private val imageFormat = ImageFormat.YUV_444_888
//    private val imageFormat = ImageFormat.RAW_SENSOR
    private val previewDataImageReader: ImageReader by lazy {
        ImageReader.newInstance(img_width, img_height, imageFormat, 30).apply {
            setOnImageAvailableListener(OnPreviewDataAvailableListener(), analysisHandler)
        }
    }

    private val previewDataSurface: Surface by lazy { previewDataImageReader.surface }

    private inner class OnPreviewDataAvailableListener : ImageReader.OnImageAvailableListener {
        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()    // Rewind the buffer to zero
            val data = ByteArray(remaining())
            get(data)   // Copy the buffer into a byte array
            return data // Return the byte array
        }

        private fun ManualGetCenterBrightnessOpenCVMasked(redMat: Mat, minThresh: Double, maxThresh: Double, ppgSize: Int, forceSize: Int, display: Boolean, returnForce: Boolean): Triple<Double, Double, Double> {

//            val reverse_thresh = 254.0
            val reverse_thresh = maxThresh
            val thresholded_img = Mat(redMat.width(), redMat.height(), CvType.CV_8UC1)
            val reverse_thresholded_img = Mat(redMat.width(), redMat.height(), CvType.CV_8UC1)
            val mask = Mat(redMat.width(), redMat.height(), CvType.CV_8UC1)
            val masked_img = Mat(redMat.width(), redMat.height(), CvType.CV_8UC1)
            Core.bitwise_not( redMat, reverse_thresholded_img)
            Imgproc.threshold(reverse_thresholded_img, reverse_thresholded_img, 255.0 - reverse_thresh, 1.0, Imgproc.THRESH_BINARY)
            Imgproc.threshold(redMat, thresholded_img, minThresh, 1.0, Imgproc.THRESH_BINARY)
            Core.multiply(reverse_thresholded_img, thresholded_img, mask)
            Core.multiply(redMat, mask, masked_img)

//            var luma = Core.sumElems(masked_img).`val`[0] / maskArea

            val ppgRoi = Rect((imageWidth-ppgSize)/2 - direction*manualSquareXOffset, (imageHeight-ppgSize)/2 - direction*manualSquareYOffset, ppgSize, ppgSize)
            val croppedPPG = Mat(masked_img, ppgRoi)
            var forceAvg = 0.0
            if (returnForce) {
                val forceRoi = Rect(
                    (imageWidth - forceSize) / 2 - direction*manualSquareXOffset,
                    (imageHeight - forceSize) / 2 - direction*manualSquareYOffset,
                    forceSize,
                    forceSize
                )
                val croppedForce = Mat(masked_img, forceRoi)
//                forceAvg = Core.mean(croppedForce).`val`[0]
                val croppedForceMask = Mat(mask, forceRoi)
                val forceMaskArea = Core.sumElems(croppedForceMask).`val`[0]
                if (forceMaskArea != 0.0){
                    forceAvg = Core.sumElems(croppedForce).`val`[0] / forceMaskArea
                }
            }
            val croppedPPGMask = Mat(mask, ppgRoi)
            val max = Core.minMaxLoc(croppedPPG).maxVal
            val maskArea = Core.sumElems(croppedPPGMask).`val`[0]
//            Log.d("Circle", "Mask Size " + maskArea)
//            if (display) {
//                displayOpenCVImage(croppedPPG)
//            }
            var ppgAvg = 0.0
            if (maskArea != 0.0){
                ppgAvg = Core.sumElems(croppedPPG).`val`[0] / maskArea
            }
            return Triple(ppgAvg, forceAvg, max)
        }

        private fun EstimateCircleSize(redMat:Mat, minThresh: Double): Double{
            val thresholded_img = Mat(redMat.width(), redMat.height(), CvType.CV_8UC1)
            Imgproc.threshold(redMat, thresholded_img, minThresh, 1.0, Imgproc.THRESH_BINARY)
            val area = Core.sumElems(thresholded_img).`val`[0]
            return sqrt(area/3.14)
        }

        private fun saturatedCircleDetection(img: Mat): Double {

            val gray = Mat(
                manualForceSquareSize,
                manualForceSquareSize, CvType.CV_8UC1)
            val equ = Mat(
                manualForceSquareSize,
                manualForceSquareSize, CvType.CV_8UC1)
            val thresholdedImg = Mat(
                manualForceSquareSize,
                manualForceSquareSize, CvType.CV_8UC1)
            val forceRoi = Rect(
                (imageWidth - manualForceSquareSize) / 2 - direction * manualSquareXOffset,
                (imageHeight - manualForceSquareSize) / 2 - direction * manualSquareYOffset,
                manualForceSquareSize,
                manualForceSquareSize
            )
            val croppedPPG = Mat(img, forceRoi)
            //Get grayscale image
            Imgproc.cvtColor(croppedPPG, gray, Imgproc.COLOR_BGR2GRAY)
            //Histogram equalization
            Imgproc.equalizeHist(gray, equ)
            Imgproc.threshold(equ, thresholdedImg, 20.0, 255.0, Imgproc.THRESH_BINARY)
            val area = Core.sumElems(thresholdedImg).`val`[0]/255.0
//            displayOpenCVImage(thresholdedImg)
            val radius = sqrt(area / 3.14159265)
            Log.d("circle", "Area ${area}")
            Log.d("circle", "Radius $radius")
            return radius
        }

        @WorkerThread
        override fun onImageAvailable(imageReader: ImageReader) {
            val image = imageReader.acquireLatestImage()
//            MainScope().launch {
//                if(analysisOn){
//                    exposureTextView.text =
//                        "ExposureTime: ${String.format("%.3f", exposureMetadata / 1000000.0)}ms"
//                    isoValue.text = "ISO: $sensitivityMetadata"
//                    maxVal.text = "MaxVal: $currentMax"
//                    FocalDistanceValue.text = "FD: ${String.format("%.1f", focusDistanceMetadata)}"
//                    if (currentMax >= 255) {
//                        maxVal.setTextColor(Color.RED)
//                    } else {
//                        maxVal.setTextColor(Color.WHITE)
//                    }
//                }
//            }

//            val image = imageReader.acquireNextImage()
            val currentTime = System.currentTimeMillis()
            val elapsedTime = if (recording) {
                (currentTime - recordingStartTime!!) / 1000.0
            } else{0.0}

//            if (lastTimeStamp != null) {
//                val frameDuration = currentTime - lastTimeStamp!!
////                Log.d("CSV", frameDuration.toString())
//            }
//            lastTimeStamp = currentTime

//            val diffList = floatArrayOf(axDiff!!, ayDiff!!, azDiff!!)
//            avgDiff = diffList.average()
//            val gyroThreshold = 0.05
//            gyroSensor.progress = ((avgDiff!! / gyroThreshold)*100).toInt()
//            isShaking = avgDiff!! >= gyroThreshold
//            if (isShaking){
//                val progressDrawable: Drawable = gyroSensor.progressDrawable.mutate()
//                progressDrawable.setColorFilter(Color.RED, PorterDuff.Mode.SRC_IN)
//                gyroSensor.progressDrawable = progressDrawable
//
//                MainScope().launch {
//                    if (analysisOn){
//                        shakeIndicator.text = "isShaking"
//                        shakeIndicator.setTextColor(Color.RED)
//                    }
//                }
//            }else{
//                val progressDrawable: Drawable = gyroSensor.progressDrawable.mutate()
//                progressDrawable.setColorFilter(Color.GREEN, PorterDuff.Mode.SRC_IN)
//                gyroSensor.progressDrawable = progressDrawable
//
//                MainScope().launch {
//                    if(analysisOn){
//                        shakeIndicator.text = "isStable"
//                        shakeIndicator.setTextColor(Color.GREEN)
//                    }
//                }
//            }



            val converter = YuvToRgbConverter(applicationContext)

            image?.use {

                val bmp = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                if (calibrationMode){
                    ppgCounter+=1
                }
                converter.yuvToRgb(image, bmp)

                val rgb_list: List<Mat> = ArrayList(3)
                val mat = Mat(bmp.width, bmp.height,CvType.CV_8UC1)
                Utils.bitmapToMat(bmp, mat)
                Core.split(mat, rgb_list)
//                Core.split(mRGB, rgb_list)

                var luma = 0.0
                var force = 0.0

//                var temp_luma= Core.sumElems(rgb_list[0]).`val`[0]

                var (temp_luma, temp_force, max) = ManualGetCenterBrightnessOpenCVMasked(
                    rgb_list[0],
                    minThresh,
                    maxThresh,
                    manualPPGSquareSize,
                    manualForceSquareSize,
                    true,
                    true
                )
//                getHistogram(rgb_list[0])

                luma = temp_luma
                if (correctISO == true){
                    Log.d("sensitivity", "luma: ".plus(luma.toString()))
                    session.stopRepeating()
                    val minthresh = 120
                    val maxthresh = 170
                    if (luma>maxthresh){
                        sensitivity= abs(sensitivity-abs(luma-minthresh)).toInt()
                    }else if (luma<minthresh){
                        sensitivity= abs(sensitivity+abs(luma-maxthresh)).toInt()
                    }else{
                        ISOset=false
                        correctISO=false
                        Log.d("sensitivity", "STOP!!!!!!!")
                    }
                    session.setRepeatingRequest(session.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        // Add the preview surface target
//            this[CaptureRequest.FLASH_MODE] = flashMode
                        this[CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE] =
                            CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_OFF
//            this[CaptureRequest.LENS_APERTURE] = characteristics[LENS_INFO_AVAILABLE_APERTURES]!![0]
                        Log.d("rt", characteristics[CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE]!!.toString())
                        Log.d("rt", characteristics[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE]!!.toString())
                        Log.d("rt", characteristics[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE]!!.toString())
                        Log.d("sensitivity", "In preview: ".plus(sensitivity.toString()))
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
                        addTarget(view_finder.holder.surface)
//            addTarget(recorderSurface)
                        addTarget(previewDataSurface)
                    }.build(), captureCallback, cameraHandler)
                }

//                ppgSeries.updateData(luma)
//                if (isRecording){
//                    ppgwriter.appendLine(Calendar.getInstance().timeInMillis.toString().plus(luma.toString()))
//                }
                dataBufferQueue.add(luma)
                if (!useFilter2){
                    ppgSeries.updateData(luma)
                    if (isRecording){
//                        ppgwriter.appendLine(Calendar.getInstance().timeInMillis.toString().plus(luma.toString()))
                        ppgwriter.appendLine(luma.toString())
                    }
                }
                else if (dataBufferQueue.size >bufferSize){
                    val bufferArray = dataBufferQueue.toList().toDoubleArray()
                    val reflect = UtilMethods.padSignal(bufferArray, "reflect")
//                    val flt = Chebyshev(reflect, 30.0, 1.0, 1)
                    var filteredSignal: DoubleArray = flt.bandPassFilter(reflect, 5, 0.6, 5.0)
//                        flt.highPassFilter(reflect, 3, 0.1)
                    filteredSignal = UtilMethods.splitByIndex(filteredSignal, bufferSize, bufferSize*2)
//                    val filteredSignal = fw.firfilter(outCoeffs, bufferArray)
                    val lastVal = filteredSignal.last()
                    if (xaccelBufferQueue.size >= accelBufferSize){
                            ppgSeries.updateData(lastVal)
                        }
                    if (isRecording){
                        ppgwriter.appendLine(Calendar.getInstance().timeInMillis.toString().plus(luma.toString()))
                    }
                }
                if (dataBufferQueue.size >= bufferSize) {
                    dataBufferQueue.poll()
                }


                try {
                    if (recording){
                        frameIndex = frameIndex?.plus(1)
                    }
                } catch (e: java.io.IOException) {
                    println()
                }
            }
        }
    }

    /************************************Camera Related******************************************/
    /** Detects, characterizes, and connects to a CameraDevice (used for all camera operations) */
    private val cameraManager: CameraManager by lazy {
        val context = applicationContext
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    private var frontCameraId = 0.toString()
    private var backCameraId = 2.toString()

    /** [CameraCharacteristics] corresponding to the provided Camera ID */
    private val characteristics: CameraCharacteristics by lazy {
//        cameraManager.getCameraCharacteristics(frontCameraId)
        cameraManager.getCameraCharacteristics(backCameraId)
    }


    /** Captures frames from a [CameraDevice] for our video recording */
    private lateinit var session: CameraCaptureSession

    private lateinit var view_finder: SurfaceView

    /** The [CameraDevice] that will be opened in this fragment */
    private lateinit var camera: CameraDevice
    //    private val autoAE = CONTROL_AE_MODE_ON
    private val autoAE = CameraCharacteristics.CONTROL_AE_MODE_OFF

    //    private val autoAWB = CONTROL_AWB_MODE_AUTO
    private val autoAWB = CameraCharacteristics.CONTROL_AWB_MODE_OFF

    //    private val autoAF = CONTROL_AF_MODE_CONTINUOUS_VIDEO
    private val autoAF = CameraCharacteristics.CONTROL_AF_MODE_OFF

    private var flashMode = CameraCharacteristics.FLASH_MODE_TORCH
//    private val flashMode = FLASH_MODE_OFF

    private val toneMap: TonemapCurve = TonemapCurve(floatArrayOf(0.0F, 0.0F, 1.0F, 1.0F), floatArrayOf(0.0F, 0.0F, 1.0F, 1.0F), floatArrayOf(0.0F, 0.0F, 1.0F, 1.0F))

    private var focalRatio: Float = 1.0f
    private var boost = 100 //[100, 3199]
    private val frameDuration:Long = 33333333
    //    private val frameDuration:Long = 30333333
//    private val exposureTime:Long = 33338756 // [13611, 10170373248]
    private var exposureTimeMs: Double = 40.0
    private var exposureTime:Long = (exposureTimeMs * 10.0.pow(6)).toLong() // [13611, 10170373248]

    private var sensitivity = 55 // [55, 7111], default = 444
//    private var sensitivity = 1000 //95
//    private var sensitivity = 100

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
    private val colorCorrectionTransform =  intArrayOf(
        192, 128, -89, 128, 24, 128,
        -23, 128, 152, 128, -2, 128,
        18, 128, -123, 128, 233, 128
    )
    private val colorCorrectionMode = 1

    private var ae_lock = false
    private var awb_lock = false
    private val camera_fps = 60

//    private var countDownJob: Job? = null

    /** Requests used for preview only in the [CameraCaptureSession] */
    private val previewRequest: CaptureRequest by lazy {
        // Capture request holds references to target surfaces
        session.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            // Add the preview surface target
//            this[CaptureRequest.FLASH_MODE] = flashMode
            this[CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE] =
                CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_OFF
//            this[CaptureRequest.LENS_APERTURE] = characteristics[LENS_INFO_AVAILABLE_APERTURES]!![0]
            Log.d("rt", characteristics[CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE]!!.toString())
            Log.d("rt", characteristics[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE]!!.toString())
            Log.d("rt", characteristics[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE]!!.toString())
            Log.d("sensitivity", "In preview: ".plus(sensitivity.toString()))
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
            addTarget(view_finder.holder.surface)
//            addTarget(recorderSurface)
            addTarget(previewDataSurface)
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
            addTarget(view_finder.holder.surface)
//            addTarget(recorderSurface)
            addTarget(previewDataSurface)
            // Sets user requested FPS for all targets
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(camera_fps,camera_fps))
        }.build()
    }

    private val captureCallback = object: CameraCaptureSession.CaptureCallback(){
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            super.onCaptureCompleted(session, request, result)
            exposureMetadata = result[CaptureResult.SENSOR_EXPOSURE_TIME]!!
            sensitivityMetadata = result[CaptureResult.SENSOR_SENSITIVITY]!!
            tonemapMetadata = result[CaptureResult.TONEMAP_CURVE]!!
            focusDistanceMetadata = result[CaptureResult.LENS_FOCUS_DISTANCE]!!
//            Log.d("rt", "ToneMode: " + result[CaptureResult.TONEMAP_MODE].toString())
//            Log.d("rt", "ToneCurve: " + result[CaptureResult.TONEMAP_CURVE].toString())
//            Log.d("rt", "Boost: " + result[CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST].toString())
//            Log.d("rt", "FrameDuration: " + result[CaptureResult.SENSOR_FRAME_DURATION].toString())
//            Log.d("rt", "Sensitivity: " + result[CaptureResult.SENSOR_SENSITIVITY].toString())
//            Log.d("rt", "ExposureDuration: " + result[CaptureResult.SENSOR_EXPOSURE_TIME].toString())
//            Log.d("rt", "ColorCorrectionGain: " + result[CaptureResult.COLOR_CORRECTION_GAINS].toString())
//            Log.d("rt", "ColorCorrectionTransform: " + result[CaptureResult.COLOR_CORRECTION_TRANSFORM].toString())
//            Log.d("rt", "ColorCorrectionMode: " + result[CaptureResult.COLOR_CORRECTION_MODE].toString())
//            Log.d("rt", "LensAperture: " + result[CaptureResult.LENS_APERTURE].toString())
//            Log.d("rt", "BlackLevel: " + result[CaptureResult.LENS_OPTICAL_STABILIZATION_MODE].toString())
        }

    }

    private fun getFrontFacingCameraId(cManager: CameraManager): String {
        try {
            var cameraId: String?
            var cameraOrientation: Int
            var characteristics: CameraCharacteristics
            for (i in 0 until cManager.cameraIdList.size) {
                cameraId = cManager.cameraIdList[i]
                characteristics = cManager.getCameraCharacteristics(cameraId)
                cameraOrientation = characteristics[CameraCharacteristics.LENS_FACING]!!
                Log.d("findcameraid", cameraId.plus(cameraOrientation.toString()))
                if (cameraOrientation == CameraMetadata.LENS_FACING_FRONT) {
                    return cameraId.toString()
                }
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
        return null.toString()
    }

    private fun getBackFacingCameraId(cManager: CameraManager): String {
        try {
            var cameraId: String?
            var cameraOrientation: Int
            var characteristics: CameraCharacteristics
            for (i in 0 until cManager.cameraIdList.size) {
                cameraId = cManager.cameraIdList[i]
                characteristics = cManager.getCameraCharacteristics(cameraId)
                cameraOrientation = characteristics[CameraCharacteristics.LENS_FACING]!!
                if (cameraOrientation == CameraCharacteristics.LENS_FACING_BACK) {
                    return cameraId.toString()
                }
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
        return null.toString()
    }

    /**
     * Begin all camera operations in a coroutine in the main thread. This function:
     * - Opens the camera
     * - Configures the camera session
     * - Starts the preview by dispatching a repeating request
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun initializeCamera() = lifecycleScope.launch(Dispatchers.Main) {
        frontCameraId=getFrontFacingCameraId(cameraManager!!)
        Log.d(TAG, "Camera " + frontCameraId + " color characteristics")
        val configMap: StreamConfigurationMap? = characteristics
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val colorFormats: IntArray? = configMap?.outputFormats
        Log.d("CSV", colorFormats.toString())
        // Open the selected camera
//        camera = openCamera(cameraManager, frontCameraId, cameraHandler)
        if (Build.MODEL != "SM-A536U1"){
            camera = openCamera(cameraManager, frontCameraId, cameraHandler)
        }else{
            camera = openCamera(cameraManager, "3", cameraHandler)
        }


//        val expName = "Test"
//        GlobalScope.launch {
//            setupRecorder(recorderSurface, expName)
////            recorder?.start()
//        }

//        viewFinder = view.findViewById(R.id.view_finder)


        // Creates list of Surfaces where the camera will output frames
        val targets = listOf(view_finder.holder.surface, previewDataSurface)
//        val targets = listOf(recorderSurface)
//        val targets = listOf(view_finder.holder.surface)
//        val targets = listOf(previewDataSurface)

        // Start a capture session using our open camera and list of Surfaces where frames will go
        session = createCaptureSession(camera, targets, cameraHandler)

        // Sends the capture request as frequently as possible until the session is torn down or
        //  session.stopRepeating() is called
        session.setRepeatingRequest(previewRequest, captureCallback, cameraHandler)

    }


    /** Opens the camera and returns the opened device (as the result of the suspend coroutine) */
    @SuppressLint("MissingPermission")
    private suspend fun openCamera(
        manager: CameraManager,
        cameraId: String,
        handler: Handler? = null
    ): CameraDevice = suspendCancellableCoroutine { cont ->
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) = cont.resume(device)

            override fun onDisconnected(device: CameraDevice) {
                Log.w(TAG, "Camera $cameraId has been disconnected")
//                requireActivity().finish()
            }

            override fun onError(device: CameraDevice, error: Int) {
                val msg = when (error) {
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

        // Creates a capture session using the predefined targets, and defines a session state
        // callback which resumes the coroutine once the session is configured
        device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {

            override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)

            override fun onConfigureFailed(session: CameraCaptureSession) {
                val exc = RuntimeException("Camera ${device.id} session configuration failed")
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }
        }, handler)
    }

    /************************************Video Recording Related*********************************/
    /** File where the recording will be saved */
    private var frameIndex: Int? = null
    private var recordingStartTime: Long? = null

    private fun setupOutputFile(expName: String): File {
        val mediaFileName: String = "$mediaDir/${expName}_${sbjTrlName}.mp4"
        return File(mediaFileName)
    }
    private var outputFile: File? = null
    private fun setupRecorder(surface: Surface, expName: String) {
        outputFile = setupOutputFile(expName)
        recorder = MediaRecorder().apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncodingBitRate(RECORDER_VIDEO_BITRATE)
            setVideoFrameRate(camera_fps)
            setOutputFile(outputFile?.absolutePath)
            setVideoSize(img_width, img_height)
            setVideoEncoder(MediaRecorder.VideoEncoder.HEVC)
            //        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setInputSurface(surface)
//            relativeOrientation.value?.let { setOrientationHint(it) }
            prepare()
//            start()
        }
    }




    /************************************PlottingModel*********************************/
    class accelModel internal constructor(size: Int, private val xRange:Int) : XYSeries {
        private val data: Array<Number?> = arrayOfNulls<Number>(size)
        private val titles = arrayOf("x", "y", "z")
        private var latestIndex = 0
        private var startTime = Calendar.getInstance().timeInMillis
        private var seriesNum = 0
        fun updateData(newData: Double): Unit {
            data[latestIndex] = newData
            latestIndex += 1
            if (latestIndex == xRange) {
                latestIndex = 0
//                for (i in 0..xRange+1){
//                    data[i] = null
//                }
            }

        }
        override fun size(): Int {
            return data.size
        }
        override fun getX(index: Int): Number {
//            Log.d(TAG,(Calendar.getInstance().timeInMillis-startTime).toString())
//            Log.d(TAG,index.toString())
//            if (index==0){
//                startTime=Calendar.getInstance().timeInMillis
//            }
//            return Calendar.getInstance().timeInMillis-startTime
            return index
        }
        override fun getY(index: Int): Number? {
            return data[index]
        }
        override fun getTitle(): String {
//            val returnVar = titles[seriesNum]
//            seriesNum+=1
//            return returnVar
            return "accelerometer"
        }
    }

    class ppgModel internal constructor(size: Int, private val xRange:Int) : XYSeries {
        private val data: Array<Number?> = arrayOfNulls<Number>(size)
        private val titles = "ppg"
        private var latestIndex = 0
        private var seriesNum = 0
        fun updateData(newData: Double): Unit {
            data[latestIndex] = newData
            latestIndex += 1
            if (latestIndex == xRange) {
                latestIndex = 0
//                for (i in 0..xRange+1){
//                    data[i] = null
//                }
            }

        }
        override fun size(): Int {
            return data.size
        }
        override fun getX(index: Int): Number {
            return index
        }
        override fun getY(index: Int): Number? {
            return data[index]
        }
        override fun getTitle(): String {
            return titles

        }
    }


    @RequiresApi(Build.VERSION_CODES.S)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Hide the status bar.
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN
        // Remember that you should never show the action bar if the
        // status bar is hidden, so hide that too if necessary.
        actionBar?.hide()
        setContentView(R.layout.activity_vibrobp)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        mVibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?
//        mVibrator = vibrationManager.defaultVibrator
        mAccelerometer=sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        mGyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        Log.d("build num", Build.MODEL.toString())
        accelflt= Butterworth(filterFreqMap[Build.MODEL.toString()]!!)
        accelfltfrq= filterFreqMap[Build.MODEL.toString()]?.times(fltfrq)!!



//        if (sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) != null) {
////            mAccelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
//            mAccelerometer=sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
//            mGyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
//        } else {
//            Toast.makeText(this, "No Accelerometer/Gyroscope Access", Toast.LENGTH_LONG)
//        }
        verifyStoragePermissions(this)
        verifyCameraPermissions(this)
        verifyVibratePermissions(this)
        var vibrateButton: Button = findViewById<Button>(R.id.vibrateButton)
        var lockScreenButton: Button = findViewById<Button>(R.id.lockScreenButton)
        var fingerSizerplus: Button = findViewById<Button>(R.id.fingerSizerplus)
        var fingerSizerminus: Button = findViewById<Button>(R.id.fingerSizerminus)
        var fingerGuide: ImageView=  findViewById<ImageView>(R.id.fingerGuide)
//        fingerSizerplus.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
//            override fun onStopTrackingTouch(seekBar: SeekBar?) {
//                // TODO Auto-generated method stub
//            }
//
//            override fun onStartTrackingTouch(seekBar: SeekBar?) {
//                // TODO Auto-generated method stub
//            }
//
//            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
//                // TODO Auto-generated method stub
//                fingerGuide.layoutParams.height = 129*3 * progress/50
//                fingerGuide.layoutParams.width = 92*3 * progress/50
//                fingerGuide.requestLayout()
//                fingerSize = progress
//            }
//        })
        fingerSizerplus.setOnClickListener {
            fingerGuide.layoutParams.height = fingerGuide.layoutParams.height+10
            fingerGuide.layoutParams.width = fingerGuide.layoutParams.width+10
            fingerGuide.requestLayout()
        }
        fingerSizerminus.setOnClickListener {
            fingerGuide.layoutParams.height = fingerGuide.layoutParams.height-10
            fingerGuide.layoutParams.width = fingerGuide.layoutParams.width-10
            fingerGuide.requestLayout()
        }


        lockScreenButton.setOnClickListener {
            if (isScreenLocked){
                window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            }else{
                lifecycleScope.launch(){
                    window.setFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,  WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                    delay(30*1000)
                    window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                }

            }
        }
        vibrateButton.setOnClickListener {
            if (isVibrating){
                mVibrator?.cancel()
                vibrateButton.text = "Vibrate"
            }else{
//                vibrateButton.text= "Stop Vibrating"
//                val pattern = longArrayOf(0, 10000, 0)
//                mVibrator?.vibrate(pattern, 0)
                if (Build.MODEL.toString()=="Pixel 4"){
                    vibe = VibrationEffect.createOneShot(1000*100, 255)
                }
                else{
                    vibe = VibrationEffect.createWaveform(longArrayOf(1000*1), intArrayOf(255),0)
                }

//                val vibe = VibrationEffect.createWaveform(longArrayOf(1000*5), intArrayOf(255),0)
                mVibrator?.vibrate(vibe)

//                mVibrator?.vibrate(longArrayOf(500,500,0),0)

            }
            isVibrating = !isVibrating
        }
        OpenCVLoader.initDebug()
        view_finder = findViewById(R.id.view_finder)
        var recordButton: Button = findViewById(R.id.recordButton)
        CalibrationButton=findViewById(R.id.CalibrationButton)
        isoButton=findViewById(R.id.isoButton)
        var accelButton:Button=findViewById(R.id.accelButton)
        var idNumberText = findViewById<EditText>(R.id.idNumber)
        var trialNumberText = findViewById<EditText>(R.id.trialNumber)
        var pulseText = findViewById<EditText>(R.id.pulse)
        var sysText = findViewById<EditText>(R.id.sys)
        var diaText = findViewById<EditText>(R.id.dia)
        if (intent.extras?.get("UserId") != null) {
            val useridstring = intent.extras?.get("UserId") as String
            Log.d("userid", "farred userid = ".plus(useridstring))
                idNumberText.setText(useridstring)
        }
        idNumberText.isEnabled = true

        recordButton.setOnClickListener {
            val seconds = seconds.toLong()
            try {
                userIDnumber = idNumberText.text.toString().toInt()
                trialNumber = trialNumberText.text.toString().toInt()
            }catch(E:Exception){
                userIDnumber=0
                trialNumber=0
                idNumberText.setText(userIDnumber.toString())
                trialNumberText.setText(trialNumber.toString())
            }
            try {
                pulse = pulseText.text.toString().toInt()
                sys = sysText.text.toString().toInt()
                dia = diaText.text.toString().toInt()
            }catch (E:Exception){
                pulse=0
                sys=0
                dia=0
                pulseText.setText(pulse.toString())
                sysText.setText(sys.toString())
                diaText.setText(dia.toString())
            }

//            if (isRecording){
//            isRecording = !isRecording
//            writer.flush()
//            ppgwriter.flush()
//            gyrowriter.flush()
//            writer.close()
//            ppgwriter.close()
//            gyrowriter.close()
//            recordButton.text = "Record"
//            if (filestoupload.size >=1){
//                uploadFileArrayToNassftp(filestoupload)
//            }
//            }else{
            xaccelMeasurements = floatArrayOf()
            yaccelMeasurements = floatArrayOf()
            zaccelMeasurements = floatArrayOf()
            ppgroot = createNamedFile(this, "ppg", "txt")
            gyroroot = createNamedFile(this, "gyro", "txt")
            root = createNamedFile(this, "accel", "txt")
            writer = FileWriter(root)
            gyrowriter = FileWriter(gyroroot)
            ppgwriter = FileWriter(ppgroot)
            recordButton.text = "Stop/Save"
            filestoupload.add(ppgroot)
            filestoupload.add(gyroroot)
            filestoupload.add(root)
            isRecording = !isRecording
            lifecycleScope.launch() {
                delay(seconds*1000)
                writer.flush()
                ppgwriter.flush()
                gyrowriter.flush()
                writer.close()
                ppgwriter.close()
                gyrowriter.close()
                recordButton.text = "Record"
                if (filestoupload.size >= 1) {
                    uploadFileArrayToNassftp(filestoupload)
                }
                isRecording = !isRecording
                trialNumber+=1
                trialNumberText.setText(trialNumber.toString())
            }
        }
        val accelplot = findViewById<XYPlot>(R.id.accelplot)
        accelplot.getLayoutManager().remove(accelplot.legend)
        val ppgplot = findViewById<XYPlot>(R.id.ppgplot)
        ppgplot.getLayoutManager().remove(ppgplot.legend)
        ppgplot.getLayoutManager().remove(ppgplot.domainTitle)
        ppgplot.rangeStepValue = 3.0
        accelplot.rangeStepValue = 2.0

        val paint: Paint = guideformatter.linePaint
        paint.setStrokeWidth(30F)
        guideformatter.linePaint=paint

        accelplot.addSeries(forceaccelSeries, xformatter)
//        accelplot.addSeries(forceGuideSeries, guideformatter)
//        accelplot.addSeries(xgyroSeries, xformatter)

        ppgplot.addSeries(ppgSeries, xformatter)
//        ppgPlot.setRangeBoundaries(25, 50, BoundaryMode.FIXED)
//        ppgPlot.graph.getLineLabelStyle(XYGraphWidget.Edge.LEFT).format = DecimalFormat("0.000")
//        ppgplot.setDomainBoundaries(0,ppgRange, BoundaryMode.FIXED)
        ppgplot.setDomainBoundaries(0,ppgRange, BoundaryMode.FIXED)
//        ppgplot.setRangeBoundaries(0,10, BoundaryMode.FIXED)
        accelplot.setDomainBoundaries(0, xRange, BoundaryMode.FIXED)
//        accelplot.setRangeBoundaries(-4,4,BoundaryMode.FIXED)

        ppgplot.rangeStepMode = StepMode.SUBDIVIDE
//        ppgplot.rangeStepValue = 3.0
        accelplot.rangeStepMode = StepMode.SUBDIVIDE
//        plot.rangeStepValue = 1.0
        xformatter.isLegendIconEnabled = true
        val accelRedrawer: Redrawer by lazy { Redrawer(accelplot, 60F, false) }
        val ppgRedrawer: Redrawer by lazy { Redrawer(ppgplot, 60F, false) }
        accelRedrawer.start()
        ppgRedrawer.start()
//        var accelChannels = listOf(forceaccelSeries, xgyroSeries, ygyroSeries, zgyroSeries, xaccelSeries, yaccelSeries, zaccelSeries)
//        var accelButtonTexts = listOf("force", "x gyro", "y gyro", "z gyro", "x accel", "y accel", "z accel")
//        accelButton.text = accelButtonTexts[currentChannel]
//        accelButton.setOnClickListener {
//            accelplot.removeSeries(accelChannels[currentChannel])
//            if (currentChannel == accelChannels.size-1){
//                currentChannel=0
//            }else{
//                currentChannel +=1
//            }
//            accelButton.text = accelButtonTexts[currentChannel]
//            accelplot.addSeries(accelChannels[currentChannel], xformatter)
//        }
        lifecycleScope.launch(){
            initializeCamera()
        }
        //select correct phone
        if (Build.MODEL.toString() in forceParamMap){
            if(accel_only){
                forceParam = accelForceParamMap[Build.MODEL.toString()]!!
            }else{
                forceParam = forceParamMap[Build.MODEL.toString()]!!
            }
            val marginParams: ViewGroup.MarginLayoutParams = fingerGuide.getLayoutParams() as ViewGroup.MarginLayoutParams
            marginParams.setMargins(0, 0, -10, cameraLocationMap[Build.MODEL.toString()]!!)
            fingerGuide.layoutParams=marginParams


        }else{
            Toast.makeText(this,"Smartphone Model ("+Build.MODEL.toString()+") Not Calibrated", Toast.LENGTH_LONG).show()
            forceParam = doubleArrayOf(0.0,0.0,0.0,0.0,0.0,0.0,0.0)
        }
        isoButton.setOnClickListener{
            lifecycleScope.launch {
                //adjust ISO to get ppgval (luma) within desired range 0<ppg<255
                isoButton.text="ISO Calibration in Progress"
                while (ISOset == true) {
                    correctISO = true
                    delay(1000)
                    Log.d("sensitivity", "sensitivity: ".plus(sensitivity.toString()))
                }
                ISOset=true
                isoButton.text="Calibrate ISO"
//                Toast.makeText(applicationContext, "calibrated", Toast.LENGTH_SHORT).show()
            }
        }
        CalibrationButton.setOnClickListener{
            //start vibrating
            vibrateButton.performClick()
            CalibrationButton.text = "Testing in progress"
            lifecycleScope.launch{
                accelCounter=0
                gyroCounter = 0
                ppgCounter=0
                accelMax = -999.0
                accelMin = 999.0

                calibrationMode = true
                delay(1000)
                calibrationMode = false

                Log.d("Sampling Rate", accelCounter.toString())
                accelflt= Butterworth(accelCounter.toDouble())
                accelfltfrq = accelCounter*fltfrq
                forceGuideVal=0.0

                accelplot.removeSeries(forceaccelSeries)
                accelplot.removeSeries(forceGuideSeries)
                ppgplot.removeSeries(ppgSeries)
                val plotSeconds = seconds
                var calibratedRange = accelCounter*plotSeconds
                var calibratedGyroRange = gyroCounter*plotSeconds
//                xgyroSeries = accelModel(calibratedGyroRange, calibratedGyroRange)
//                ygyroSeries = accelModel(calibratedGyroRange, calibratedGyroRange)
//                zgyroSeries = accelModel(calibratedGyroRange, calibratedGyroRange)
//                xaccelSeries = accelModel(calibratedRange, calibratedRange)
//                yaccelSeries = accelModel(calibratedRange, calibratedRange)
//                zaccelSeries = accelModel(calibratedRange, calibratedRange)
                forceGuideSeries = accelModel(calibratedRange, calibratedRange)
                forceaccelSeries = accelModel(calibratedRange, calibratedRange)

//                accelChannels = listOf(forceaccelSeries, xgyroSeries, ygyroSeries, zgyroSeries, xaccelSeries, yaccelSeries, zaccelSeries)
//                ppgSeries = ppgModel(ppgCounter*plotSeconds, ppgCounter*plotSeconds)
                accelplot.setDomainBoundaries(0, calibratedRange, BoundaryMode.FIXED)
                accelplot.setRangeBoundaries(140,170,BoundaryMode.FIXED)
//                ppgplot.setRangeBoundaries(-15,15,BoundaryMode.FIXED)
//                ppgplot.setDomainBoundaries(0,ppgCounter*plotSeconds,BoundaryMode.FIXED)
                Log.d(TAG, "ppgCounter = ".plus(ppgCounter))
                Log.d(TAG, "accel Sample Rate = ".plus(accelCounter).plus("Hz"))
                Log.d(TAG, "gyro Sample Rate = ".plus(gyroCounter).plus("Hz"))
                flt= Butterworth(ppgCounter.toDouble())
                accelflt= Butterworth(accelCounter.toDouble())
                accelfltfrq = accelCounter.toDouble()*0.05
                bufferSize=(ppgCounter/2).toInt()
                accelBufferSize=(accelCounter/2).toInt()
//                delay(1000)
                accelplot.addSeries(forceGuideSeries,guideformatter)
                accelplot.addSeries(forceaccelSeries, xformatter)
                ppgplot.addSeries(ppgSeries, xformatter)
//                ppgplot.rangeStepMode = StepMode.SUBDIVIDE
                ppgplot.rangeStepValue = 3.0
                accelplot.rangeStepValue = 2.0
                accelplot.setDomainStep(StepMode.SUBDIVIDE, 5.0)
                //start recording
                recordButton.isEnabled=true
                recordButton.performClick()
                recordButton.isEnabled=false
                for(i in 0 until calibratedRange){
                    if (i > accelCounter*30){
                        val thresh = 143.0+(accelCounter*30*32.0/calibratedRange)
                        val j = i-accelCounter*30
                        forceGuideSeries.updateData(thresh+((j*1.5/(calibratedRange-(accelCounter*35)))))
                    }else{
                        forceGuideSeries.updateData(143.0+(i*32.0/calibratedRange))
                    }

                }
                delay(seconds*1000L+1000L)
                vibrateButton.performClick()
                CalibrationButton.text = "Start Test"
                accelplot.removeSeries(forceaccelSeries)
                accelplot.removeSeries(forceGuideSeries)
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val action: Int
        val keycode: Int
        action = event.action
        keycode = event.keyCode
        when (keycode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (KeyEvent.ACTION_UP === action) {
                    CalibrationButton.performClick()
                    return true
                }
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (KeyEvent.ACTION_DOWN == action){
                    isoButton.performClick()
                    return true
                }
            }
        }

        return super.dispatchKeyEvent(event)

    }


    private fun createNamedFile(context: Context,type: String, extension: String): File {
        val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
        return File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "VibroBP_".plus(Build.MODEL.toString()).plus("_")
            .plus(type)
            .plus("_")
            .plus(userIDnumber.toString())
            .plus("_")
            .plus(trialNumber)
            .plus("_")
            .plus(sys.toString())
            .plus("_")
            .plus(dia.toString())
            .plus("_")
            .plus(pulse.toString())
            .plus("_")
            .plus(fingerSize.toString())
            .plus("_")
            .plus("${sdf.format(Date())}.$extension"))
    }

    private fun uploadFileArrayToNassftp(filestoupload:MutableList<File>){
        try {
            Log.d("test", "client created")
            val fileiterator = filestoupload.iterator()
            Thread {
                var client = SFTPClient
                while (fileiterator.hasNext()) {
                    client.sftpUploadFile_keyAuthentication_jsch(applicationContext,fileiterator.next())
                }
            }.start()

        } catch (e: Exception) {
            Toast.makeText(applicationContext, e.toString(), Toast.LENGTH_LONG).show()
        }


    }

    private fun getFilename(path: String): String {
        return(path.substring(path.lastIndexOf("/")+1))
    }

    override fun onSensorChanged(p0: SensorEvent?) {
        if (p0!!.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            if (isRecording) {
                xaccelMeasurements = xaccelMeasurements.plus(p0?.values?.get(0)!!)
                yaccelMeasurements = yaccelMeasurements.plus(p0?.values?.get(1)!!)
                zaccelMeasurements = zaccelMeasurements.plus(p0?.values?.get(2)!!)
                writer.appendLine(
                    Calendar.getInstance().timeInMillis.toString().plus(", ").plus(
                        p0?.values?.get(0).toString().plus(", ").plus(p0?.values?.get(1).toString())
                            .plus(", ").plus(p0?.values?.get(2).toString())
                    )
                )
            }
            if (calibrationMode) {
                accelCounter += 1
//                if (p0?.values?.get(currentChannel)!!.toDouble() > accelMax) {
//                    accelMax = p0?.values?.get(currentChannel)!!.toDouble()
//                }
//                if (p0?.values?.get(currentChannel)!!.toDouble() < accelMin) {
//                    accelMin = p0?.values?.get(currentChannel)!!.toDouble()
//                }
            }
//        xaccelSeries.updateData(p0?.values?.get(0)!!.toDouble())
//        yaccelSeries.updateData(p0?.values?.get(1)!!.toDouble())
//        zaccelSeries.updateData(p0?.values?.get(2)!!.toDouble())
            val x = p0?.values?.get(0)!!.toDouble()
            val y = p0?.values?.get(1)!!.toDouble()
            val z = p0?.values?.get(2)!!.toDouble()
//            yaccelSeries.updateData(y)

            xaccelBufferQueue.add(x)
            yaccelBufferQueue.add(y)
            zaccelBufferQueue.add(z)
            if (!useFilter) {
                xaccelSeries.updateData(x)
                yaccelSeries.updateData(y)
                zaccelSeries.updateData(z)
            } else if (xaccelBufferQueue.size >= accelBufferSize) {
                val xbufferArray = xaccelBufferQueue.toList().toDoubleArray()
                val ybufferArray = yaccelBufferQueue.toList().toDoubleArray()
                val zbufferArray = zaccelBufferQueue.toList().toDoubleArray()

                val xreflect = UtilMethods.padSignal(xbufferArray, "reflect")
                val yreflect = UtilMethods.padSignal(ybufferArray, "reflect")
                val zreflect = UtilMethods.padSignal(zbufferArray, "reflect")
                var xfilteredSignal: DoubleArray =
                    accelflt.lowPassFilter(absoluteValue(xreflect), 5, accelfltfrq)
                var yfilteredSignal: DoubleArray =
                    accelflt.lowPassFilter(absoluteValue(yreflect), 5, accelfltfrq)
                var zfilteredSignal: DoubleArray =
                    accelflt.lowPassFilter(absoluteValue(zreflect), 5, accelfltfrq)

                xfilteredSignal =
                    UtilMethods.splitByIndex(xfilteredSignal, accelBufferSize, accelBufferSize * 2)
                yfilteredSignal =
                    UtilMethods.splitByIndex(yfilteredSignal, accelBufferSize, accelBufferSize * 2)
                zfilteredSignal =
                    UtilMethods.splitByIndex(zfilteredSignal, accelBufferSize, accelBufferSize * 2)

//            var xhilbertSignal = Hilbert(xbufferArray)
//            var yhilbertSignal = Hilbert(ybufferArray)
//            var zhilbertSignal = Hilbert(zbufferArray)
//
//            xhilbertSignal.transform(false)
//            yhilbertSignal.transform(false)
//            zhilbertSignal.transform(false)
//
//            var xfilteredSignal= xhilbertSignal.amplitudeEnvelope
//            var yfilteredSignal= yhilbertSignal.amplitudeEnvelope
//            var zfilteredSignal= zhilbertSignal.amplitudeEnvelope
//
                val lastValx = xfilteredSignal.last()
                val lastValy = yfilteredSignal.last()
                val lastValz = zfilteredSignal.last()

//            val forceaccelval = 157.4+(9.9*lastValx -118.4* lastValy - 14 *lastValz)
//            val forceaccelval = 115+(41.8*lastValx - 34.6*lastValy)
//            val forceaccelval = 238-83.7*lastValy
//                val forceaccelval = 107 + (28 * lastValx - 20 * lastValy + 5 * lastValz)
                val forceaccelval = forceParam[0] + (forceParam[1] * lastValx + forceParam[2] * lastValy + forceParam[3] * lastValz+ forceParam[4] * xgyro + forceParam[5] * ygyro + forceParam[6] * zgyro)
//                forceaccelSeries.updateData(forceaccelval)
                if (isRecording) {
                    forceaccelSeries.updateData(forceaccelval)
                }
            }
            if (xaccelBufferQueue.size >= accelBufferSize) {
                xaccelBufferQueue.poll()
                yaccelBufferQueue.poll()
                zaccelBufferQueue.poll()
            }
        }
        ///////////////////////////////////////////////////////GYROSCOPE//////////////////////////////////////////////////////////////////////////////////////
        else if (p0!!.sensor.type == Sensor.TYPE_GYROSCOPE){
            if (isRecording){
                xgyroSeries.updateData(p0?.values!!.get(0).toDouble())
                ygyroSeries.updateData(p0?.values!!.get(1).toDouble())
                zgyroSeries.updateData(p0?.values!!.get(2).toDouble())
                gyrowriter.appendLine(
                    Calendar.getInstance().timeInMillis.toString().plus(", ").plus(
                        p0?.values?.get(0).toString().plus(", ").plus(p0?.values?.get(1).toString())
                            .plus(", ").plus(p0?.values?.get(2).toString())
                    )
                )
            }
            if (calibrationMode){
                gyroCounter +=1
            }

            val x = p0?.values?.get(0)!!.toDouble()
            val y = p0?.values?.get(1)!!.toDouble()
            val z = p0?.values?.get(2)!!.toDouble()
            xgyroBufferQueue.add(x)
            ygyroBufferQueue.add(y)
            zgyroBufferQueue.add(z)

            if (!useFilter) {
                xgyroSeries.updateData(x)
                ygyroSeries.updateData(y)
                zgyroSeries.updateData(z)
            } else if (xgyroBufferQueue.size >= gyroBufferSize) {
                val xbufferArray = xgyroBufferQueue.toList().toDoubleArray()
                val ybufferArray = ygyroBufferQueue.toList().toDoubleArray()
                val zbufferArray = zgyroBufferQueue.toList().toDoubleArray()


                val xreflect = UtilMethods.padSignal(xbufferArray, "reflect")
                val yreflect = UtilMethods.padSignal(ybufferArray, "reflect")
                val zreflect = UtilMethods.padSignal(zbufferArray, "reflect")
                var xfilteredSignal: DoubleArray =
                    accelflt.lowPassFilter(absoluteValue(xreflect), 5, accelfltfrq)
                var yfilteredSignal: DoubleArray =
                    accelflt.lowPassFilter(absoluteValue(yreflect), 5, accelfltfrq)
                var zfilteredSignal: DoubleArray =
                    accelflt.lowPassFilter(absoluteValue(zreflect), 5, accelfltfrq)

                xfilteredSignal =
                    UtilMethods.splitByIndex(xfilteredSignal, gyroBufferSize, gyroBufferSize * 2)
                yfilteredSignal =
                    UtilMethods.splitByIndex(yfilteredSignal, gyroBufferSize, gyroBufferSize * 2)
                zfilteredSignal =
                    UtilMethods.splitByIndex(zfilteredSignal, gyroBufferSize, gyroBufferSize * 2)

                xgyro = xfilteredSignal.last()
                ygyro = yfilteredSignal.last()
                zgyro = zfilteredSignal.last()
            }

            if (xgyroBufferQueue.size >= gyroBufferSize) {
                xgyroBufferQueue.poll()
                ygyroBufferQueue.poll()
                zgyroBufferQueue.poll()
            }
        }

    }

    override fun onAccuracyChanged(p0: Sensor?, p1: Int) {
        Toast.makeText(this, "Accuracy Change", Toast.LENGTH_LONG)
    }

    override fun onResume() {
        super.onResume()
        mAccelerometer.also { accel ->
            sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_FASTEST)
        }
        mGyro.also { gyro ->
            sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_FASTEST)
        }
    }
    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
//        session.stopRepeating()
    }
    @RequiresApi(Build.VERSION_CODES.R)
    fun verifyStoragePermissions(activity: Activity?) {
        // Check if we have write permission
        val permission = ActivityCompat.checkSelfPermission(
            activity!!,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        if (permission != PackageManager.PERMISSION_GRANTED) {
            // We don't have permission so prompt the user
            ActivityCompat.requestPermissions(
                activity,
                PERMISSIONS_STORAGE,
                REQUEST_EXTERNAL_STORAGE
            )
        }
    }
    fun verifyCameraPermissions(activity: Activity?) {
        // Check if we have write permission
        val permission = ActivityCompat.checkSelfPermission(
            activity!!,
            Manifest.permission.CAMERA
        )
        if (permission != PackageManager.PERMISSION_GRANTED) {
            // We don't have permission so prompt the user
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(CAMERA),
                1
            )
        }
    }
    fun verifyVibratePermissions(activity: Activity?) {
        // Check if we have write permission
        val permission = ActivityCompat.checkSelfPermission(
            activity!!,
            Manifest.permission.VIBRATE
        )
        if (permission != PackageManager.PERMISSION_GRANTED) {
            // We don't have permission so prompt the user
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(VIBRATE),
                1
            )
        }
    }
    fun absoluteValue(input:DoubleArray): DoubleArray {
        val output = DoubleArray(input.size)
        for (i in 0..input.size-1){
            output[i]=abs(input[i])
        }
        return output
    }
    companion object {
        var filesforupload:MutableList<File> = emptyList<File>().toMutableList()
        private lateinit var context: Context
//        private val TAG = CameraFragment::class.java.simpleName
        //        public const val manualPPGSquareSize = 150
//        public const val manualPPGSquareSize = 175
        const val manualPPGSquareSize = 150
        //        public const val manualPPGSquareSize = 450
        const val manualForceSquareSize = 200
        private const val direction = -1
        const val manualSquareXOffset = 5 * direction // positive is left
        const val manualSquareYOffset = 10 * direction // positive is up


        const val stepTime = 5 // seconds
//        public val threshold_list = intArrayOf(0, 20, 40, 60, 80, 100, 120)

        private fun createStepArray(start: Int, stop: Int, step: Int) : IntArray{
            val stepRangeList = mutableListOf<Int>()
            for(i in start..stop step step) {
                stepRangeList.add(i)
            }
            return stepRangeList.toIntArray()
        }
        val stepSize = 50
        val threshold_list = createStepArray(0, 250, stepSize)

        private const val RECORDER_VIDEO_BITRATE: Int = 100_000_000
        private const val MIN_REQUIRED_RECORDING_TIME_MILLIS: Long = 1000L

        /** Creates a [File] named with the current date and time */
        private fun createFile(context: Context, extension: String): File {
            val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
            return File(context.filesDir, "VID_${sdf.format(Date())}.$extension")
        }

        private fun uploadFileArrayToNassftp(filestoupload:MutableList<File>){
            try {
                val client = SFTPClient
                Log.d("test", "client created")
                val fileiterator = filestoupload.iterator()
                Log.d("test", "continuing upload")
                Thread {
                    while (fileiterator.hasNext()) {
                        client.sftpUploadFile_keyAuthentication_jsch(context,fileiterator.next())
                        Log.d("test", "continuing upload")
                    }
                    Log.d("test", "finished upload")
                }.start()

            } catch (e: Exception) {
                Log.d("SFTP upload", e.toString())
            }
        }

        class SFTPClient {
            var context: Context? = null

            companion object {
                private val username = "udcomplab"
                private val host = "132.239.43.100"
                //            private val host = "sftp://137.110.115.58"
                private val password  = "W3AreUbicomp!"
                //            private val host: String = ServerUrl.FTP_HOST
//            private val username: String = ServerUrl.FTP_USERNAME
                private const val remoteDirectory = "Projects/VibroBP/remoteUploads"
                var photo_file: File? = null

                /**
                 * http://kodehelp.com/java-program-for-uploading-file-to-sftp-server/
                 *
                 * @param server
                 * @param userName
                 * @param openSSHPrivateKey
                 * @param remoteDir
                 * @param localDir
                 * @param localFileName
                 * @throws IOException
                 */
                @Throws(IOException::class)
                fun sftpUploadFile_keyAuthentication_jsch(
                    con: Context,
                    f: File
                ) {
                    photo_file = f
                    object : AsyncTask<Void?, Void?, Void?>() {
                        private fun createFileFromInputStream(
                            inputStream: InputStream,
                            fileName: String
                        ): File? {
                            var keyFile: File? = null
                            try {
                                keyFile = File(con.cacheDir.toString() + "/" + fileName)
                                if (!keyFile.exists() || !keyFile.canRead()) {
                                    val outputStream: OutputStream = FileOutputStream(
                                        keyFile
                                    )
                                    Log.d("test", "found file")
                                    val buffer = ByteArray(1024)
                                    var length = 0
                                    while (inputStream.read(buffer).also { length = it } > 0) {
                                        outputStream.write(buffer, 0, length)
                                    }
                                    outputStream.close()
                                    inputStream.close()
                                }
                            } catch (e: IOException) {
                                // Logging exception
                                Log.e("error", e.toString() + "")
                                Log.d("test", "problem line 1973")
                            }
                            return keyFile
                        }

                        override fun doInBackground(vararg params: Void?): Void? {
                            var fis: FileInputStream? = null
                            val os: OutputStream? = null
                            try {
                                val jsch = JSch()
                                val am: AssetManager = con.assets
//                            val inputStream: InputStream = am.open("splash_openssh.ppk")
//                            val file = createFileFromInputStream(
//                                inputStream,
//                                "splash_openssh.ppk"
//                            )
//                            if (file!!.exists()) {
//                                println(file.toString() + "")
//                            } else {
//                                println(file.toString() + "")
//                            }
//                            val path = file.toString() + ""
//                            jsch.addIdentity(path)
                                val session: Session = jsch.getSession(username, host, 22)
                                val config = Properties()
                                Log.d("test", "session started")
                                config["StrictHostKeyChecking"] = "no"
                                session.setConfig(config)
                                Log.d("test", "config set")
                                session.setPassword(password)
                                session.connect()
                                println("JSch JSch Session connected.")
                                Log.d("Start Upload Process", "connected")
                                println("Opening Channel.")
                                System.gc()
                                var channelSftp: ChannelSftp? = null
                                channelSftp = session.openChannel("sftp") as ChannelSftp
                                channelSftp.connect()
                                Log.d("test", "channel open")
                                channelSftp.cd(remoteDirectory)
                                val currentFilelength = f.length()
                                fis = FileInputStream(f)
                                channelSftp.put(fis, f.name)
                                Log.d("test", "Start Upload Process")
                            } catch (e: IOException) {
                                // TODO Auto-generated catch block
                                e.printStackTrace()
                            } catch (e: OutOfMemoryError) {
                                e.printStackTrace()
                            } catch (e: JSchException) {
                                e.printStackTrace()
                            } catch (e: SftpException) {
                                e.printStackTrace()
                            } finally {
                                if (fis != null) {
                                    try {
                                        fis.close()
                                    } catch (e: IOException) {
                                        // TODO Auto-generated catch block
                                        e.printStackTrace()
                                    }
                                }
                                if (os != null) {
                                    try {
                                        os.close()
                                    } catch (e: IOException) {
                                        // TODO Auto-generated catch block
                                        e.printStackTrace()
                                    }
                                }
                            }
                            return null
                        }
                    }.execute()
                }

                /**
                 *
                 * http://kodehelp.com/java-program-for-downloading-file-from-sftp-server/
                 *
                 * @param server
                 * @param userName
                 * @param openSSHPrivateKey
                 * @param remoteDir
                 * @param remoteFile
                 * @param localDir
                 * @throws IOException
                 */
                @Throws(IOException::class)
                fun sftpDownloadFile_keyAuthentication_jsch(con: Context): File? {
                    object : AsyncTask<Void?, Void?, Void?>() {
                        private fun createFileFromInputStream(
                            inputStream: InputStream,
                            fileName: String
                        ): File? {
                            var keyFile: File? = null
                            try {
                                keyFile = File(con.cacheDir.toString() + "/" + fileName)
                                if (!keyFile.exists() || !keyFile.canRead()) {
                                    val outputStream: OutputStream = FileOutputStream(
                                        keyFile
                                    )
                                    val buffer = ByteArray(1024)
                                    var length = 0
                                    while (inputStream.read(buffer).also { length = it } > 0) {
                                        outputStream.write(buffer, 0, length)
                                    }
                                    outputStream.close()
                                    inputStream.close()
                                }
                            } catch (e: IOException) {
                                // Logging exception
                                Log.e("error", e.toString() + "")
                            }
                            return keyFile
                        }

                        override fun doInBackground(vararg params: Void?): Void? {
                            // TODO Auto-generated method stub
                            var newFile: File? = null
                            try {
                                // JSch jsch = new JSch();
                                // String password =
                                // "/storage/sdcard0/Splash/splash_openssh.ppk";
                                // System.out.println(password);
                                // jsch.addIdentity(password);
                                val jsch = JSch()
                                val am: AssetManager = con.assets
                                val inputStream: InputStream
                                inputStream = am.open("splash_openssh.ppk")
                                val file = createFileFromInputStream(
                                    inputStream,
                                    "splash_openssh.ppk"
                                )
                                if (file!!.exists()) {
                                    println(file.toString() + "")
                                } else {
                                    println(file.toString() + "")
                                }
                                val path = file.toString() + ""
                                jsch.addIdentity(path)
                                val session: Session = jsch.getSession(username, host, 22)
                                val config = Properties()
                                config["StrictHostKeyChecking"] = "no"
                                session.setConfig(config)
                                session.connect()
                                val channel: Channel = session.openChannel("sftp")
                                channel.setOutputStream(System.out)
                                channel.connect()
                                val channelSftp: ChannelSftp = channel as ChannelSftp
                                channelSftp.cd(remoteDirectory)
                                val buffer = ByteArray(1024)
                                val mf = Environment.getExternalStorageDirectory()
                                val bis = BufferedInputStream(
                                    channelSftp.get("269-twitter.jpg")
                                )
                                newFile = File(
                                    Environment.getExternalStorageDirectory()
                                        .toString() + "/Splash/upload/", "splash_img1.jpg"
                                )
                                var os: OutputStream? = null
                                os = FileOutputStream(newFile)
                                val bos = BufferedOutputStream(os)
                                var readCount: Int
                                while (bis.read(buffer).also { readCount = it } > 0) {
                                    println("Writing: ")
                                    bos.write(buffer, 0, readCount)
                                }
                                bos.close()
                            } catch (e: IOException) {
                                // TODO Auto-generated catch block
                                e.printStackTrace()
                            } catch (e: OutOfMemoryError) {
                                e.printStackTrace()
                            } catch (e: JSchException) {
                                e.printStackTrace()
                            } catch (e: SftpException) {
                                e.printStackTrace()
                            }
                            return null
                        }
                    }.execute()
                    return null
                }

                private fun FileSaveInLocalSDCard(file: File): String {
                    // TODO Auto-generated method stub
                    var imagePath = ""
                    val mf = Environment.getExternalStorageDirectory()
                    val storePath = mf.absoluteFile.toString() + "/Splash/upload/"
                    val dirFile = File(storePath)
                    dirFile.mkdirs()
                    val destfile = File(dirFile, file.name)
                    imagePath = storePath + file.name
                    try {
                        val copyFileValue = copyFile(file, destfile)
                    } catch (e1: IOException) {
                        // TODO Auto-generated catch block
                        e1.printStackTrace()
                    }
                    return imagePath
                }

                @Throws(IOException::class)
                fun copyFile(sourceFile: File?, destFile: File): Boolean {
                    if (!destFile.exists()) {
                        destFile.createNewFile()
                        var source: FileChannel? = null
                        var destination: FileChannel? = null
                        try {
                            source = FileInputStream(sourceFile).channel
                            destination = FileOutputStream(destFile).channel
                            destination.transferFrom(source, 0, source.size())
                        } finally {
                            if (source != null) source.close()
                            if (destination != null) destination.close()
                        }
                        return true
                    }
                    return false
                }
            }
        }

    }
}
