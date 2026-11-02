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
 * 系统里可能有两套实现，这里都兜住：
 *  1. ZTE 浮窗：FloatWindowSensorView 里的 ImageView(sensorView)，图标是它的背景图；
 *  2. AOSP：UdfpsFpDrawable.draw() 画出的指纹路径。
 */
object FingerprintRing : HookRegister() {
    private const val SENSOR_VIEW_CLASS = "com.zte.feature.fingerprintlcd.FloatWindowSensorView"
    private const val FEATURE_CLASS = "com.zte.feature.fingerprintlcd.FingerprintUnderLcdFeature"
    private const val UDFPS_DRAWABLE_CLASS = "com.android.systemui.biometrics.UdfpsFpDrawable"

    /** 圆环线宽，占图标直径的比例 */
    private const val RING_STROKE_RATIO = 0.075f

    override fun init() = hasEnable("fp_ring") {
        runCatching { hookSensorView() }
        runCatching { hookUdfpsDrawable() }
    }

    /**
     * 指纹 UI 是独立的应用（com.fingerprint.sensorservice），
     * 图标最终是 ImageView 的背景/前景资源，这里按资源名兜底替换。
     */
    fun installImageViewHook() {
        runCatching {
            val hook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam?) {
                    val view = param?.thisObject as? ImageView ?: return
                    val id = param.args?.getOrNull(0) as? Int ?: return
                    val name = runCatching { view.resources.getResourceEntryName(id) }.getOrNull()
                        ?: return
                    if (!name.contains("finger", ignoreCase = true)) return
                    val color = if (name.contains("black", ignoreCase = true)) Color.BLACK else Color.WHITE
                    android.util.Log.d("WBRing", "imageView $name -> ring")
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

    /** ZTE 浮窗里的 sensorView：把背景图换成圆环 */
    private fun hookSensorView() {
        val cls = XposedHelpers.findClass(SENSOR_VIEW_CLASS, getDefaultCL())
        android.util.Log.i("WooBox-FPUI", "hookSensorView: class found")
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam?) {
                val view = XposedHelpers.getObjectField(param?.thisObject, "sensorView") as? ImageView
                    ?: return
                android.util.Log.i("WooBox-FPUI", "sensorView -> ring (${param?.method?.name})")
                view.background = RingDrawable(ringColor(param))
            }
        }
        XposedBridge.hookAllMethods(cls, "initSensorView", hook)
        XposedBridge.hookAllMethods(cls, "setSensorViewIconForRedmagic", hook)
    }

    /** AOSP UDFPS 的指纹图形 */
    private fun hookUdfpsDrawable() {
        val cls = XposedHelpers.findClass(UDFPS_DRAWABLE_CLASS, getDefaultCL())
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
                android.util.Log.d("WBRing", "udfps drawable -> ring alpha=$alpha")
                drawRing(canvas, shape.bounds, Color.WHITE, alpha)
                param.result = null
            }
        })
    }

    /** 跟随系统的深/浅色判断：keyguard 颜色为 -1 时用白色，否则黑色 */
    private fun ringColor(param: XC_MethodHook.MethodHookParam?): Int {
        val feature = param?.args?.lastOrNull { it?.javaClass?.name == FEATURE_CLASS } ?: return Color.WHITE
        return runCatching {
            val color = XposedHelpers.callMethod(feature, "getKeyguardColor") as Int
            if (color == -1) Color.WHITE else Color.BLACK
        }.getOrDefault(Color.WHITE)
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
