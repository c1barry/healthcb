package edu.ucsd.healthcb

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
import android.text.SpannableStringBuilder
import android.util.Log
import android.util.Range
import android.view.Surface
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.annotation.WorkerThread
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.androidplot.util.Redrawer
import com.androidplot.xy.*
import com.github.psambit9791.jdsp.filter.Butterworth
import com.github.psambit9791.jdsp.misc.UtilMethods
import com.jcraft.jsch.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
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


class DynaActivity : AppCompatActivity(), SensorEventListener {

    /************************************Sensor and Plotting Related**********************************/
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
    private lateinit var gyroroot: File
    private lateinit var writer: FileWriter
    private lateinit var gyrowriter: FileWriter
    private val REQUEST_EXTERNAL_STORAGE = 1
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
    private val xRange:Int = 1000*3
    private var xaccelSeries = accelModel(xRange, xRange)
    private var yaccelSeries = accelModel(xRange, xRange)
    private var zaccelSeries = accelModel(xRange, xRange)
    private var forceaccelSeries = accelModel(xRange, xRange)
    private var forceaccelval = 0.0
    private var xgyroSeries = accelModel(xRange, xRange)
    private var ygyroSeries = accelModel(xRange, xRange)
    private var zgyroSeries = accelModel(xRange, xRange)
    private var xgyro = 0.0
    private var ygyro = 0.0
    private var zgyro = 0.0
    private var calibrationMode = false
    private var accelCounter = 0
    private var gyroCounter = 0
    private var accelMax = 0.0
    private var accelMin = 0.0
    private var currentChannel = 0

    private var bufferSize = 150
    private var dataBufferQueue: Queue<Double> = LinkedList<Double>()
    private var useFilter = false
    private var useFilter2 = true
    private var flt: Butterworth = Butterworth(30.0)

    private var accelBufferSize = 150
    private var xaccelBufferQueue: Queue<Double> = LinkedList<Double>()
    private var yaccelBufferQueue: Queue<Double> = LinkedList<Double>()
    private var zaccelBufferQueue: Queue<Double> = LinkedList<Double>()
    private var useAccelFilter = true
    private var accelflt: Butterworth = Butterworth(423.0)
    private var accelfltfrq = 423*0.05

    private var gyroBufferSize = 150
    private var xgyroBufferQueue: Queue<Double> = LinkedList<Double>()
    private var ygyroBufferQueue: Queue<Double> = LinkedList<Double>()
    private var zgyroBufferQueue: Queue<Double> = LinkedList<Double>()

