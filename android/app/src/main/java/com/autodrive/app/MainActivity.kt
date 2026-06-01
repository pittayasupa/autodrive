package com.autodrive.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Environment
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import java.io.File
import kotlin.math.sqrt
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var settings: Settings
    private lateinit var adas: Adas
    private var detector: ObjectDetector? = null
    private var detectorFast: Boolean? = null
    private var speaker: Speaker? = null
    private val gate = EventGate(12)
    private var lastProcNs = 0L

    // GPS / ความเร็ว
    private var locationManager: LocationManager? = null
    private var speedKmh = -1f          // -1 = ยังไม่มีค่า GPS
    private var cameraStarted = false
    private var locationStarted = false
    // สถานะสำหรับเตือน "ไปได้แล้ว"
    private var prevLightColor: String? = null
    private var prevLead = 999f

    private val locListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            speedKmh = if (loc.hasSpeed()) loc.speed * 3.6f else speedKmh
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    // dashcam
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var isRecording = false
    private var recBtn: MaterialButton? = null

    // g-sensor (เบรกแรง)
    private var sensorManager: SensorManager? = null
    private var lastBrakeNs = 0L
    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val m = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
            if (m > 4.0f) {   // ความเร่ง/หน่วงแรง (เบรก/กระแทก)
                val now = System.nanoTime()
                if (now - lastBrakeNs > 3_000_000_000L && settings.dashcamHardBrake) {
                    lastBrakeNs = now
                    speaker?.alert("brake", "ตรวจพบเบรกแรง บันทึกเหตุการณ์", "Hard braking detected", settings.voiceEnabled, settings.beepEnabled, 0L, settings.volume)
                    if (!isRecording) startRecording()
                }
            }
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.camera_preview)
        overlay = findViewById(R.id.overlay)
        settings = Settings(this)
        adas = Adas(settings)
        speaker = Speaker(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        findViewById<ImageButton>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        recBtn = findViewById(R.id.btn_rec)
        recBtn?.setOnClickListener { toggleRecording() }

        locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        ensureDetector()
        requestNeededPermissions()
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun requestNeededPermissions() {
        val need = ArrayList<String>()
        if (!granted(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA)
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) need.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (need.isEmpty()) onPermsReady()
        else ActivityCompat.requestPermissions(this, need.toTypedArray(), REQ_CAM)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        onPermsReady()
    }

    private fun onPermsReady() {
        if (!granted(Manifest.permission.CAMERA)) {
            Toast.makeText(this, "ต้องอนุญาตใช้กล้องเพื่อใช้งาน", Toast.LENGTH_LONG).show()
            finish(); return
        }
        if (!cameraStarted) { startCamera(); cameraStarted = true }
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION) && !locationStarted) startLocation()
    }

    private fun startLocation() {
        try {
            locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locListener)
            locationStarted = true
        } catch (_: SecurityException) {}
    }

    /** สร้าง/สร้างใหม่ detector ตามโหมดที่เลือก (เร็ว=Lite0 / แม่น=Lite2) */
    private fun ensureDetector() {
        val fast = settings.modelFast
        if (detector != null && detectorFast == fast) return
        detector?.close()
        val model = if (fast) "efficientdet_lite0.tflite" else "efficientdet_lite2.tflite"
        val base = BaseOptions.builder().setModelAssetPath(model).build()
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.IMAGE)
            .setScoreThreshold(0.3f)
            .setMaxResults(30)
            .build()
        detector = ObjectDetector.createFromOptions(this, options)
        detectorFast = fast
    }

    override fun onResume() {
        super.onResume()
        ensureDetector()   // รับค่าโมเดลใหม่ถ้าเปลี่ยนในตั้งค่า
        sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let {
            sensorManager?.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(sensorListener)
        if (isRecording) stopRecording()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(cameraExecutor) { proxy -> analyze(proxy) }

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
                .build()
            val vc = VideoCapture.withOutput(recorder)

            provider.unbindAll()
            try {
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, vc
                )
                videoCapture = vc
            } catch (e: Exception) {
                // อุปกรณ์ไม่รองรับ preview+analysis+video พร้อมกัน -> ตัด video ออก
                videoCapture = null
                recBtn?.visibility = android.view.View.GONE
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }
            if (videoCapture != null && settings.dashcamAuto && !isRecording) startRecording()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleRecording() {
        if (videoCapture == null) {
            Toast.makeText(this, "อุปกรณ์นี้บันทึกวิดีโอพร้อมตรวจจับไม่ได้", Toast.LENGTH_SHORT).show()
            return
        }
        if (isRecording) stopRecording() else startRecording()
    }

    private fun startRecording() {
        val vc = videoCapture ?: return
        if (isRecording) return
        val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        val file = File(dir, "AutoDrive_${System.currentTimeMillis()}.mp4")
        val out = FileOutputOptions.Builder(file).build()
        recording = vc.output.prepareRecording(this, out)
            .start(ContextCompat.getMainExecutor(this)) { ev ->
                when (ev) {
                    is VideoRecordEvent.Start -> { isRecording = true; updateRecBtn() }
                    is VideoRecordEvent.Finalize -> {
                        isRecording = false; updateRecBtn()
                        if (!ev.hasError()) Toast.makeText(this, "บันทึกแล้ว: ${file.name}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        updateRecBtn()
    }

    private fun stopRecording() {
        recording?.stop()
        recording = null
    }

    private fun updateRecBtn() {
        recBtn?.text = if (isRecording) "■ REC" else "● REC"
    }

    private fun analyze(proxy: ImageProxy) {
        val det = detector
        if (det == null) { proxy.close(); return }
        // จำกัด FPS การประมวลผลตามตั้งค่า (30 = ไม่จำกัด)
        val maxF = settings.maxFps
        if (maxF in 1..29) {
            val now = System.nanoTime()
            if (now - lastProcNs < 1_000_000_000L / maxF) { proxy.close(); return }
            lastProcNs = now
        }
        try {
            val bitmap = proxy.toUprightBitmap()
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = det.detect(mpImage)
            val adasResult = adas.process(result, bitmap)
            overlay.post {
                overlay.setResults(adasResult, bitmap.width, bitmap.height)
                overlay.setSpeed(speedKmh, settings.showSpeed, settings.speedLimit)
                handleAudio(adasResult)
            }
        } catch (_: Exception) {
            // ข้ามเฟรมที่ผิดพลาด
        } finally {
            proxy.close()
        }
    }

    private data class Ev(
        val key: String, val trigger: Boolean, val onScreen: Boolean,
        val th: String, val en: String
    )

    /**
     * เตือนเสียงแบบ edge-trigger: เตือนครั้งเดียวตอนเริ่มเจอ
     * และ "reset เมื่อวัตถุนั้นหายจากจอจริง ๆ" (onScreen=false ต่อเนื่อง) เท่านั้น
     */
    private fun handleAudio(a: AdasResult) {
        val sp = speaker ?: return
        val v = settings.voiceEnabled
        val b = settings.beepEnabled
        if (!v && !b) return
        val cd = (settings.cooldownSec * 1000).toLong()
        val vol = settings.volume

        val lt = settings.alertTrafficLight
        val anyLight = a.trafficLight          // ยังมีไฟจราจรในจอ
        val leadExists = a.leadDistM < 900f     // ยังมีรถคันหน้าในเลน

        // ----- ความเร็ว / ระยะเวลา -----
        val spd = speedKmh                                   // -1 = ไม่มี GPS
        val overspeed = settings.alertOverspeed && spd >= 0f && spd > settings.speedLimit
        // กฎ 2 วินาที: เวลา = ระยะ / ความเร็ว(m/s)
        var timeGap = -1f
        if (leadExists && spd > 20f) timeGap = a.leadDistM / (spd / 3.6f)
        val tooClose = settings.alertTimeGap && timeGap in 0f..2.0f

        // ----- เตือน "ไปได้แล้ว" (ไฟเขียว / รถคันหน้าเคลื่อน) -----
        val stopped = spd in 0f..5f                          // หยุดนิ่ง (จาก GPS)
        val greenNow = a.trafficLight && a.lightColor == "green"
        val greenJust = prevLightColor == "red" && greenNow
        val leadMoved = stopped && prevLead < 12f && a.leadDistM > prevLead + 6f && a.leadDistM < 900f
        val goTrigger = settings.alertGoReminder && (greenJust || leadMoved)
        if (a.trafficLight) prevLightColor = a.lightColor
        prevLead = a.leadDistM

        // (key, trigger=ควรเตือน, onScreen=ยังเห็นวัตถุชนิดนั้นในจอ, ข้อความ)
        val events = listOf(
            Ev("ped", a.pedestrian && settings.alertPedestrian, a.personOnScreen,
                "ระวัง คนข้ามถนน เบรก", "Pedestrian, brake"),
            Ev("red", a.redLight && settings.alertRedLight, anyLight,
                "ไฟแดงข้างหน้า เตรียมหยุด", "Red light, prepare to stop"),
            Ev("fcw", a.leadLevel == Level.CRIT && settings.alertFcw, leadExists,
                "ระวัง รถข้างหน้าใกล้มาก เบรก", "Car too close, brake"),
            Ev("go", goTrigger, goTrigger,
                "ไปได้แล้ว รถข้างหน้าเคลื่อนแล้ว", "You can go now"),
            Ev("gap", tooClose, tooClose,
                "เว้นระยะ จี้ท้ายเกินไป", "Too close, keep distance"),
            Ev("over", overspeed, overspeed,
                "ความเร็วเกินกำหนด", "Over the speed limit"),
            Ev("yellow", a.lightColor == "yellow" && lt, anyLight,
                "ไฟเหลืองข้างหน้า ระวัง ชะลอ", "Yellow light ahead, slow down"),
            Ev("stop", a.stopSign && settings.alertStopSign, a.stopSign,
                "ป้ายหยุดข้างหน้า", "Stop sign ahead"),
            Ev("light", a.lightColor == "unknown" && lt, anyLight,
                "มีไฟจราจรข้างหน้า ระวัง", "Traffic light ahead"),
            Ev("green", a.lightColor == "green" && lt, anyLight,
                "ไฟเขียว ไปได้", "Green light, go"),
            Ev("slow", a.leadLevel == Level.WARN && settings.alertFcw, leadExists,
                "ชะลอ รักษาระยะ", "Slow down, keep distance")
        )

        val mode = settings.resetMode
        if (mode == 2) {
            // โหมด "พูดซ้ำตามเวลา": พูดเมื่อมีเงื่อนไข แล้วเว้นช่วงด้วย cooldown ของ Speaker
            for (e in events) {
                if (e.trigger) { sp.alert(e.key, e.th, e.en, v, b, cd, vol); break }
            }
            return
        }
        // โหมด gate: 0=reset เมื่อวัตถุหายจากจอ, 1=reset เมื่อเงื่อนไขหมด
        var fired = false
        for (e in events) {
            val onScreen = if (mode == 0) e.onScreen else e.trigger
            val eligible = gate.check(e.key, e.trigger, onScreen)   // เรียกทุก event เพื่ออัปเดต reset
            if (!fired && eligible) {
                sp.alert(e.key, e.th, e.en, v, b, cd, vol)
                gate.markFired(e.key)
                fired = true
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        detector?.close()
        speaker?.shutdown()
        try { locationManager?.removeUpdates(locListener) } catch (_: Exception) {}
        try { sensorManager?.unregisterListener(sensorListener) } catch (_: Exception) {}
        stopRecording()
    }

    companion object { private const val REQ_CAM = 10 }
}

/** แปลง ImageProxy (RGBA_8888) เป็น Bitmap แนวตั้งถูกต้องตามการหมุนของกล้อง */
private fun ImageProxy.toUprightBitmap(): Bitmap {
    val plane = planes[0]
    val pixelStride = plane.pixelStride
    val rowStride = plane.rowStride
    val rowPadding = rowStride - pixelStride * width
    val bmp = Bitmap.createBitmap(
        width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
    )
    bmp.copyPixelsFromBuffer(plane.buffer)
    val cropped = if (rowPadding == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, width, height)
    val rot = imageInfo.rotationDegrees
    if (rot == 0) return cropped
    val m = Matrix().apply { postRotate(rot.toFloat()) }
    return Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, m, true)
}
