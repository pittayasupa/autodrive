package com.autodrive.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/** ลากปรับเส้นเลนด้วยนิ้ว: จับจุดมุม 4 จุด (ล่างซ้าย/ขวา, บนซ้าย/ขวา) */
class LaneEditorView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    // สัดส่วน 0–1
    var bl = 0.28f; var br = 0.72f
    var tl = 0.46f; var tr = 0.54f
    var ty = 0.55f

    private var drag = -1   // 0=BL 1=BR 2=TL 3=TR
    private val cHud = Color.parseColor("#00F0FF")

    private val line = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 5f; color = cHud; isAntiAlias = true }
    private val fillP = Paint().apply { style = Paint.Style.FILL; color = Color.parseColor("#3322FF88"); isAntiAlias = true }
    private val handle = Paint().apply { style = Paint.Style.FILL; color = cHud; isAntiAlias = true }
    private val handleRing = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.WHITE; isAntiAlias = true }
    private val txt = Paint().apply { color = Color.WHITE; textSize = 30f; isAntiAlias = true }

    fun load(s: Settings) {
        bl = s.laneBL; br = s.laneBR; tl = s.laneTL; tr = s.laneTR; ty = s.laneTopY
        invalidate()
    }
    fun save(s: Settings) {
        s.laneBL = bl; s.laneBR = br; s.laneTL = tl; s.laneTR = tr; s.laneTopY = ty
    }
    fun reset() { bl = 0.28f; br = 0.72f; tl = 0.46f; tr = 0.54f; ty = 0.55f; invalidate() }

    private fun blPx() = floatArrayOf(bl * width, height.toFloat())
    private fun brPx() = floatArrayOf(br * width, height.toFloat())
    private fun tlPx() = floatArrayOf(tl * width, ty * height)
    private fun trPx() = floatArrayOf(tr * width, ty * height)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val a = blPx(); val b = brPx(); val c = trPx(); val d = tlPx()
        val path = Path().apply {
            moveTo(a[0], a[1]); lineTo(b[0], b[1]); lineTo(c[0], c[1]); lineTo(d[0], d[1]); close()
        }
        canvas.drawPath(path, fillP)
        canvas.drawLine(a[0], a[1], d[0], d[1], line)   // ซ้าย
        canvas.drawLine(b[0], b[1], c[0], c[1], line)   // ขวา
        canvas.drawLine(d[0], d[1], c[0], c[1], line)   // บน

        for (p in listOf(a, b, d, c)) {
            canvas.drawCircle(p[0], p[1], 26f, handle)
            canvas.drawCircle(p[0], p[1], 30f, handleRing)
        }
        canvas.drawText("ลากจุดให้ตรงกับเลนถนนของคุณ", 24f, 44f, txt)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x; val y = e.y
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                val pts = listOf(blPx(), brPx(), tlPx(), trPx())
                var best = -1; var bestD = 90f
                for (i in pts.indices) {
                    val dd = hypot(x - pts[i][0], y - pts[i][1])
                    if (dd < bestD) { bestD = dd; best = i }
                }
                drag = best
                return drag >= 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (drag < 0) return false
                val fx = (x / width).coerceIn(0.02f, 0.98f)
                val fy = (y / height).coerceIn(0.30f, 0.92f)
                when (drag) {
                    0 -> bl = fx.coerceIn(0.02f, 0.5f)
                    1 -> br = fx.coerceIn(0.5f, 0.98f)
                    2 -> { tl = fx.coerceIn(0.05f, 0.5f); ty = fy }
                    3 -> { tr = fx.coerceIn(0.5f, 0.95f); ty = fy }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { drag = -1; return true }
        }
        return false
    }
}
