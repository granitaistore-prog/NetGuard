package com.buildlevel.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.tan

/** Малює бульбашковий рівень: круглий (2 осі) або трубчастий (1 вісь). */
class LevelView(context: Context) : View(context) {

    enum class Style { CIRCLE, TUBE }

    var style = Style.CIRCLE
        set(value) { field = value; invalidate() }
    var percent = false
        set(value) { field = value; invalidate() }

    /** Кут по X (вправо вище = +) і по Y (вгору вище = +), у градусах. */
    var angleA = 0f
    var angleB = 0f

    private val maxAngle = 10f          // кут, при якому бульбашка доходить до краю
    private val levelThreshold = 0.5f   // «у рівень», якщо відхилення менше цього значення

    private val blue = Color.parseColor("#4FC3F7")
    private val green = Color.parseColor("#69F0AE")
    private val faint = Color.parseColor("#33FFFFFF")

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val rect = RectF()

    fun update(a: Float, b: Float) {
        angleA = a
        angleB = b
        invalidate()
    }

    fun isLevel(): Boolean =
        abs(angleA) <= levelThreshold && (style == Style.TUBE || abs(angleB) <= levelThreshold)

    private fun fmt(angle: Float, signed: Boolean): String {
        val raw = if (percent) tan(Math.toRadians(angle.toDouble())) * 100.0 else angle.toDouble()
        val v = if (abs(raw) < 0.05) 0.0 else raw
        val unit = if (percent) "%" else "°"
        return String.format(if (signed) "%+.1f%s" else "%.1f%s", v, unit)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val accent = if (isLevel()) green else blue
        if (style == Style.CIRCLE) drawCircle(canvas, w, h, accent) else drawTube(canvas, w, h, accent)
    }

    private fun drawCircle(canvas: Canvas, w: Float, h: Float, accent: Int) {
        val textArea = min(w, h) * 0.3f
        val availH = h - textArea
        val cx = w / 2f
        val cy = availH / 2f
        val r = min(w, availH) / 2f * 0.94f

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.color = faint
        canvas.drawCircle(cx, cy, r * 0.66f, paint)
        canvas.drawCircle(cx, cy, r * 0.33f, paint)
        canvas.drawLine(cx - r, cy, cx + r, cy, paint)
        canvas.drawLine(cx, cy - r, cx, cy + r, paint)

        paint.color = accent
        paint.strokeWidth = 8f
        canvas.drawCircle(cx, cy, r, paint)

        val bubbleR = r * 0.13f
        paint.strokeWidth = 5f
        canvas.drawCircle(cx, cy, bubbleR, paint)

        var nx = (angleA / maxAngle).coerceIn(-1f, 1f)
        var ny = (angleB / maxAngle).coerceIn(-1f, 1f)
        val len = hypot(nx, ny)
        if (len > 1f) { nx /= len; ny /= len }
        val travel = r - bubbleR
        val bx = cx + nx * travel
        val by = cy - ny * travel

        paint.style = Paint.Style.FILL
        paint.color = accent
        paint.alpha = 160
        canvas.drawCircle(bx, by, bubbleR, paint)
        paint.alpha = 255

        val total = hypot(angleA, angleB)
        textPaint.color = accent
        textPaint.textSize = textArea * 0.5f
        canvas.drawText(fmt(total, false), cx, availH + textArea * 0.52f, textPaint)
        textPaint.color = Color.LTGRAY
        textPaint.textSize = textArea * 0.2f
        canvas.drawText(
            "X ${fmt(angleA, true)}     Y ${fmt(angleB, true)}",
            cx, availH + textArea * 0.88f, textPaint
        )
    }

    private fun drawTube(canvas: Canvas, w: Float, h: Float, accent: Int) {
        val tubeW = w * 0.9f
        val tubeH = min(h * 0.25f, tubeW * 0.16f)
        val cx = w / 2f
        val cy = h * 0.33f

        rect.set(cx - tubeW / 2f, cy - tubeH / 2f, cx + tubeW / 2f, cy + tubeH / 2f)
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#1C2630")
        canvas.drawRoundRect(rect, tubeH / 2f, tubeH / 2f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 8f
        paint.color = accent
        canvas.drawRoundRect(rect, tubeH / 2f, tubeH / 2f, paint)

        val bw = tubeH * 1.5f
        val bh = tubeH * 0.62f
        paint.strokeWidth = 4f
        paint.color = faint
        canvas.drawLine(cx - bw / 2f, cy - tubeH / 2f, cx - bw / 2f, cy + tubeH / 2f, paint)
        canvas.drawLine(cx + bw / 2f, cy - tubeH / 2f, cx + bw / 2f, cy + tubeH / 2f, paint)

        val travel = tubeW / 2f - bw / 2f - tubeH * 0.2f
        val bx = cx + (angleA / maxAngle).coerceIn(-1f, 1f) * travel
        rect.set(bx - bw / 2f, cy - bh / 2f, bx + bw / 2f, cy + bh / 2f)
        paint.style = Paint.Style.FILL
        paint.color = accent
        paint.alpha = 170
        canvas.drawOval(rect, paint)
        paint.alpha = 255

        val ts = min(w * 0.2f, h * 0.3f)
        val baseline = cy + tubeH / 2f + ts * 1.4f
        textPaint.color = accent
        textPaint.textSize = ts
        canvas.drawText(fmt(angleA, true), cx, baseline, textPaint)
        textPaint.color = Color.LTGRAY
        textPaint.textSize = ts * 0.2f
        canvas.drawText("+ = правий бік вище", cx, baseline + ts * 0.5f, textPaint)
    }
}
