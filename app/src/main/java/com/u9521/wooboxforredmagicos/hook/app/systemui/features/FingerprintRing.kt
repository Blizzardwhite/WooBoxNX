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

    override fun init() = hasEnable("fp_ring") {
        runCatching { hookSensorView(SENSOR_VIEW_CLASS, "mSensorView") }
        runCatching { hookSensorView(LEGACY_SENSOR_VIEW_CLASS, "sensorView") }
        runCatching { hookUdfpsDrawable() }
    }

    /** 指纹 UI 是独立应用，图标最终落到 ImageView 的背景资源上，这里按资源名兜底 */
    fun installImageViewHook() {
        runCatching {
            val hook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam?) {
                    val view = param?.thisObject as? ImageView ?: return
                    val id = param.args?.getOrNull(0) as? Int ?: return
                    val name = runCatching { view.resources.getResourceEntryName(id) }.getOrNull()
                        ?: return
                    if (!name.contains("finger", ignoreCase = true)) return
                    android.util.Log.i("WooBox-FPUI", "imageView $name -> ring")
                    val color = if (name.contains("black", ignoreCase = true)) Color.BLACK else Color.WHITE
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

    /** 从参数里的资源 id 判断该用白色还是黑色圆环 */
    private fun colorOf(view: ImageView, param: XC_MethodHook.MethodHookParam): Int {
        val id = param.args?.firstOrNull { it is Int } as? Int ?: return Color.WHITE
        val name = runCatching { view.resources.getResourceEntryName(id) }.getOrNull()
            ?: return Color.WHITE
        return if (name.contains("black", ignoreCase = true)) Color.BLACK else Color.WHITE
    }

    /** AOSP UDFPS 的指纹图形（部分机型/界面走这条） */
    private fun hookUdfpsDrawable() {
        val cls = runCatching { XposedHelpers.findClass(UDFPS_DRAWABLE_CLASS, getDefaultCL()) }.getOrNull()
            ?: return
        XposedBridge.hookAllMethods(cls, "draw", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
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
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            this.color = color
            strokeWidth = stroke
            this.alpha = alpha
        }
        canvas.drawCircle(
            bounds.exactCenterX(),
            bounds.exactCenterY(),
            size / 2f - stroke / 2f,
            paint
        )
    }

    private class RingDrawable(private val color: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            val size = minOf(b.width(), b.height()).toFloat()
            if (size <= 0f) return
            val stroke = size * RING_STROKE_RATIO
            paint.color = color
            paint.strokeWidth = stroke
            canvas.drawCircle(
                b.exactCenterX(),
                b.exactCenterY(),
                size / 2f - stroke / 2f,
                paint
            )
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun getAlpha(): Int = paint.alpha

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
