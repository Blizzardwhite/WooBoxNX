package com.bw.wooboxnx.hook.app.systemui.features

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.bw.wooboxnx.util.XSPUtils
import com.bw.wooboxnx.util.xposed.Log
import com.bw.wooboxnx.util.xposed.base.HookRegister
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * 给通知面板和快速设置窗口铺一层统一的背景。
 *
 * 这两个页面是两个独立窗口（NotificationShade / control_center），
 * ROM 自己的背景层颜色和模糊不一致，左右切换时会闪一下。
 * 在窗口最底层插一张统一的背景（纯色或图片），两边就完全一致了。
 */
object ShadeBackground : HookRegister() {

    private const val TAG = "woobox_shade_bg"

    override fun init() {
        runCatching {
            listOf(
                "com.android.systemui.shade.NotificationShadeWindowView",
                "com.zte.controlcenter.ControlPanelWindowView"
            ).forEach { name ->
                runCatching {
                    val cls = getDefaultCL().loadClass(name)
                    XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            runCatching { attach(param.thisObject as? ViewGroup ?: return) }
                        }
                    })
                }.onFailure {
                    Log.px("W", "ShadeBackground skip $name", it, logInRelease = true)
                }
            }
            // 通知页额外有一层从上往下的渐变遮罩（ScrimView 绘制），
            // 盖住它会和快速设置页不一致，这里对通知面板的 ScrimView 跳过渐变设置。
            runCatching {
                val scrim = getDefaultCL().loadClass("com.android.systemui.scrim.ScrimView")
                val gradient = getDefaultCL().loadClass(
                    "com.android.internal.colorextraction.ColorExtractor\$GradientColors"
                )
                val setColors = scrim.getDeclaredMethod(
                    "setColors", gradient, Boolean::class.javaPrimitiveType
                )
                XposedBridge.hookMethod(setColors, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        runCatching {
                            val v = param.thisObject as? View ?: return
                            val root = v.rootView?.javaClass?.simpleName.orEmpty()
                            if (root.contains("NotificationShade")) {
                                param.result = null
                            }
                        }
                    }
                })
            }.onFailure {
                Log.px("W", "ScrimView hook skipped", it, logInRelease = true)
            }

            Log.px("I", "ShadeBackground hooked", logInRelease = true)
        }.onFailure {
            Log.px("E", "ShadeBackground init failed", it, logInRelease = true)
        }
    }

    private fun attach(root: ViewGroup) {
        val mode = XSPUtils.getInt("shade_bg_mode", 0)
        if (mode == 0) return
        if (root.findViewWithTag<View>(TAG) != null) return

        val ctx = root.context
        val blur = XSPUtils.getInt("shade_bg_blur", 24).coerceIn(0, 60)
        val dim = XSPUtils.getInt("shade_bg_dim", 30).coerceIn(0, 80)

        val iv = ImageView(ctx).apply {
            tag = TAG
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        runCatching { root.addView(iv, 0, ViewGroup.LayoutParams(-1, -1)) }

        if (mode == 1) {
            iv.setBackgroundColor(0xFF101014.toInt())
            return
        }

        Thread({
            val bmp = runCatching {
                ctx.contentResolver.openInputStream(
                    Uri.parse("content://com.bw.wooboxnx.moon/bg")
                )?.use { BitmapFactory.decodeStream(it) }
            }.getOrNull() ?: return@Thread
            iv.post {
                runCatching {
                    iv.setImageBitmap(bmp)
                    if (blur > 0) {
                        iv.setRenderEffect(
                            RenderEffect.createBlurEffect(
                                blur.toFloat(), blur.toFloat(), Shader.TileMode.CLAMP
                            )
                        )
                    }
                    if (dim > 0) {
                        iv.colorFilter = PorterDuffColorFilter(
                            Color.argb((dim / 100f * 255).toInt(), 0, 0, 0),
                            PorterDuff.Mode.SRC_OVER
                        )
                    }
                }
            }
        }, "woobox-bg").apply { isDaemon = true }.start()
    }
}