    private val recordSeconds = 5000L //total length of recording (length of squeeze is recordSeconds-delayTime
    private val delayTime = 1000L //time during recording that participant is not squeezing

//    private lateinit var view_finder: SurfaceView
    private var vibrateContinuous=true
    private var filestoupload: MutableList<File> = mutableListOf()
    private var userIDnumber = 0
    private var trialNumber = 0
    private var gripStrength= 0.0

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
        setContentView(R.layout.activity_dyna)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        mVibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?
//        mVibrator = vibrationManager.defaultVibrator
        mAccelerometer=sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        mGyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

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
                lifecycleScope.launch{
                    if (vibrateContinuous) {
                        if (Build.MODEL=="Pixel 4"){
                            val vibe = VibrationEffect.createOneShot(1000 * 100, 255)
                            mVibrator?.vibrate(vibe)
                        }else{
                            val vibe = VibrationEffect.createWaveform(longArrayOf(1000,1000), intArrayOf(255,255), 0)
                            mVibrator?.vibrate(vibe)
                        }

                    }else{
                        val vibe = VibrationEffect.createWaveform(longArrayOf(200,200), intArrayOf(255,0),0)
                        mVibrator?.vibrate(vibe)
                    }
                }

//                val vibe = VibrationEffect.createWaveform(longArrayOf(1000*5), intArrayOf(255),0)


//                mVibrator?.vibrate(longArrayOf(500,500,0),0)

            }
            isVibrating = !isVibrating
        }
        OpenCVLoader.initDebug()
        var recordButton: Button = findViewById(R.id.recordButton)
        var CalibrationButton:Button=findViewById(R.id.CalibrationButton)
        var background =findViewById<ConstraintLayout>(R.id.constraint_layout)
        var accelButton:Button=findViewById(R.id.accelButton)
        var idNumberText = findViewById<EditText>(R.id.idNumber)
        var gripStrengthText = findViewById<EditText>(R.id.gripStrength)
        var trialNumberText = findViewById<EditText>(R.id.trialNumber)
        recordButton.setOnClickListener {
            try{
                userIDnumber = idNumberText.text.toString().toInt()
                trialNumber = trialNumberText.text.toString().toInt()
                gripStrength = gripStrengthText.text.toString().toDouble()
            }catch(E:Exception){
                userIDnumber=0
            }


            xaccelMeasurements = floatArrayOf()
            yaccelMeasurements = floatArrayOf()
            zaccelMeasurements = floatArrayOf()
            gyroroot = createNamedFile(this, "gyro", "txt")
            root = createNamedFile(this, "accel", "txt")
            writer = FileWriter(root)
            gyrowriter = FileWriter(gyroroot)
            recordButton.text = "Stop/Save"
            filestoupload.add(gyroroot)
            filestoupload.add(root)
            isRecording = true

            lifecycleScope.launch() {
                Toast.makeText(applicationContext, "Recording Grip", Toast.LENGTH_LONG).show()
                delay(recordSeconds)
                writer.flush()
                gyrowriter.flush()
                writer.close()
                gyrowriter.close()
                recordButton.text = "Record"
                if (filestoupload.size >= 1) {
                    uploadFileArrayToNassftp(filestoupload)
                }
                isRecording = false
            }
        }
        val accelplot = findViewById<XYPlot>(R.id.accelplot)
        val ppgplot = findViewById<XYPlot>(R.id.ppgplot)

        val paint: Paint = guideformatter.linePaint
        paint.setStrokeWidth(3F)
        guideformatter.linePaint=paint

        accelplot.addSeries(forceaccelSeries, xformatter)
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
        var accelChannels = listOf(forceaccelSeries, xgyroSeries, ygyroSeries, zgyroSeries, xaccelSeries, yaccelSeries, zaccelSeries)
        var accelButtonTexts = listOf("force", "x gyro", "y gyro", "z gyro", "x accel", "y accel", "z accel")
        accelButton.text = accelButtonTexts[currentChannel]
        accelButton.setOnClickListener {
            accelplot.removeSeries(accelChannels[currentChannel])
            if (currentChannel == accelChannels.size-1){
                currentChannel=0
            }else{
                currentChannel +=1
            }
            accelButton.text = accelButtonTexts[currentChannel]
            accelplot.addSeries(accelChannels[currentChannel], xformatter)
        }
        CalibrationButton.setOnClickListener{
            CalibrationButton.text="Testing in Progress"
            lifecycleScope.launch{
                accelCounter=0
                gyroCounter = 0
                accelMax = -999.0
                accelMin = 999.0
                calibrationMode = true
                delay(1000)
                calibrationMode = false

                accelplot.removeSeries(accelChannels[currentChannel])
                val plotSeconds = 15
                var calibratedRange = accelCounter*plotSeconds
                var calibratedGyroRange = gyroCounter*plotSeconds
                xgyroSeries = accelModel(calibratedGyroRange, calibratedGyroRange)
                ygyroSeries = accelModel(calibratedGyroRange, calibratedGyroRange)
                zgyroSeries = accelModel(calibratedGyroRange, calibratedGyroRange)
                xaccelSeries = accelModel(calibratedRange, calibratedRange)
                yaccelSeries = accelModel(calibratedRange, calibratedRange)
                zaccelSeries = accelModel(calibratedRange, calibratedRange)
                forceaccelSeries = accelModel(calibratedRange, calibratedRange)
                accelChannels = listOf(forceaccelSeries, xgyroSeries, ygyroSeries, zgyroSeries, xaccelSeries, yaccelSeries, zaccelSeries)
                accelplot.setDomainBoundaries(0, calibratedRange, BoundaryMode.FIXED)
                Log.d(TAG, "accel Sample Rate = ".plus(accelCounter).plus("Hz"))
                Log.d(TAG, "gyro Sample Rate = ".plus(gyroCounter).plus("Hz"))
                accelflt= Butterworth(accelCounter.toDouble())
                accelfltfrq = accelCounter.toDouble()*0.05
                accelBufferSize=(accelCounter/2).toInt()
                accelplot.addSeries(accelChannels[currentChannel], xformatter)

                accelplot.rangeStepValue = 2.0
                accelplot.layoutManager.remove(accelplot.legend)
//                vibrateButton.isEnabled=true


                //First Test (vibrate, record, green screen)
                vibrateButton.performClick()
                recordButton.performClick()
//                delay(delayTime)
                background.setBackgroundColor(Color.GREEN)
                delay(recordSeconds)
                Log.d("vibration check", "white")
                background.setBackgroundColor(Color.WHITE)

                vibrateButton.performClick()
                vibrateContinuous=!vibrateContinuous
                delay(delayTime)
//
//                vibrateButton.performClick()
//                recordButton.performClick()
//                delay(delayTime)
//                background.setBackgroundColor(Color.GREEN)
//                delay(recordSeconds-delayTime)
//                background.setBackgroundColor(Color.WHITE)
//
//                delay(delayTime)
//                vibrateButton.performClick()
//                vibrateContinuous=!vibrateContinuous

                CalibrationButton.text="Start Test"
                trialNumber+=1

            }

        }

    }


    private fun createNamedFile(context: Context,type: String, extension: String): File {
        val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
        var contvibe = ""
        if (vibrateContinuous){
            contvibe="cont"
        }else{
            contvibe="pulse"
        }
        return File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), Build.MODEL.toString().plus("_")
            .plus(type)
            .plus("_")
            .plus(userIDnumber.toString())
            .plus("_")
            .plus(trialNumber)
            .plus("_")
            .plus(gripStrength)
            .plus("_")
            .plus(contvibe)
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
//        if ((!useFilter) || (xaccelBufferQueue.size <accelBufferSize)){
//            xaccelSeries.updateData(y)
//            yaccelSeries.updateData(x)
//            zaccelSeries.updateData(z)
//        }
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
                if (Build.MODEL == "Pixel 4"){
                    val parameters = doubleArrayOf(470.51, -13.06011158,   -79.6883385,    -11.09071467,  7449.08359999,
                        -1903.76515038, -1640.82724184)
                    forceaccelval = parameters[0]+(lastValx*parameters[1])+(lastValy*parameters[2])+(lastValz*parameters[3])+(xgyro*parameters[4])+(ygyro*parameters[5])+(zgyro*parameters[6])
                }else{
                    val parameters = doubleArrayOf(1200.6520, -93.14648391,  -206.7670984,    -98.77187847,  -245.77843109,
                        -1209.21048107,  1541.51684023)
                    forceaccelval = parameters[0]+(lastValx*parameters[1])+(lastValy*parameters[2])+(lastValz*parameters[3])+(xgyro*parameters[4])+(ygyro*parameters[5])+(zgyro*parameters[6])
                }

