package com.autodrive.app

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlin.math.roundToInt

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        title = "ตั้งค่า"
        val s = Settings(this)

        bindSwitch(R.id.sw_voice, s.voiceEnabled) { s.voiceEnabled = it }
        bindSwitch(R.id.sw_beep, s.beepEnabled) { s.beepEnabled = it }
        bindSwitch(R.id.sw_red, s.alertRedLight) { s.alertRedLight = it }
        bindSwitch(R.id.sw_light, s.alertTrafficLight) { s.alertTrafficLight = it }
        bindSwitch(R.id.sw_stop, s.alertStopSign) { s.alertStopSign = it }
        bindSwitch(R.id.sw_ped, s.alertPedestrian) { s.alertPedestrian = it }
        bindSwitch(R.id.sw_fcw, s.alertFcw) { s.alertFcw = it }

        bindSlider(R.id.sl_warn, R.id.lbl_warn, "ระยะเริ่มเตือน (ชะลอ)", "ม.", s.warnDist) { s.warnDist = it }
        bindSlider(R.id.sl_crit, R.id.lbl_crit, "ระยะอันตราย (เบรก)", "ม.", s.critDist) { s.critDist = it }
        bindSlider(R.id.sl_hfov, R.id.lbl_hfov, "มุมมองกล้อง (HFOV)", "°", s.hfov) { s.hfov = it }
        bindSlider(R.id.sl_cd, R.id.lbl_cd, "เว้นระยะพูดซ้ำ", "วิ", s.cooldownSec) { s.cooldownSec = it }

        findViewById<Button>(R.id.btn_done).setOnClickListener { finish() }
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
