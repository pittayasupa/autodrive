package com.autodrive.app

import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import kotlin.math.tan

/** ระดับความรุนแรง -> ใช้กำหนดสี */
enum class Level { NORMAL, WARN, CRIT }

/** กล่องที่จะวาด (พิกัดเป็น pixel ของภาพ input) */
data class DetBox(
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val label: String, val distM: Float?, val level: Level
)

/** ผลรวมต่อเฟรม */
data class AdasResult(
    val boxes: List<DetBox>,
    val command: String,
    val sub: String,
    val level: Level,
    val leadDistM: Float,
    val ttcSec: Float?
)

/**
 * ตรรกะ ADAS: ประมาณระยะด้วยกล้องเดียว (pinhole) + ตัดสินใจคำสั่ง
 * distance ≈ (focal_px × ความสูงจริง) / ความสูงกล่อง(px)
 */
class Adas(private val hfovDeg: Double = 62.0) {

    // ความสูงจริงโดยประมาณ (เมตร) ของแต่ละคลาส COCO
    private val realHeights = mapOf(
        "person" to 1.7f, "bicycle" to 1.1f, "car" to 1.5f,
        "motorcycle" to 1.1f, "bus" to 3.2f, "truck" to 3.5f
    )
    private val vehicles = setOf("car", "bus", "truck", "motorcycle")
    private val vulnerable = setOf("person", "bicycle")

    private val safeDist = 18f
    private val warnDist = 12f
    private val critDist = 7f
    private val pedCrit = 15f
    private val ttcWarn = 2.5f
    private val ttcCrit = 1.2f

    private var lastLead = 999f
    private var lastTimeNs = 0L

    fun process(result: ObjectDetectorResult, imgW: Int, imgH: Int): AdasResult {
        val focal = (imgW / 2.0) / tan(Math.toRadians(hfovDeg / 2.0))
        val laneL = 0.32f * imgW
        val laneR = 0.68f * imgW

        val boxes = ArrayList<DetBox>()
        var leadDist = 999f
        var pedInLane = false
        var trafficLight = false

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

            when {
                name in vulnerable && dist != null && dist < pedCrit && inLane -> {
                    pedInLane = true; level = Level.CRIT
                }
                name == "traffic light" -> {
                    trafficLight = true; level = Level.WARN
                }
                name in vehicles && inLane -> {
                    if (dist != null && dist < leadDist) leadDist = dist
                    level = when {
                        dist != null && dist < critDist -> Level.CRIT
                        dist != null && dist < warnDist -> Level.WARN
                        else -> Level.NORMAL
                    }
                }
            }

            val showDist = if (name in realHeights) dist else null
            boxes.add(
                DetBox(r.left, r.top, r.right, r.bottom, "$name ${"%.2f".format(score)}", showDist, level)
            )
        }

        // คำนวณ TTC จากการเปลี่ยนแปลงระยะรถคันหน้า
        val now = System.nanoTime()
        var ttc: Float? = null
        if (lastTimeNs != 0L && leadDist < 900f && lastLead < 900f) {
            val dt = (now - lastTimeNs) / 1e9
            val closing = lastLead - leadDist
            if (dt > 0 && closing > 0.1f) ttc = (leadDist / (closing / dt)).toFloat()
        }
        lastLead = leadDist
        lastTimeNs = now

        val (cmd, sub, level) = decide(leadDist, ttc, pedInLane, trafficLight)
        return AdasResult(boxes, cmd, sub, level, leadDist, ttc)
    }

    private fun decide(lead: Float, ttc: Float?, ped: Boolean, light: Boolean): Triple<String, String, Level> {
        if (ped) return Triple("เบรก! คนข้ามถนน", "EMERGENCY STOP", Level.CRIT)
        if (lead < critDist || (ttc != null && ttc < ttcCrit))
            return Triple("เบรก! รถหน้าใกล้มาก", "FORWARD COLLISION", Level.CRIT)
        if (lead < warnDist || (ttc != null && ttc < ttcWarn))
            return Triple("ชะลอ — รักษาระยะ", "SLOW DOWN", Level.WARN)
        if (light) return Triple("มีไฟจราจรข้างหน้า", "TRAFFIC LIGHT", Level.WARN)
        return Triple("ขับปกติ", "CRUISING", Level.NORMAL)
    }
}
