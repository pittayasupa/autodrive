package com.autodrive.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
    private var speaker: Speaker? = null
    private val gate = EventGate(10)

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

        setupDetector()

        if (hasCameraPermission()) startCamera()
        else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAM)
    }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasCameraPermission()) startCamera()
        else {
            Toast.makeText(this, "ต้องอนุญาตใช้กล้องเพื่อใช้งาน", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun setupDetector() {
        val base = BaseOptions.builder()
            .setModelAssetPath("efficientdet_lite0.tflite")
            .build()
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.IMAGE)
            .setScoreThreshold(0.4f)
            .setMaxResults(25)
            .build()
        detector = ObjectDetector.createFromOptions(this, options)
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

            provider.unbindAll()
            provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(proxy: ImageProxy) {
        val det = detector
        if (det == null) { proxy.close(); return }
        try {
            val bitmap = proxy.toUprightBitmap()
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = det.detect(mpImage)
            val adasResult = adas.process(result, bitmap)
            overlay.post {
                overlay.setResults(adasResult, bitmap.width, bitmap.height)
                handleAudio(adasResult)
            }
        } catch (_: Exception) {
            // ข้ามเฟรมที่ผิดพลาด
        } finally {
            proxy.close()
        }
    }

    private data class Ev(val key: String, val present: Boolean, val th: String, val en: String)

    /**
     * เตือนเสียงแบบ edge-trigger: เตือนครั้งเดียวตอนเริ่มเจอ, reset เมื่อหายไป
     * เรียก gate.check ทุก event ทุกเฟรม (อัปเดตสถานะ) แล้วพูดเฉพาะตัวสำคัญสุดที่เพิ่งเริ่มเกิด
     */
    private fun handleAudio(a: AdasResult) {
        val sp = speaker ?: return
        val v = settings.voiceEnabled
        val b = settings.beepEnabled
        if (!v && !b) return
        val cd = (settings.cooldownSec * 1000).toLong()
        val vol = settings.volume

        // เรียงตามความสำคัญ (สูง -> ต่ำ)
        val events = listOf(
            Ev("ped", a.pedestrian && settings.alertPedestrian, "ระวัง คนข้ามถนน เบรก", "Pedestrian, brake"),
            Ev("red", a.redLight && settings.alertRedLight, "ไฟแดงข้างหน้า เตรียมหยุด", "Red light, prepare to stop"),
            Ev("fcw", a.leadLevel == Level.CRIT && settings.alertFcw, "ระวัง รถข้างหน้าใกล้มาก เบรก", "Car too close, brake"),
            Ev("stop", a.stopSign && settings.alertStopSign, "ป้ายหยุดข้างหน้า", "Stop sign ahead"),
            Ev("light", a.trafficLight && !a.redLight && settings.alertTrafficLight, "มีไฟจราจรข้างหน้า ระวัง", "Traffic light ahead"),
            Ev("slow", a.leadLevel == Level.WARN && settings.alertFcw, "ชะลอ รักษาระยะ", "Slow down, keep distance")
        )

        var fired = false
        for (e in events) {
            val eligible = gate.check(e.key, e.present)   // ต้องเรียกทุก event เพื่ออัปเดต reset
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
