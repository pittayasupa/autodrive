package com.autodrive.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/** วาด HUD ทับภาพกล้อง: กล่อง + ระยะ + แถบคำสั่ง + FCW */
class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var result: AdasResult? = null
    private var imgW = 1
    private var imgH = 1

    private val box = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 6f; isAntiAlias = true }
    private val fill = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val text = Paint().apply { isAntiAlias = true; textSize = 34f; color = Color.WHITE }
    private val panel = Paint().apply { style = Paint.Style.FILL; color = Color.parseColor("#D8050C14") }

    private val cOrange = Color.parseColor("#FF7A18")
    private val cYellow = Color.parseColor("#FFCC00")
    private val cRed = Color.parseColor("#FF2D55")
    private val cOk = Color.parseColor("#22FF88")
    private val cHud = Color.parseColor("#00F0FF")

    fun setResults(r: AdasResult, w: Int, h: Int) {
        result = r; imgW = w; imgH = h; invalidate()
    }

    private fun colorOf(l: Level) = when (l) {
        Level.NORMAL -> cOrange
        Level.WARN -> cYellow
        Level.CRIT -> cRed
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = result ?: return

        // PreviewView ใช้ FILL_CENTER (ครอบตัด) -> ใช้ scale แบบ max + offset กึ่งกลาง
        val scale = max(width.toFloat() / imgW, height.toFloat() / imgH)
        val dx = (imgW * scale - width) / 2f
        val dy = (imgH * scale - height) / 2f
        fun mapX(x: Float) = x * scale - dx
        fun mapY(y: Float) = y * scale - dy

        // ---- เส้นบอกขอบเลนของเรา ----
        run {
            box.color = cHud; box.strokeWidth = 3f
            box.alpha = 150
            val lx = mapX(r.laneLeft); val rx = mapX(r.laneRight)
            canvas.drawLine(lx, height.toFloat(), lx + (width / 2f - lx) * 0.45f, height * 0.5f, box)
            canvas.drawLine(rx, height.toFloat(), rx - (rx - width / 2f) * 0.45f, height * 0.5f, box)
            box.alpha = 255
        }

        // ---- ป้ายสถานะไฟจราจร (มุมซ้ายบน) ----
        if (r.trafficLight) {
            val col = when (r.lightColor) {
                "red" -> cRed; "green" -> cOk; "yellow" -> cYellow; else -> Color.LTGRAY
            }
            val label = when (r.lightColor) {
                "red" -> "ไฟแดง"; "green" -> "ไฟเขียว"; "yellow" -> "ไฟเหลือง"; else -> "ไฟจราจร"
            }
            text.textSize = 30f
            val tw = text.measureText(label)
            panel.color = Color.parseColor("#E0050C14")
            val rect = RectF(16f, 134f, 16f + tw + 70f, 134f + 50f)
            canvas.drawRoundRect(rect, 12f, 12f, panel)
            fill.color = col
            canvas.drawCircle(rect.left + 27f, rect.centerY(), 14f, fill)
            text.color = Color.WHITE
            canvas.drawText(label, rect.left + 50f, rect.centerY() + 10f, text)
            text.textSize = 34f
        }

        // ---- กล่อง detection ----
        for (b in r.boxes) {
            val col = colorOf(b.level)
            box.color = col; box.strokeWidth = 6f
            val l = mapX(b.left); val t = mapY(b.top); val rr = mapX(b.right); val bb = mapY(b.bottom)
            canvas.drawRect(l, t, rr, bb, box)

            // ป้ายคลาส
            text.textSize = 32f
            val tw = text.measureText(b.label)
            fill.color = col
            canvas.drawRect(l, t - 40f, l + tw + 14f, t, fill)
            text.color = Color.BLACK
            canvas.drawText(b.label, l + 7f, t - 11f, text)
            text.color = Color.WHITE

            // ป้ายระยะ
            b.distM?.let {
                val dt = "%.1f m".format(it)
                val dw = text.measureText(dt)
                fill.color = Color.parseColor("#D8050C14")
                canvas.drawRect(rr - dw - 14f, bb, rr, bb + 42f, fill)
                text.color = cHud
                canvas.drawText(dt, rr - dw - 7f, bb + 33f, text)
                text.color = Color.WHITE
            }
        }

        val cmdCol = colorOf(r.level)

        // ---- แถบคำสั่งบน ----
        val bw = width * 0.74f
        val bx = (width - bw) / 2f
        val rect = RectF(bx, 24f, bx + bw, 24f + 100f)
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

        // ---- FCW ล่าง: ระยะรถหน้า + TTC ----
        if (r.leadDistM < 900f) {
            val fw = 380f
            val fx = (width - fw) / 2f
            val fy = height - 130f
            val frect = RectF(fx, fy, fx + fw, fy + 100f)
            panel.color = Color.parseColor("#E0050C14")
            canvas.drawRoundRect(frect, 18f, 18f, panel)
            box.color = cmdCol
            canvas.drawRoundRect(frect, 18f, 18f, box)

            text.textSize = 26f; text.color = Color.WHITE
            canvas.drawText("ระยะรถหน้า", fx + 22f, fy + 36f, text)
            text.textSize = 50f; text.color = cmdCol
            canvas.drawText("%.0f m".format(r.leadDistM), fx + 22f, fy + 88f, text)
            text.textSize = 26f; text.color = Color.WHITE
            canvas.drawText("TTC", fx + 240f, fy + 36f, text)
            text.textSize = 46f; text.color = cmdCol
            canvas.drawText(r.ttcSec?.let { "%.1f s".format(it) } ?: "--", fx + 240f, fy + 86f, text)
            text.color = Color.WHITE
        }

        // ---- หมายเหตุ advisory ----
        text.textSize = 24f; text.color = Color.WHITE
        canvas.drawText("ADVISORY ONLY • ไม่ควบคุมรถจริง", 22f, height - 22f, text)
        text.textSize = 34f
    }
}
