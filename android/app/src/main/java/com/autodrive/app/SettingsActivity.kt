package com.autodrive.app

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlin.math.roundToInt

class SettingsActivity : AppCompatActivity() {

    private var speaker: Speaker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        title = "ตั้งค่า"
        val s = Settings(this)
        speaker = Speaker(this)

        bindSwitch(R.id.sw_voice, s.voiceEnabled) { s.voiceEnabled = it }
        bindSwitch(R.id.sw_beep, s.beepEnabled) { s.beepEnabled = it }

        // ระดับเสียง (0–100% -> เก็บเป็น 0.0–1.0)
        val slVol = findViewById<Slider>(R.id.sl_vol)
        val lblVol = findViewById<TextView>(R.id.lbl_vol)
        val initVol = ((s.volume * 100 / 5f).roundToInt() * 5).coerceIn(0, 100)
        slVol.value = initVol.toFloat()
        lblVol.text = "ระดับเสียง: $initVol%"
        slVol.addOnChangeListener { _, value, _ ->
            lblVol.text = "ระดับเสียง: ${value.toInt()}%"
            s.volume = value / 100f
        }

        // ปุ่มทดสอบเสียงพูด
        findViewById<MaterialButton>(R.id.btn_test).setOnClickListener {
            speaker?.test(s.beepEnabled, s.volume)
        }
        bindSwitch(R.id.sw_red, s.alertRedLight) { s.alertRedLight = it }
        bindSwitch(R.id.sw_light, s.alertTrafficLight) { s.alertTrafficLight = it }
        bindSwitch(R.id.sw_stop, s.alertStopSign) { s.alertStopSign = it }
        bindSwitch(R.id.sw_ped, s.alertPedestrian) { s.alertPedestrian = it }
        bindSwitch(R.id.sw_fcw, s.alertFcw) { s.alertFcw = it }
        bindSwitch(R.id.sw_go, s.alertGoReminder) { s.alertGoReminder = it }
        bindSwitch(R.id.sw_speed, s.showSpeed) { s.showSpeed = it }
        bindSwitch(R.id.sw_overspeed, s.alertOverspeed) { s.alertOverspeed = it }
        bindSwitch(R.id.sw_gap, s.alertTimeGap) { s.alertTimeGap = it }

        // ความเร็วจำกัด (40–160 step 10)
        val slLimit = findViewById<Slider>(R.id.sl_limit)
        val lblLimit = findViewById<TextView>(R.id.lbl_limit)
        val initLimit = ((s.speedLimit / 10f).roundToInt() * 10).coerceIn(40, 160)
        slLimit.value = initLimit.toFloat()
        lblLimit.text = "ความเร็วจำกัด: $initLimit km/h"
        slLimit.addOnChangeListener { _, value, _ ->
            lblLimit.text = "ความเร็วจำกัด: ${value.toInt()} km/h"
            s.speedLimit = value.toInt()
        }

        bindSwitch(R.id.sw_manual, s.manualLane) { s.manualLane = it }
        bindSwitch(R.id.sw_auto, s.autoLane) { s.autoLane = it }
        bindSwitch(R.id.sw_lane, s.egoLaneOnly) { s.egoLaneOnly = it }

        findViewById<MaterialButton>(R.id.btn_calibrate).setOnClickListener {
            startActivity(android.content.Intent(this, CalibrateActivity::class.java))
        }

        // ประสิทธิภาพ
        bindSwitch(R.id.sw_model, s.modelFast) { s.modelFast = it }

        val slFps = findViewById<Slider>(R.id.sl_fps)
        val lblFps = findViewById<TextView>(R.id.lbl_fps)
        val initFps = ((s.maxFps / 5f).roundToInt() * 5).coerceIn(5, 30)
        slFps.value = initFps.toFloat()
        lblFps.text = if (initFps >= 30) "FPS สูงสุด: ไม่จำกัด" else "FPS สูงสุด: $initFps"
        slFps.addOnChangeListener { _, value, _ ->
            val n = value.toInt()
            lblFps.text = if (n >= 30) "FPS สูงสุด: ไม่จำกัด" else "FPS สูงสุด: $n"
            s.maxFps = n
        }

        // วิธีรีเซ็ตการเตือน
        val grp = findViewById<MaterialButtonToggleGroup>(R.id.grp_reset)
        grp.check(when (s.resetMode) { 1 -> R.id.rm1; 2 -> R.id.rm2; else -> R.id.rm0 })
        grp.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) s.resetMode = when (checkedId) { R.id.rm1 -> 1; R.id.rm2 -> 2; else -> 0 }
        }

        // ความกว้างเลน (15–60% step 5 -> เก็บเป็นสัดส่วน 0.15–0.60) — ต้องปัดให้ลงตัวกับ step ไม่งั้น Slider crash
        val slLane = findViewById<Slider>(R.id.sl_lane)
        val lblLane = findViewById<TextView>(R.id.lbl_lane)
        val initLane = ((s.laneWidth * 100 / 5f).roundToInt() * 5).coerceIn(15, 60)
        slLane.value = initLane.toFloat()
        lblLane.text = "ความกว้างเลน: $initLane%"
        slLane.addOnChangeListener { _, value, _ ->
            lblLane.text = "ความกว้างเลน: ${value.toInt()}%"
            s.laneWidth = value / 100f
        }

        bindSlider(R.id.sl_warn, R.id.lbl_warn, "ระยะเริ่มเตือน (ชะลอ)", "ม.", s.warnDist) { s.warnDist = it }
        bindSlider(R.id.sl_crit, R.id.lbl_crit, "ระยะอันตราย (เบรก)", "ม.", s.critDist) { s.critDist = it }
        bindSlider(R.id.sl_hfov, R.id.lbl_hfov, "มุมมองกล้อง (HFOV)", "°", s.hfov) { s.hfov = it }
        bindSlider(R.id.sl_cd, R.id.lbl_cd, "เว้นระยะพูดซ้ำ", "วิ", s.cooldownSec) { s.cooldownSec = it }

        findViewById<Button>(R.id.btn_done).setOnClickListener { finish() }
    }

    override fun onDestroy() {
        super.onDestroy()
        speaker?.shutdown()
    }

    private fun bindSwitch(id: Int, init: Boolean, onChange: (Boolean) -> Unit) {
        val sw = findViewById<SwitchMaterial>(id)
        sw.isChecked = init
        sw.setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    private fun bindSlider(slId: Int, lblId: Int, label: String, unit: String, init: Float, onChange: (Float) -> Unit) {
        val sl = findViewById<Slider>(slId)
        val lbl = findViewById<TextView>(lblId)
        val v0 = init.roundToInt().toFloat().coerceIn(sl.valueFrom, sl.valueTo)
        sl.value = v0
        lbl.text = "$label: ${v0.toInt()} $unit"
        sl.addOnChangeListener { _, value, _ ->
            lbl.text = "$label: ${value.toInt()} $unit"
            onChange(value)
        }
    }
}
