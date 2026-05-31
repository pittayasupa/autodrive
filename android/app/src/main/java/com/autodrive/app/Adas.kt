package com.autodrive.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import kotlin.math.abs
import kotlin.math.tan

/** ระดับความรุนแรง -> ใช้กำหนดสี */
enum class Level { NORMAL, WARN, CRIT }

data class DetBox(
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val label: String, val distM: Float?, val level: Level
)

data class AdasResult(
    val boxes: List<DetBox>,
    val command: String,
    val sub: String,
    val level: Level,
    val leadLevel: Level,
    val leadDistM: Float,
    val ttcSec: Float?,
    val redLight: Boolean,
    val trafficLight: Boolean,
    val stopSign: Boolean,
    val pedestrian: Boolean,
    val lightColor: String?,
    val laneLeft: Float,
    val laneRight: Float
)

/** วัตถุที่ parse แล้วจาก 1 เฟรม */
private class Det(
    val name: String, val score: Float, val rect: RectF,
    val cx: Float, val boxH: Float, val dist: Float?
)

/**
 * ตรรกะ ADAS: ประมาณระยะกล้องเดียว + แยกสีไฟจราจร + เลนอัตโนมัติ + ตัดสินใจคำสั่ง
 * อ่านค่าตั้งค่าสดจาก Settings
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
    private var laneCenterFrac = 0.5f   // ตำแหน่งกึ่งกลางเลน (อัปเดตอัตโนมัติ)

    fun process(result: ObjectDetectorResult, bmp: Bitmap): AdasResult {
        val imgW = bmp.width
        val imgH = bmp.height
        val focal = (imgW / 2.0) / tan(Math.toRadians(s.hfov.toDouble() / 2.0))
        val warnDist = s.warnDist
        val critDist = s.critDist

        // ---- Pass 1: parse ทุก detection ----
        val dets = ArrayList<Det>()
        for (d in result.detections()) {
            val cat = d.categories().firstOrNull() ?: continue
            val name = cat.categoryName()
            val r = d.boundingBox()
            val boxH = r.bottom - r.top
            val realH = realHeights[name]
            val dist = if (realH != null && boxH > 1f) (focal.toFloat() * realH) / boxH else null
            dets.add(Det(name, cat.score(), r, (r.left + r.right) / 2f, boxH, dist))
        }

        // ---- หาเลน: อัตโนมัติ (ตามรถคันหน้า) หรือกลางจอ ----
        val center = computeLaneCenter(dets, imgW, imgH)
        val half = (if (s.egoLaneOnly) s.laneWidth else 0.9f) / 2f
        val laneL = (center - half).coerceIn(0f, 1f) * imgW
        val laneR = (center + half).coerceIn(0f, 1f) * imgW

        // ---- Pass 2: จัดประเภท + สร้างกล่อง ----
        val boxes = ArrayList<DetBox>()
        var leadDist = 999f
        var pedInLane = false
        var trafficLight = false
        var stopSign = false
        var redLight = false
        var lightColor: String? = null

        for (p in dets) {
            val inLane = p.cx in laneL..laneR
            var level = Level.NORMAL
            var label = "${p.name} ${"%.2f".format(p.score)}"

            when {
                p.name in vulnerable && p.dist != null && p.dist < pedCrit && inLane -> {
                    pedInLane = true; level = Level.CRIT
                }
                p.name == "traffic light" -> {
                    trafficLight = true
                    val color = classifyLight(bmp, p.rect)
                    lightColor = color
                    when (color) {
                        "red" -> { redLight = true; level = Level.CRIT; label = "RED light ${"%.2f".format(p.score)}" }
                        "green" -> { level = Level.NORMAL; label = "GREEN light ${"%.2f".format(p.score)}" }
                        "yellow" -> { level = Level.WARN; label = "YELLOW light ${"%.2f".format(p.score)}" }
                        else -> level = Level.WARN
                    }
                }
                p.name == "stop sign" -> { stopSign = true; level = Level.WARN }
                p.name in vehicles && inLane && p.rect.bottom > 0.40f * imgH -> {
                    if (p.dist != null && p.dist < leadDist) leadDist = p.dist
                    level = when {
                        p.dist != null && p.dist < critDist -> Level.CRIT
                        p.dist != null && p.dist < warnDist -> Level.WARN
                        else -> Level.NORMAL
                    }
                }
            }

            val showDist = if (p.name in realHeights) p.dist else null
            boxes.add(DetBox(p.rect.left, p.rect.top, p.rect.right, p.rect.bottom, label, showDist, level))
        }

        // ---- TTC ----
        val now = System.nanoTime()
        var ttc: Float? = null
        if (lastTimeNs != 0L && leadDist < 900f && lastLead < 900f) {
            val dt = (now - lastTimeNs) / 1e9
            val closing = lastLead - leadDist
            if (dt > 0 && closing > 0.1f) ttc = (leadDist / (closing / dt)).toFloat()
        }
        lastLead = leadDist
        lastTimeNs = now

        val leadLevel = when {
            leadDist < critDist || (ttc != null && ttc < ttcCrit) -> Level.CRIT
            leadDist < warnDist || (ttc != null && ttc < ttcWarn) -> Level.WARN
            else -> Level.NORMAL
        }

        val (cmd, sub, lvl) = decide(leadDist, ttc, pedInLane, redLight, trafficLight, stopSign, lightColor, warnDist, critDist)
        return AdasResult(
            boxes, cmd, sub, lvl, leadLevel, leadDist, ttc,
            redLight, trafficLight, stopSign, pedInLane, lightColor, laneL, laneR
        )
    }

    /** เลนอัตโนมัติ: เลื่อนกึ่งกลางเลนตามรถคันหน้าที่ใกล้สุด (กันกล้องติดเอียง) */
    private fun computeLaneCenter(dets: List<Det>, imgW: Int, imgH: Int): Float {
        if (!s.autoLane) { laneCenterFrac = 0.5f; return 0.5f }

        var best: Det? = null
        var bestScore = -1f
        for (p in dets) {
            if (p.name !in vehicles) continue
            if (p.rect.bottom < 0.45f * imgH) continue          // เอาเฉพาะรถที่อยู่ใกล้ (ครึ่งล่าง)
            val area = (p.rect.right - p.rect.left) * (p.rect.bottom - p.rect.top)
            val cxFrac = p.cx / imgW
            val prox = 1f / (1f + 4f * abs(cxFrac - laneCenterFrac))   // ชอบรถที่ใกล้แนวเลนเดิม
            val sc = area * prox
            if (sc > bestScore) { bestScore = sc; best = p }
        }

        if (best != null) {
            val obs = (best.cx / imgW).coerceIn(0.15f, 0.85f)
            laneCenterFrac += 0.12f * (obs - laneCenterFrac)        // เลื่อนแบบนุ่มนวล (EMA)
        } else {
            laneCenterFrac += 0.03f * (0.5f - laneCenterFrac)       // ไม่มีรถ -> ค่อย ๆ กลับกลางจอ
        }
        laneCenterFrac = laneCenterFrac.coerceIn(0.2f, 0.8f)
        return laneCenterFrac
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
        if (maxc < n * 0.04f) return "unknown"
        return when (maxc) { red -> "red"; green -> "green"; else -> "yellow" }
    }
}
