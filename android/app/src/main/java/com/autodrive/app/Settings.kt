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
    private fun i(k: String, d: Int) = p.getInt(k, d)
    private fun setI(k: String, v: Int) = p.edit().putInt(k, v).apply()

    // ประสิทธิภาพ / พฤติกรรม
    var maxFps: Int get() = i("maxfps", 15); set(v) = setI("maxfps", v)          // จำกัดรอบประมวลผล (30 = ไม่จำกัด)
    var modelFast: Boolean get() = b("fast", false); set(v) = setB("fast", v)    // true=Lite0 เร็ว, false=Lite2 แม่น/ไกล
    var resetMode: Int get() = i("resetmode", 0); set(v) = setI("resetmode", v)  // 0=หายจากจอ 1=เงื่อนไขหมด 2=ตามเวลา

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
    var alertGoReminder: Boolean get() = b("a_go", true); set(v) = setB("a_go", v)   // ไฟเขียว/รถคันหน้าไปแล้ว

    // GPS / ความเร็ว
    var showSpeed: Boolean get() = b("spd_show", true); set(v) = setB("spd_show", v)
    var alertOverspeed: Boolean get() = b("spd_over", true); set(v) = setB("spd_over", v)
    var speedLimit: Int get() = i("spd_limit", 90); set(v) = setI("spd_limit", v)
    var alertTimeGap: Boolean get() = b("spd_gap", true); set(v) = setB("spd_gap", v)   // กฎ 2 วินาที

    // เลนของเรา
    var egoLaneOnly: Boolean get() = b("ego", true); set(v) = setB("ego", v)
    var autoLane: Boolean get() = b("autolane", true); set(v) = setB("autolane", v)   // หาเลนเองจากรถคันหน้า
    var laneWidth: Float get() = f("lanew", 0.35f); set(v) = setF("lanew", v)   // สัดส่วนกว้างจอ 0.15–0.6 (ทวีคูณ 0.05)

    // เลนที่ปรับเอง (trapezoid) — สัดส่วน 0–1 ของจอ
    var manualLane: Boolean get() = b("manual", false); set(v) = setB("manual", v)
    var laneBL: Float get() = f("lnbl", 0.28f); set(v) = setF("lnbl", v)   // ล่างซ้าย x
    var laneBR: Float get() = f("lnbr", 0.72f); set(v) = setF("lnbr", v)   // ล่างขวา x
    var laneTL: Float get() = f("lntl", 0.46f); set(v) = setF("lntl", v)   // บนซ้าย x
    var laneTR: Float get() = f("lntr", 0.54f); set(v) = setF("lntr", v)   // บนขวา x
    var laneTopY: Float get() = f("lnty", 0.55f); set(v) = setF("lnty", v) // ขอบบนเลน y

    // ค่าตัวเลข
    var warnDist: Float get() = f("warn", 12f); set(v) = setF("warn", v)
    var critDist: Float get() = f("crit", 7f); set(v) = setF("crit", v)
    var hfov: Float get() = f("hfov", 62f); set(v) = setF("hfov", v)
    var cooldownSec: Float get() = f("cooldown", 5f); set(v) = setF("cooldown", v)
}