//            forceaccelSeries.updateData(forceaccelval)
                if (dataBufferQueue.size >= bufferSize) {
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
//            yaccelSeries.updateData(y)
            xgyroBufferQueue.add(x)
            ygyroBufferQueue.add(y)
            zgyroBufferQueue.add(z)
//        if ((!useFilter) || (xaccelBufferQueue.size <accelBufferSize)){
//            xaccelSeries.updateData(y)
//            yaccelSeries.updateData(x)
//            zaccelSeries.updateData(z)
//        }
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
                xgyro = xfilteredSignal.last()
                ygyro = yfilteredSignal.last()
                zgyro = zfilteredSignal.last()
            }

            xgyroSeries.updateData(p0?.values!!.get(0).toDouble())
            ygyroSeries.updateData(p0?.values!!.get(1).toDouble())
            zgyroSeries.updateData(p0?.values!!.get(2).toDouble())
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

        class SFTPClient {
            var context: Context? = null

            companion object {
                private val username = "udcomplab"
                private val host = "137.110.115.58"
                //            private val host = "sftp://137.110.115.58"
                private val password  = "W3AreUbicomp!"
                //            private val host: String = ServerUrl.FTP_HOST
//            private val username: String = ServerUrl.FTP_USERNAME
                private const val remoteDirectory = "Projects/VibroBP/RemoteUploads"
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
