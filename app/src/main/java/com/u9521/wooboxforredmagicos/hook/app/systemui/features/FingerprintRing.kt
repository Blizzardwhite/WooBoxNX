package com.u9521.wooboxforredmagicos.hook.app.systemui.features

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.widget.ImageView
import com.u9521.wooboxforredmagicos.util.hasEnable
import com.u9521.wooboxforredmagicos.util.xposed.base.HookRegister
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 把锁屏的指纹识别图标换成圆环。
 *
 * 指纹图标其实画在独立系统应用 com.fingerprint.sensorservice 里，
 * 对应类 com.fingerprint.sensorservice.view.FloatWindowSensorView：
 *   - 图标控件字段 mSensorView（ImageView）
 *   - 设置图标的方法 setSensorViewBackground(int) / initSensorView(int, String)
 *   - 背景资源 fingerprint_senser_white / fingerprint_senser_black
 * 这里在它设置完图标之后，把背景换成自绘的圆环（颜色跟随原来的白色/黑色）。
 */
object FingerprintRing : HookRegister() {
    private const val SENSOR_VIEW_CLASS = "com.fingerprint.sensorservice.view.FloatWindowSensorView"
    private const val LEGACY_SENSOR_VIEW_CLASS = "com.zte.feature.fingerprintlcd.FloatWindowSensorView"
    private const val UDFPS_DRAWABLE_CLASS = "com.android.systemui.biometrics.UdfpsFpDrawable"

    /** 圆环线宽，占图标直径的比例 */
    private const val RING_STROKE_RATIO = 0.075f

    /** 圆环半径占可用半径的比例（比原图标小一点） */
    private const val RING_RADIUS_SCALE = 0.86f

    /** 浅色壁纸下用的深色圆环：比原来更深一些 */
    private val DARK_RING = Color.argb(199, 0, 0, 0)

    /** 圆环外圈的反色描边透明度（白环配黑边、深色环配白边），中心保持透明 */
    private const val OUTLINE_ALPHA = 0.28f

    /** 描边在圆环两侧各加宽的尺寸，占图标边长的比例 */
    private const val OUTLINE_WIDTH_RATIO = 0.016f

    override fun init() {
        // 不在注入时判断开关：开关在 hook 内部实时读取，
        // 这样打开/关闭立刻生效，不用重启常驻的指纹服务
        runCatching { hookSensorView(SENSOR_VIEW_CLASS, "mSensorView") }
        runCatching { hookSensorView(LEGACY_SENSOR_VIEW_CLASS, "sensorView") }
        runCatching { hookUdfpsDrawable() }
    }

    /** 实时读取开关，关闭后不再替换图标（原来的指纹图标会自然恢复） */
    private fun enabled(): Boolean = runCatching {
        com.u9521.wooboxforredmagicos.util.XSPUtils.getBoolean("fp_ring", false)
    }.getOrDefault(false)

