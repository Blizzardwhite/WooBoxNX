package com.u9521.wooboxforredmagicos.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View

/** 分类图标：在 tonal 圆底上画一个简单图形 */
class IconView(context: Context, private val type: Int) : View(context) {

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = M3Color.PRIMARY_CONT }
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = M3Color.ON_PRIMARY_CONT
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val r = minOf(w, h) / 2f
        canvas.drawCircle(cx, cy, r, bg)

        val s = r * 0.5f
        when (type) {
            0 -> { // 弯月
                val outer = Path().apply { addCircle(cx - s * 0.15f, cy, s, Path.Direction.CW) }
                val inner = Path().apply { addCircle(cx + s * 0.50f, cy - s * 0.50f, s * 0.92f, Path.Direction.CW) }
                outer.op(inner, Path.Op.DIFFERENCE)
                canvas.drawPath(outer, Paint(fg).apply { style = Paint.Style.FILL })
            }
            1 -> { // 状态栏三条
                canvas.drawLine(cx - s, cy - s * 0.6f, cx + s, cy - s * 0.6f, fg)
                canvas.drawLine(cx - s, cy, cx + s * 0.4f, cy, fg)
                canvas.drawLine(cx - s, cy + s * 0.6f, cx + s * 0.8f, cy + s * 0.6f, fg)
            }
            2 -> { // 通知铃铛
                canvas.drawArc(RectF(cx - s, cy - s, cx + s, cy + s * 0.7f), 180f, 180f, false, fg)
                canvas.drawLine(cx - s * 1.02f, cy + s * 0.35f, cx + s * 1.02f, cy + s * 0.35f, fg)
                canvas.drawCircle(cx, cy + s * 0.75f, dp(1.6f), fg)
            }
            3 -> { // 快速设置 2x2
                val p = Paint(fg).apply { style = Paint.Style.FILL }
                val q = s * 0.42f
                canvas.drawRoundRect(RectF(cx - s, cy - s, cx - s + q * 1.5f, cy - s + q * 1.5f), dp(1.5f), dp(1.5f), p)
                canvas.drawRoundRect(RectF(cx + s - q * 1.5f, cy - s, cx + s, cy - s + q * 1.5f), dp(1.5f), dp(1.5f), p)
                canvas.drawRoundRect(RectF(cx - s, cy + s - q * 1.5f, cx - s + q * 1.5f, cy + s), dp(1.5f), dp(1.5f), p)
                canvas.drawRoundRect(RectF(cx + s - q * 1.5f, cy + s - q * 1.5f, cx + s, cy + s), dp(1.5f), dp(1.5f), p)
            }
            4 -> { // 锁
                canvas.drawArc(RectF(cx - s * 0.6f, cy - s * 1.1f, cx + s * 0.6f, cy + s * 0.2f), 180f, 180f, false, fg)
                val p = Paint(fg).apply { style = Paint.Style.FILL }
                canvas.drawRoundRect(RectF(cx - s * 0.9f, cy - s * 0.1f, cx + s * 0.9f, cy + s * 0.9f), dp(3f), dp(3f), p)
            }
            6 -> { // 信息
                val p = Paint(fg).apply { style = Paint.Style.FILL }
                canvas.drawCircle(cx, cy - s * 0.55f, dp(1.8f), p)
                canvas.drawRoundRect(RectF(cx - dp(1.2f), cy - s * 0.15f, cx + dp(1.2f), cy + s * 0.7f), dp(1.2f), dp(1.2f), p)
            }
            else -> { // 三个点
                val p = Paint(fg).apply { style = Paint.Style.FILL }
                canvas.drawCircle(cx - s, cy, dp(2.2f), p)
                canvas.drawCircle(cx, cy, dp(2.2f), p)
                canvas.drawCircle(cx + s, cy, dp(2.2f), p)
            }
        }
    }
}

/** 摄像头位置预览：模拟状态栏 + 黑圆 + 当前样式 */
class CameraPreviewView(context: Context) : View(context) {

    var styleProvider: () -> Int = { 0 }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF2F2F2.toInt()
        style = Paint.Style.FILL
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF34C759.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFCFCFCF.toInt()
        textSize = dp(11f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(13f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        bgPaint.shader = LinearGradient(0f, 0f, 0f, h, 0xFF1A2A30.toInt(), 0xFF101820.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(RectF(0f, 0f, w, h), dp(14f), dp(14f), bgPaint)

        // 模拟状态栏
        canvas.drawText("04:20", dp(14f), dp(20f), textPaint)
        val dot = Paint(fgPaint).apply { color = 0xFFBFBFBF.toInt() }
        canvas.drawCircle(w - dp(16f), dp(16f), dp(1.8f), dot)
        canvas.drawCircle(w - dp(24f), dp(16f), dp(1.8f), dot)

        // 摄像头位置
        val cx = w / 2f
        val cy = dp(36f)
        val r = dp(19f)
        canvas.drawCircle(cx, cy, r, discPaint)

        when (styleProvider()) {
            1 -> { // 彩色图标
                canvas.drawCircle(cx, cy, r * 0.92f, iconPaint)
                canvas.drawText("C", cx, cy + dp(5f), glyphPaint)
            }
            2 -> { // 剪影
                canvas.drawText("C", cx, cy + dp(5f), glyphPaint)
            }
            else -> { // 弯月 + 星
                val mr = r * 0.62f
                val outer = Path().apply { addCircle(cx - mr * 0.12f, cy, mr, Path.Direction.CW) }
                val inner = Path().apply { addCircle(cx + mr * 0.52f, cy - mr * 0.52f, mr * 0.90f, Path.Direction.CW) }
                outer.op(inner, Path.Op.DIFFERENCE)
                canvas.drawPath(outer, fgPaint)
                star(canvas, cx + r * 0.48f, cy - r * 0.40f, r * 0.17f)
            }
        }

        // 屏幕内容示意
        val content = Paint(fgPaint).apply { color = 0x18FFFFFF }
        canvas.drawRoundRect(RectF(dp(14f), cy + r + dp(14f), w - dp(14f), cy + r + dp(22f)), dp(4f), dp(4f), content)
        canvas.drawRoundRect(RectF(dp(14f), cy + r + dp(30f), w * 0.66f, cy + r + dp(38f)), dp(4f), dp(4f), content)
        canvas.drawRoundRect(RectF(dp(14f), cy + r + dp(52f), w - dp(14f), h - dp(14f)), dp(8f), dp(8f), content)
    }

    private fun star(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val p = Path()
        p.moveTo(cx, cy - r)
        p.lineTo(cx + r * 0.26f, cy - r * 0.26f)
        p.lineTo(cx + r, cy)
        p.lineTo(cx + r * 0.26f, cy + r * 0.26f)
        p.lineTo(cx, cy + r)
        p.lineTo(cx - r * 0.26f, cy + r * 0.26f)
        p.lineTo(cx - r, cy)
        p.lineTo(cx - r * 0.26f, cy - r * 0.26f)
        p.close()
        canvas.drawPath(p, fgPaint)
    }
}
