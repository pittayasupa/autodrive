package com.autodrive.app

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/** หน้าเมนูหลัก (เปิดเป็นหน้าแรก) — กดเริ่มขับขี่ / ตั้งค่า ก่อนเข้ากล้อง */
class HomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        findViewById<MaterialButton>(R.id.btn_start).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btn_help).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("วิธีใช้ AutoDrive")
                .setMessage(
                    "1. ติดมือถือไว้หน้ารถ (แนวนอน) กล้องหันไปข้างหน้า\n" +
                    "2. กด \"เริ่มขับขี่\" แล้วอนุญาตกล้อง\n" +
                    "3. แอปจะตรวจจับ รถ/คน/ไฟจราจร และแจ้งเตือนด้วยเสียง+ภาพ\n\n" +
                    "ตั้งค่าได้: เปิด/ปิดการเตือนแต่ละชนิด, ปรับระยะเตือน, มุมกล้อง\n\n" +
                    "⚠ เป็นเพียงตัวช่วยเตือน ไม่ควบคุมรถจริง — ขับด้วยความระมัดระวังเสมอ"
                )
                .setPositiveButton("เข้าใจแล้ว", null)
                .show()
        }
    }
}
