package com.autodrive.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import kotlin.math.tan

/** ระดับความรุนแรง -> ใช้กำหนดสี */
enum class Level { NORMAL, WARN, CRIT }

/** กล่องที่จะวาด (พิกัดเป็น pixel ของภาพ input) */
data class DetBox(
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val label: String, val distM: Float?, val level: Level
)

/** ผลรวมต่อเฟรม + ธงเหตุการณ์สำหรับขับเสียงเตือน */
data class AdasResult(
    val boxes: List<DetBox>,
    val command: String,
    val sub: String,
    val level: Level,
    val leadLevel: Level,      // ระดับจากรถคันหน้าในเลนเราเท่านั้น (สำหรับเสียง FCW)
    val leadDistM: Float,
    val ttcSec: Float?,
    val redLight: Boolean,
    val trafficLight: Boolean,
    val stopSign: Boolean,
    val pedestrian: Boolean,
    val lightColor: String?,
    val laneLeft: Float,       // ขอบเลนซ้าย (pixel ของภาพ) สำหรับวาดเส้น
    val laneRight: Float
)

/**
 * ตรรกะ ADAS: ประมาณระยะกล้องเดียว (pinhole) + แยกสีไฟจราจร + ตัดสินใจคำสั่ง
 * อ่านค่าตั้งค่าสดจาก Settings (เปลี่ยนในแอปแล้วมีผลทันที)
 */
class Adas(private val s: Settings) {

    private val realHeights = mapOf(
        "person" to 1.7f, "bicycle" to 1.1f, "car" to 1.5f,
        "motorcycle" to 1.1f, "bus" to 3.2f, "truck" to 3.5f
    )
    private val vehicles = setOf("car", "bus", "truck", "motorcycle")
    private val vulnerable = setOf("person", "bicycle")
    private val ttcWarn = 2.5f
    private val ttcCrit = 1.2f
    private val pedCrit = 15f

    private var lastLead = 999f
    private var lastTimeNs = 0L

    fun process(result: ObjectDetectorResult, bmp: Bitmap): AdasResult {
        val imgW = bmp.width
        val imgH = bmp.height
        val focal = (imgW / 2.0) / tan(Math.toRadians(s.hfov.toDouble() / 2.0))
        // เลนของเรา = แถบกลางจอ (ปรับความกว้างได้); ปิด egoLaneOnly = ครอบคลุมเกือบเต็มจอ
        val half = (if (s.egoLaneOnly) s.laneWidth else 0.9f) / 2f
        val laneL = (0.5f - half) * imgW
        val laneR = (0.5f + half) * imgW
        val warnDist = s.warnDist
        val critDist = s.critDist

        val boxes = ArrayList<DetBox>()
        var leadDist = 999f
        var pedInLane = false
        var trafficLight = false
        var stopSign = false
        var redLight = false
        var lightColor: String? = null

        for (d in result.detections()) {
            val cat = d.categories().firstOrNull() ?: continue
            val name = cat.categoryName()
            val score = cat.score()
            val r = d.boundingBox()
            val cx = (r.left + r.right) / 2f
            val boxH = r.bottom - r.top

            val realH = realHeights[name]
            var dist: Float? = null
            if (realH != null && boxH > 1f) dist = (focal.toFloat() * realH) / boxH

            val inLane = cx in laneL..laneR
            var level = Level.NORMAL
            var label = "$name ${"%.2f".format(score)}"

            when {
                name in vulnerable && dist != null && dist < pedCrit && inLane -> {
                    pedInLane = true; level = Level.CRIT
                }
                name == "traffic light" -> {
                    trafficLight = true
                    val color = classifyLight(bmp, r)
                    lightColor = color
                    when (color) {
                        "red" -> { redLight = true; level = Level.CRIT; label = "RED light ${"%.2f".format(score)}" }
                        "green" -> { level = Level.NORMAL; label = "GREEN light ${"%.2f".format(score)}" }
                        "yellow" -> { level = Level.WARN; label = "YELLOW light ${"%.2f".format(score)}" }
                        else -> { level = Level.WARN }
                    }
                }
                name == "stop sign" -> { stopSign = true; level = Level.WARN }
                // นับเป็น "รถคันหน้า" เฉพาะในเลนเรา และอยู่ครึ่งล่างของจอ (ตัดรถไกล/ข้ามแยกออก)
                name in vehicles && inLane && r.bottom > 0.40f * imgH -> {
                    if (dist != null && dist < leadDist) leadDist = dist
                    level = when {
                        dist != null && dist < critDist -> Level.CRIT
                        dist != null && dist < warnDist -> Level.WARN
                        else -> Level.NORMAL
                    }
                }
            }

            val showDist = if (name in realHeights) dist else null
            boxes.add(DetBox(r.left, r.top, r.right, r.bottom, label, showDist, level))
        }

        // TTC จากการเปลี่ยนแปลงระยะรถคันหน้า
        val now = System.nanoTime()
        var ttc: Float? = null
        if (lastTimeNs != 0L && leadDist < 900f && lastLead < 900f) {
            val dt = (now - lastTimeNs) / 1e9
            val closing = lastLead - leadDist
            if (dt > 0 && closing > 0.1f) ttc = (leadDist / (closing / dt)).toFloat()
        }
        lastLead = leadDist
        lastTimeNs = now

        // ระดับจากรถคันหน้า (ในเลนเรา) อย่างเดียว -> ใช้ขับเสียง FCW แยกจากไฟแดง/คนข้าม
        val leadLevel = when {
            leadDist < critDist || (ttc != null && ttc < ttcCrit) -> Level.CRIT
            leadDist < warnDist || (ttc != null && ttc < ttcWarn) -> Level.WARN
            else -> Level.NORMAL
        }

        val (cmd, sub, level) = decide(leadDist, ttc, pedInLane, redLight, trafficLight, stopSign, lightColor, warnDist, critDist)
        return AdasResult(
            boxes, cmd, sub, level, leadLevel, leadDist, ttc,
            redLight, trafficLight, stopSign, pedInLane, lightColor, laneL, laneR
        )
    }

