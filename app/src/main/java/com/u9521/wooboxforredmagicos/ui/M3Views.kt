package com.u9521.wooboxforredmagicos.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.ScrollView

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
            7 -> { // 齿轮（系统）
                val p = Paint(fg).apply { style = Paint.Style.STROKE; strokeWidth = dp(2.0f) }
                canvas.drawCircle(cx, cy, s * 0.5f, p)
                canvas.drawCircle(cx, cy, s * 0.18f, p)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val x1 = cx + (s * 0.6f * Math.cos(a)).toFloat()
                    val y1 = cy + (s * 0.6f * Math.sin(a)).toFloat()
                    val x2 = cx + (s * 0.92f * Math.cos(a)).toFloat()
                    val y2 = cy + (s * 0.92f * Math.sin(a)).toFloat()
                    canvas.drawLine(x1, y1, x2, y2, p)
                }
            }
            8 -> { // 向下箭头（系统更新）
                val p = Paint(fg).apply { style = Paint.Style.STROKE; strokeWidth = dp(2.0f) }
                canvas.drawLine(cx, cy - s * 0.8f, cx, cy + s * 0.3f, p)
                canvas.drawLine(cx - s * 0.4f, cy - s * 0.1f, cx, cy + s * 0.3f, p)
                canvas.drawLine(cx + s * 0.4f, cy - s * 0.1f, cx, cy + s * 0.3f, p)
                canvas.drawLine(cx - s * 0.8f, cy + s * 0.8f, cx + s * 0.8f, cy + s * 0.8f, p)
            }
            9 -> { // 手势：屏幕 + 底部手势点
                val p = Paint(fg).apply { style = Paint.Style.STROKE; strokeWidth = dp(2.0f) }
                canvas.drawRoundRect(
                    RectF(cx - s * 0.6f, cy - s * 0.85f, cx + s * 0.6f, cy + s * 0.85f),
                    dp(3f), dp(3f), p
                )
                canvas.drawCircle(cx, cy + s * 0.45f, s * 0.16f,
                    Paint(fg).apply { style = Paint.Style.FILL })
                canvas.drawLine(cx - s * 0.3f, cy + s * 0.12f, cx + s * 0.3f, cy + s * 0.12f, p)
            }
            10 -> { // 应用方块
                val p = Paint(fg).apply { style = Paint.Style.STROKE; strokeWidth = dp(2.0f) }
                canvas.drawRoundRect(RectF(cx - s * 0.75f, cy - s * 0.75f, cx + s * 0.75f, cy + s * 0.75f), dp(4f), dp(4f), p)
                canvas.drawCircle(cx, cy, s * 0.22f, Paint(fg).apply { style = Paint.Style.FILL })
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
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var iconCache: android.graphics.Bitmap? = null
    private var monoCache: android.graphics.Bitmap? = null
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFCFCFCF.toInt()
        textSize = dp(11f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
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
            1 -> { // 彩色图标：直接用模块自身的图标铺满，效果和真机一致
                val icon = appIconBitmap() ?: return
                val save = canvas.save()
                canvas.clipPath(Path().apply { addCircle(cx, cy, r, Path.Direction.CW) })
                canvas.drawBitmap(icon, null, RectF(cx - r, cy - r, cx + r, cy + r), bitmapPaint)
                canvas.restoreToCount(save)
            }
            2 -> { // 剪影：黑底 + 白色字形（模拟真实的反色字形提取）
                val glyph = monoBitmap() ?: return
                val t = r * 0.64f
                canvas.drawBitmap(glyph, null, RectF(cx - t, cy - t, cx + t, cy + t), bitmapPaint)
            }
            3 -> Unit // 纯黑遮罩
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

    /** 模块图标位图（用于彩色图标预览），按 4 倍预览尺寸渲染保证清晰 */
    private fun appIconBitmap(): android.graphics.Bitmap? {
        iconCache?.let { return it }
        val size = 256
        val d = context.getDrawable(com.u9521.wooboxforredmagicos.R.drawable.app_icon) ?: return null
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val cc = Canvas(bmp)
        d.setBounds(0, 0, size, size)
        d.draw(cc)
        iconCache = bmp
        return bmp
    }

    /**
     * 剪影预览：直接用启动图标的白色前景矢量（Woo + 方块），
     * 裁掉透明边后缩放，和真机上提取出的字形观感一致，也干净。
     */
    private fun monoBitmap(): android.graphics.Bitmap? {
        monoCache?.let { return it }
        val d = context.getDrawable(com.u9521.wooboxforredmagicos.R.drawable.ic_launcher_foreground) ?: return null
        val n = 256
        val src = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val cc = Canvas(src)
        d.setBounds(0, 0, n, n)
        d.draw(cc)

        val px = IntArray(n * n)
        src.getPixels(px, 0, n, 0, 0, n, n)
        var minX = n
        var minY = n
        var maxX = -1
        var maxY = -1
        for (y in 0 until n) {
            for (x in 0 until n) {
                if (Color.alpha(px[y * n + x]) > 16) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return null
        val w = maxX - minX + 1
        val h = maxY - minY + 1
        val side = maxOf(w, h)
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val c2 = Canvas(out)
        val dx = (side - w) / 2
        val dy = (side - h) / 2
        c2.drawBitmap(src, Rect(minX, minY, maxX + 1, maxY + 1),
            Rect(dx, dy, dx + w, dy + h), bitmapPaint)
        monoCache = out
        return out
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

/** 支持左右滑动切换 tab 的 ScrollView */
class SwipeScrollView(context: Context, private val onSwipe: (Int) -> Unit) : ScrollView(context) {

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (e1 == null) return false
            val dx = e2.x - e1.x
            val dy = e2.y - e1.y
            val threshold = 110 * resources.displayMetrics.density
            if (kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f &&
                kotlin.math.abs(dx) > threshold &&
                kotlin.math.abs(vx) > 700f
            ) {
                onSwipe(if (dx < 0) 1 else -1)
                return true
            }
            return false
        }
    })

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        detector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }
}