    /** 指纹 UI 是独立应用，图标最终落到 ImageView 的背景资源上，这里按资源名兜底 */
    fun installImageViewHook() {
        runCatching {
            val hook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam?) {
                    if (!enabled()) return
                    val view = param?.thisObject as? ImageView ?: return
                    val id = param.args?.getOrNull(0) as? Int ?: return
                    val name = runCatching { view.resources.getResourceEntryName(id) }.getOrNull()
                        ?: return
                    if (!name.contains("finger", ignoreCase = true)) return
                    android.util.Log.i("WooBox-FPUI", "imageView $name -> ring")
                    val color = if (name.contains("black", ignoreCase = true)) DARK_RING else Color.WHITE
                    if (param.method.name.contains("Background")) {
                        view.background = RingDrawable(color)
                    } else {
                        view.setImageDrawable(RingDrawable(color))
                    }
                }
            }
            XposedBridge.hookAllMethods(ImageView::class.java, "setBackgroundResource", hook)
            XposedBridge.hookAllMethods(ImageView::class.java, "setImageResource", hook)
        }
    }

    private fun hookSensorView(className: String, fieldName: String) {
        val cls = runCatching { XposedHelpers.findClass(className, getDefaultCL()) }.getOrNull()
            ?: return
        android.util.Log.i("WooBox-FPUI", "hooked $className")
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam?) {
                if (!enabled()) return
                val self = param?.thisObject ?: return
                val view = runCatching {
                    XposedHelpers.getObjectField(self, fieldName)
                }.getOrNull() as? ImageView ?: return
                android.util.Log.i("WooBox-FPUI", "${param.method.name} -> ring")
                view.background = RingDrawable(colorOf(view, param))
            }
        }
        listOf(
            "setSensorViewBackground",
            "initSensorView",
            "setSensorViewIconForRedmagic",
            "setSensorWindowBackgroundResource"
        ).forEach { method ->
            runCatching { XposedBridge.hookAllMethods(cls, method, hook) }
        }
    }

    /** 跟随系统的深/浅色判断：keyguard 颜色为 -1 时用白色，否则黑色（和原生逻辑一致） */
    private fun colorOf(view: ImageView, param: XC_MethodHook.MethodHookParam): Int {
        (param.args?.getOrNull(0) as? Int)?.let { keyguardColor ->
            android.util.Log.i("WooBox-FPUI", "keyguardColor=$keyguardColor")
            return if (keyguardColor == -1) Color.WHITE else DARK_RING
        }
        val id = param.args?.firstOrNull { it is Int } as? Int ?: return Color.WHITE
        val name = runCatching { view.resources.getResourceEntryName(id) }.getOrNull()
            ?: return Color.WHITE
        return if (name.contains("black", ignoreCase = true)) DARK_RING else Color.WHITE
    }

    /** AOSP UDFPS 的指纹图形（部分机型/界面走这条） */
    private fun hookUdfpsDrawable() {
        val cls = runCatching { XposedHelpers.findClass(UDFPS_DRAWABLE_CLASS, getDefaultCL()) }.getOrNull()
            ?: return
        XposedBridge.hookAllMethods(cls, "draw", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                if (!enabled()) return
                val self = param?.thisObject ?: return
                val canvas = param.args?.getOrNull(0) as? Canvas ?: return
                if (XposedHelpers.getBooleanField(self, "isDisplayConfigured")) {
                    param.result = null
                    return
                }
                val shape = XposedHelpers.getObjectField(self, "fingerprintDrawable") as? Drawable
                    ?: return
                val alpha = runCatching {
                    XposedHelpers.getIntField(self, "_alpha")
                }.getOrDefault(255)
                drawRing(canvas, shape.bounds, Color.WHITE, alpha)
                param.result = null
            }
        })
    }

    private fun drawRing(canvas: Canvas, bounds: Rect, color: Int, alpha: Int) {
        if (bounds.isEmpty) return
        val size = minOf(bounds.width(), bounds.height()).toFloat()
        if (size <= 0f) return
        val stroke = size * RING_STROKE_RATIO
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val radius = (size / 2f - stroke / 2f) * RING_RADIUS_SCALE
        val outlineWidth = size * OUTLINE_WIDTH_RATIO

        // 只有白色圆环才加反色描边（浅色/白色背景上靠它显形）
        if (color == Color.WHITE) {
            val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                this.color = Color.BLACK
                strokeWidth = stroke + outlineWidth * 2
                this.alpha = (255 * OUTLINE_ALPHA * alpha / 255f).toInt()
            }
            canvas.drawCircle(cx, cy, radius, outline)
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            this.color = color
            strokeWidth = stroke
            this.alpha = alpha
        }
        canvas.drawCircle(cx, cy, radius, paint)
    }

    private class RingDrawable(private val color: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private var drawableAlpha = 255

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            val size = minOf(b.width(), b.height()).toFloat()
            if (size <= 0f) return
            val alphaFactor = drawableAlpha / 255f
            val stroke = size * RING_STROKE_RATIO
            val radius = (size / 2f - stroke / 2f) * RING_RADIUS_SCALE
            val outlineWidth = size * OUTLINE_WIDTH_RATIO

            // 只有白色圆环才需要外圈描边：浅色/白色背景上靠它显形；
            // 深色圆环本身在浅色背景上已经清楚，不加白边
            if (color == Color.WHITE) {
                outlinePaint.color = Color.BLACK
                outlinePaint.strokeWidth = stroke + outlineWidth * 2
                outlinePaint.alpha = (255 * OUTLINE_ALPHA * alphaFactor).toInt()
                canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), radius, outlinePaint)
            }

            paint.color = color
            paint.strokeWidth = stroke
            paint.alpha = (Color.alpha(color) * alphaFactor).toInt()
            canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), radius, paint)
        }

        override fun setAlpha(alpha: Int) {
            drawableAlpha = alpha
            invalidateSelf()
        }

        override fun getAlpha(): Int = drawableAlpha

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