    private fun decide(
        lead: Float, ttc: Float?, ped: Boolean, red: Boolean, light: Boolean, stop: Boolean,
        lightColor: String?, warnDist: Float, critDist: Float
    ): Triple<String, String, Level> {
        if (ped) return Triple("เบรก! คนข้ามถนน", "EMERGENCY STOP", Level.CRIT)
        if (red) return Triple("ไฟแดงข้างหน้า — เตรียมหยุด", "RED LIGHT - STOP", Level.CRIT)
        if (lead < critDist || (ttc != null && ttc < ttcCrit))
            return Triple("เบรก! รถหน้าใกล้มาก", "FORWARD COLLISION", Level.CRIT)
        if (lead < warnDist || (ttc != null && ttc < ttcWarn))
            return Triple("ชะลอ — รักษาระยะ", "SLOW DOWN", Level.WARN)
        if (stop) return Triple("ป้ายหยุดข้างหน้า", "STOP SIGN", Level.WARN)
        when (lightColor) {
            "yellow" -> return Triple("ไฟเหลืองข้างหน้า — ระวัง", "YELLOW LIGHT", Level.WARN)
            "green" -> return Triple("ไฟเขียว — ไปได้", "GREEN LIGHT", Level.NORMAL)
        }
        if (light) return Triple("มีไฟจราจรข้างหน้า", "TRAFFIC LIGHT", Level.WARN)
        return Triple("ขับปกติ", "CRUISING", Level.NORMAL)
    }

    /** ดูสีไฟจราจรจากพิกเซลในกล่อง (สุ่มกริด HSV) -> red / yellow / green / unknown */
    private fun classifyLight(bmp: Bitmap, box: RectF): String {
        val x1 = box.left.toInt().coerceIn(0, bmp.width - 1)
        val y1 = box.top.toInt().coerceIn(0, bmp.height - 1)
        val x2 = box.right.toInt().coerceIn(x1 + 1, bmp.width)
        val y2 = box.bottom.toInt().coerceIn(y1 + 1, bmp.height)
        if (x2 - x1 < 3 || y2 - y1 < 3) return "unknown"

        var red = 0; var green = 0; var yellow = 0; var n = 0
        val hsv = FloatArray(3)
        val stepX = ((x2 - x1) / 12).coerceAtLeast(1)
        val stepY = ((y2 - y1) / 24).coerceAtLeast(1)

        var y = y1
        while (y < y2) {
            var x = x1
            while (x < x2) {
                Color.colorToHSV(bmp.getPixel(x, y), hsv)
                val h = hsv[0]; val sat = hsv[1]; val v = hsv[2]
                if (sat > 0.45f && v > 0.45f) {
                    when {
                        h < 20f || h > 340f -> red++
                        h in 40f..70f -> yellow++
                        h in 80f..170f -> green++
                    }
                }
                n++
                x += stepX
            }
            y += stepY
        }
        if (n == 0) return "unknown"
        val maxc = maxOf(red, green, yellow)
        if (maxc < n * 0.04f) return "unknown"   // ไม่มีสีเด่นพอ
        return when (maxc) { red -> "red"; green -> "green"; else -> "yellow" }
    }
}
