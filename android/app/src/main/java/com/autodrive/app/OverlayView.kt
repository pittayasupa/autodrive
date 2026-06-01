package com.autodrive.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** วาด HUD ทับภาพกล้อง — กล่องเลื่อนแบบ smooth (track + ease ~60fps) */
class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    /** กล่องที่ติดตามข้ามเฟรม (มีตำแหน่งเป้าหมาย + ตำแหน่งปัจจุบันที่ค่อย ๆ ขยับเข้าหา) */
    private class Track(
        var tl: Float, var tt: Float, var tr: Float, var tb: Float
    ) {
        var cl = tl; var ct = tt; var cr = tr; var cb = tb
        var label = ""; var dist: Float? = null; var level = Level.NORMAL
        var miss = 0; var alpha = 0f
    }

    private val tracks = ArrayList<Track>()
    private var latest: AdasResult? = null
    private var imgW = 1
    private var imgH = 1
    private var smoothLead = 0f
    private var speedKmh = -1f
    private var showSpeed = false
    private var speedLimit = 90

    private val box = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 6f; isAntiAlias = true }
    private val fill = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val text = Paint().apply { isAntiAlias = true; textSize = 34f; color = Color.WHITE }
    private val panel = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }

    private val cOrange = Color.parseColor("#FF7A18")
    private val cYellow = Color.parseColor("#FFCC00")
    private val cRed = Color.parseColor("#FF2D55")
    private val cOk = Color.parseColor("#22FF88")
    private val cHud = Color.parseColor("#00F0FF")

    fun setResults(r: AdasResult, w: Int, h: Int) {
        imgW = w; imgH = h; latest = r

        // smooth ระยะรถคันหน้า
        if (r.leadDistM < 900f) {
            smoothLead = if (smoothLead <= 0f) r.leadDistM else smoothLead + 0.3f * (r.leadDistM - smoothLead)
        } else smoothLead = 0f

        // จับคู่กล่องใหม่กับ track เดิม (คลาสเดียวกัน + ศูนย์กลางใกล้สุด)
        val used = BooleanArray(tracks.size)
        val matchDist = 0.20f * imgW
        val toAdd = ArrayList<Track>()
        for (nb in r.boxes) {
            val cls = nb.label.substringBefore(' ')
            val ncx = (nb.left + nb.right) / 2f; val ncy = (nb.top + nb.bottom) / 2f
            var bi = -1; var bd = matchDist
            for (i in tracks.indices) {
                if (used[i]) continue
                val t = tracks[i]
                if (t.label.substringBefore(' ') != cls) continue
                val d = hypot(((t.tl + t.tr) / 2f) - ncx, ((t.tt + t.tb) / 2f) - ncy)
                if (d < bd) { bd = d; bi = i }
            }
            if (bi >= 0) {
                val t = tracks[bi]
                t.tl = nb.left; t.tt = nb.top; t.tr = nb.right; t.tb = nb.bottom
                t.label = nb.label; t.dist = nb.distM; t.level = nb.level; t.miss = 0
                used[bi] = true
            } else {
                toAdd.add(Track(nb.left, nb.top, nb.right, nb.bottom).also {
                    it.label = nb.label; it.dist = nb.distM; it.level = nb.level
                })
            }
        }
        for (i in tracks.indices) if (!used[i]) tracks[i].miss++
        tracks.addAll(toAdd)

        postInvalidateOnAnimation()
    }

    fun setSpeed(kmh: Float, show: Boolean, limit: Int) {
        speedKmh = kmh; showSpeed = show; speedLimit = limit
        postInvalidateOnAnimation()
    }

    private fun colorOf(l: Level) = when (l) {
        Level.NORMAL -> cOrange; Level.WARN -> cYellow; Level.CRIT -> cRed
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = latest ?: return

        // ขยับ current เข้าหา target แบบนุ่มนวล + จัดการ fade
        var moving = false
        val ease = 0.35f
        val it = tracks.iterator()
        while (it.hasNext()) {
            val t = it.next()
            t.cl += (t.tl - t.cl) * ease; t.ct += (t.tt - t.ct) * ease
            t.cr += (t.tr - t.cr) * ease; t.cb += (t.tb - t.cb) * ease
            val targetA = if (t.miss > 3) 0f else 1f
            t.alpha += (targetA - t.alpha) * 0.25f
            if (t.miss > 3 && t.alpha < 0.04f) { it.remove(); continue }
            if (abs(t.tl - t.cl) > 0.5f || abs(t.tr - t.cr) > 0.5f ||
                abs(t.tt - t.ct) > 0.5f || abs(t.tb - t.cb) > 0.5f || abs(targetA - t.alpha) > 0.01f
            ) moving = true
        }

        val scale = max(width.toFloat() / imgW, height.toFloat() / imgH)
        val dx = (imgW * scale - width) / 2f
        val dy = (imgH * scale - height) / 2f
        fun mapX(x: Float) = x * scale - dx
        fun mapY(y: Float) = y * scale - dy

        // ---- เลน (trapezoid) สัดส่วนจอ ----
        run {
            val w = width.toFloat(); val h = height.toFloat()
            box.color = cHud; box.strokeWidth = 4f; box.alpha = 150
            canvas.drawLine(r.laneNearL * w, h, r.laneFarL * w, r.laneTopY * h, box)
            canvas.drawLine(r.laneNearR * w, h, r.laneFarR * w, r.laneTopY * h, box)
            box.alpha = 80
            canvas.drawLine(r.laneFarL * w, r.laneTopY * h, r.laneFarR * w, r.laneTopY * h, box)
            box.alpha = 255
        }

        // ---- ป้ายสถานะไฟจราจร ----
        if (r.trafficLight) {
            val col = when (r.lightColor) { "red" -> cRed; "green" -> cOk; "yellow" -> cYellow; else -> Color.LTGRAY }
            val label = when (r.lightColor) { "red" -> "ไฟแดง"; "green" -> "ไฟเขียว"; "yellow" -> "ไฟเหลือง"; else -> "ไฟจราจร" }
            text.textSize = 30f
            val tw = text.measureText(label)
            panel.color = Color.parseColor("#E0050C14")
            val rect = RectF(16f, 134f, 16f + tw + 70f, 184f)
            canvas.drawRoundRect(rect, 12f, 12f, panel)
            fill.color = col
            canvas.drawCircle(rect.left + 27f, rect.centerY(), 14f, fill)
            text.color = Color.WHITE
            canvas.drawText(label, rect.left + 50f, rect.centerY() + 10f, text)
        }

        // ---- กล่อง detection (smooth) ----
        for (t in tracks) {
            val col = colorOf(t.level)
            val a = (t.alpha * 255).toInt().coerceIn(0, 255)
            box.color = col; box.alpha = a; box.strokeWidth = 6f
            val l = mapX(t.cl); val tp = mapY(t.ct); val rr = mapX(t.cr); val bb = mapY(t.cb)
            canvas.drawRoundRect(RectF(l, tp, rr, bb), 8f, 8f, box)

            text.textSize = 32f
            val tw = text.measureText(t.label)
            fill.color = col; fill.alpha = a
            canvas.drawRect(l, tp - 40f, l + tw + 14f, tp, fill)
            text.color = Color.BLACK; text.alpha = a
            canvas.drawText(t.label, l + 7f, tp - 11f, text)

            t.dist?.let {
                val dt = "%.1f m".format(it)
                val dw = text.measureText(dt)
                fill.color = Color.parseColor("#D8050C14"); fill.alpha = a
                canvas.drawRect(rr - dw - 14f, bb, rr, bb + 42f, fill)
                text.color = cHud; text.alpha = a
                canvas.drawText(dt, rr - dw - 7f, bb + 33f, text)
            }
            box.alpha = 255; fill.alpha = 255; text.alpha = 255; text.color = Color.WHITE
        }

        val cmdCol = colorOf(r.level)

        // ---- แถบคำสั่งบน ----
        val bw = width * 0.74f
        val bx = (width - bw) / 2f
        val rect = RectF(bx, 24f, bx + bw, 124f)
        panel.color = Color.parseColor("#E0050C14")
        canvas.drawRoundRect(rect, 20f, 20f, panel)
        box.color = cmdCol; box.strokeWidth = 5f
        canvas.drawRoundRect(rect, 20f, 20f, box)
        fill.color = cmdCol
        canvas.drawCircle(rect.left + 46f, rect.centerY(), 21f, fill)
        text.textSize = 48f; text.color = Color.WHITE
        canvas.drawText(r.command, rect.left + 88f, rect.top + 52f, text)
        text.textSize = 30f; text.color = cHud
        canvas.drawText(r.sub, rect.left + 88f, rect.top + 88f, text)
        text.color = Color.WHITE

        // ---- FCW: ระยะรถหน้า (smooth) + TTC ----
        if (smoothLead > 0f) {
            val fw = 380f
            val fx = (width - fw) / 2f
            val fy = height - 130f
            val frect = RectF(fx, fy, fx + fw, fy + 100f)
            panel.color = Color.parseColor("#E0050C14")
            canvas.drawRoundRect(frect, 18f, 18f, panel)
            box.color = cmdCol; canvas.drawRoundRect(frect, 18f, 18f, box)
            text.textSize = 26f; text.color = Color.WHITE
            canvas.drawText("ระยะรถหน้า", fx + 22f, fy + 36f, text)
            text.textSize = 50f; text.color = cmdCol
            canvas.drawText("%.0f m".format(smoothLead), fx + 22f, fy + 88f, text)
            text.textSize = 26f; text.color = Color.WHITE
            canvas.drawText("TTC", fx + 240f, fy + 36f, text)
            text.textSize = 46f; text.color = cmdCol
            canvas.drawText(r.ttcSec?.let { "%.1f s".format(it) } ?: "--", fx + 240f, fy + 86f, text)
            text.color = Color.WHITE
        }

        // ---- มาตรวัดความเร็ว (มุมซ้ายล่าง) ----
        if (showSpeed && speedKmh >= 0f) {
            val over = speedKmh > speedLimit
            val col = if (over) cRed else cOk
            val sw = 150f
            val sx = 16f; val sy = height - 130f
            panel.color = Color.parseColor("#E0050C14")
            canvas.drawRoundRect(RectF(sx, sy, sx + sw, sy + 96f), 16f, 16f, panel)
            box.color = col; box.strokeWidth = if (over) 5f else 3f
            canvas.drawRoundRect(RectF(sx, sy, sx + sw, sy + 96f), 16f, 16f, box)
            text.textSize = 52f; text.color = col
            canvas.drawText("%.0f".format(speedKmh), sx + 16f, sy + 56f, text)
            text.textSize = 22f; text.color = Color.WHITE
            canvas.drawText("km/h  •  จำกัด $speedLimit", sx + 16f, sy + 84f, text)
        }

        // ---- หมายเหตุ ----
        text.textSize = 24f
        canvas.drawText("ADVISORY ONLY • ไม่ควบคุมรถจริง", 22f, height - 22f, text)
        text.textSize = 34f

        if (moving) postInvalidateOnAnimation()
    }
}
