package com.autodrive.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
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
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** โหมดตรวจง่วง/หลับใน — ใช้กล้องหน้าจับการหลับตา/หาว แล้วเตือน */
class DrowsinessActivity : AppCompatActivity() {

    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var exec: ExecutorService
    private var landmarker: FaceLandmarker? = null
    private var speaker: Speaker? = null

    private var closedSinceNs = 0L
    private var lastFaceNs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_drowsiness)
        preview = findViewById(R.id.drowsy_preview)
        status = findViewById(R.id.drowsy_status)
        exec = Executors.newSingleThreadExecutor()
        speaker = Speaker(this)
        setupLandmarker()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) startCamera()
        else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 12)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) startCamera()
        else { Toast.makeText(this, "ต้องอนุญาตกล้อง", Toast.LENGTH_LONG).show(); finish() }
    }

    private fun setupLandmarker() {
        val base = BaseOptions.builder().setModelAssetPath("face_landmarker.task").build()
        val opts = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.IMAGE)
            .setNumFaces(1)
            .setOutputFaceBlendshapes(true)
            .build()
        landmarker = FaceLandmarker.createFromOptions(this, opts)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val p = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(exec) { analyze(it) }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, p, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(proxy: ImageProxy) {
        val lm = landmarker
        if (lm == null) { proxy.close(); return }
        try {
            val bmp = proxy.toBitmap2()
            val result = lm.detect(BitmapImageBuilder(bmp).build())
            val bs = result.faceBlendshapes()
            var eyesClosed = false
            var yawning = false
            var hasFace = false
            if (bs.isPresent && bs.get().isNotEmpty()) {
                hasFace = true
                val cats = bs.get()[0]
                val l = cats.firstOrNull { it.categoryName() == "eyeBlinkLeft" }?.score() ?: 0f
                val r = cats.firstOrNull { it.categoryName() == "eyeBlinkRight" }?.score() ?: 0f
                val jaw = cats.firstOrNull { it.categoryName() == "jawOpen" }?.score() ?: 0f
                eyesClosed = (l + r) / 2f > 0.5f
                yawning = jaw > 0.55f
            }
            evaluate(hasFace, eyesClosed, yawning)
        } catch (_: Exception) {
        } finally {
            proxy.close()
        }
    }

    private fun evaluate(hasFace: Boolean, eyesClosed: Boolean, yawning: Boolean) {
        val now = System.nanoTime()
        if (hasFace) lastFaceNs = now
        val faceRecent = now - lastFaceNs < 2_000_000_000L

        if (eyesClosed) {
            if (closedSinceNs == 0L) closedSinceNs = now
        } else closedSinceNs = 0L

        val closedMs = if (closedSinceNs > 0L) (now - closedSinceNs) / 1_000_000L else 0L
        val drowsy = closedMs > 1200L

        val (txt, col) = when {
            !faceRecent -> "ไม่พบใบหน้า" to Color.LTGRAY
            drowsy -> "ง่วง! ตื่น!" to Color.parseColor("#FF2D55")
            yawning -> "กำลังหาว — พัก" to Color.parseColor("#FFCC00")
            eyesClosed -> "ตาเริ่มปิด" to Color.parseColor("#FFCC00")
            else -> "ปกติ" to Color.parseColor("#22FF88")
        }
        runOnUiThread { status.text = txt; status.setTextColor(col) }

        if (drowsy) {
            speaker?.alert("drowsy", "ตื่น! คุณกำลังง่วง พักก่อน", "Wake up, you are drowsy", true, true, 3000L, 1f)
        } else if (yawning) {
            speaker?.alert("yawn", "คุณดูเหนื่อย ควรพัก", "You look tired, take a break", true, true, 8000L, 1f)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        exec.shutdown()
        landmarker?.close()
        speaker?.shutdown()
    }

    /** RGBA_8888 ImageProxy -> Bitmap แนวตั้ง */
    private fun ImageProxy.toBitmap2(): Bitmap {
        val plane = planes[0]
        val rowPadding = plane.rowStride - plane.pixelStride * width
        val bmp = Bitmap.createBitmap(width + rowPadding / plane.pixelStride, height, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(plane.buffer)
        val cropped = if (rowPadding == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, width, height)
        val rot = imageInfo.rotationDegrees
        if (rot == 0) return cropped
        val m = Matrix().apply { postRotate(rot.toFloat()) }
        return Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, m, true)
    }
}
