package com.autodrive.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

/** ปรับเส้นเลนด้วยการลาก บนภาพกล้องสด */
class CalibrateActivity : AppCompatActivity() {

    private lateinit var preview: PreviewView
    private lateinit var editor: LaneEditorView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibrate)
        val settings = Settings(this)

        preview = findViewById(R.id.calib_preview)
        editor = findViewById(R.id.lane_editor)
        editor.load(settings)

        findViewById<MaterialButton>(R.id.btn_reset).setOnClickListener { editor.reset() }
        findViewById<MaterialButton>(R.id.btn_save).setOnClickListener {
            editor.save(settings)
            settings.manualLane = true
            Toast.makeText(this, "บันทึกเลนแล้ว (ใช้เลนที่ปรับเอง)", Toast.LENGTH_SHORT).show()
            finish()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) startCamera()
        else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 11)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) startCamera()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val p = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, p)
        }, ContextCompat.getMainExecutor(this))
    }
}
