package com.autodrive.app

import android.content.Context
import android.content.SharedPreferences

/** เก็บการตั้งค่าทั้งหมดลง SharedPreferences (อ่าน/เขียนสดทันที) */
class Settings(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("autodrive", Context.MODE_PRIVATE)

    private fun b(k: String, d: Boolean) = p.getBoolean(k, d)
    private fun setB(k: String, v: Boolean) = p.edit().putBoolean(k, v).apply()
    private fun f(k: String, d: Float) = p.getFloat(k, d)
    private fun setF(k: String, v: Float) = p.edit().putFloat(k, v).apply()

    // เสียง
    var voiceEnabled: Boolean get() = b("voice", true); set(v) = setB("voice", v)
    var beepEnabled: Boolean get() = b("beep", true); set(v) = setB("beep", v)
    var volume: Float get() = f("volume", 0.85f); set(v) = setF("volume", v)   // 0.0–1.0

    // ประเภทการแจ้งเตือน
    var alertRedLight: Boolean get() = b("a_red", true); set(v) = setB("a_red", v)
    var alertTrafficLight: Boolean get() = b("a_light", true); set(v) = setB("a_light", v)
    var alertStopSign: Boolean get() = b("a_stop", true); set(v) = setB("a_stop", v)
    var alertPedestrian: Boolean get() = b("a_ped", true); set(v) = setB("a_ped", v)
    var alertFcw: Boolean get() = b("a_fcw", true); set(v) = setB("a_fcw", v)

    // เลนของเรา
    var egoLaneOnly: Boolean get() = b("ego", true); set(v) = setB("ego", v)
    var laneWidth: Float get() = f("lanew", 0.34f); set(v) = setF("lanew", v)   // สัดส่วนกว้างจอ 0.15–0.6

    // ค่าตัวเลข
    var warnDist: Float get() = f("warn", 12f); set(v) = setF("warn", v)
    var critDist: Float get() = f("crit", 7f); set(v) = setF("crit", v)
    var hfov: Float get() = f("hfov", 62f); set(v) = setF("hfov", v)
    var cooldownSec: Float get() = f("cooldown", 5f); set(v) = setF("cooldown", v)
}
