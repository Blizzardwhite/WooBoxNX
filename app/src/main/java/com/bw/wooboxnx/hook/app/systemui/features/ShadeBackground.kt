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
 * 在面板/快速设置窗口的“模糊层”位置铺一块不透明底色。
 *
 * 左右切换会闪黑，是因为两个窗口（NotificationShade / control_center）
 * 的底层各自变化。把两层都换成同一块静止底色后，切换时就没有底层可闪了。
 */
object ShadeBackground : HookRegister() {

    private const val TAG = "woobox_shade_bg"
    private var containerId = 0

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
                            val root = param.thisObject as? ViewGroup ?: return
                            if (containerId == 0) {
                                containerId = runCatching {
                                    root.resources.getIdentifier(
                                        "notification_container_parent", "id", "com.android.systemui"
                                    )
                                }.getOrDefault(0)
                            }
                            runCatching { attach(root) }
                        }
                    })
                }.onFailure {
                    Log.px("W", "ShadeBackground skip $name", it, logInRelease = true)
                }
            }

            // 去掉通知/QS 容器自带的黑色渐变，避免和底色叠出上下不一致
            XposedBridge.hookAllMethods(View::class.java, "setBackground", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val v = param.thisObject as? View ?: return
                        if (containerId != 0 && v.id == containerId) {
                            param.args[0] = null
                            return
                        }
                        // 顶部渐变（不透明黑 → 透明）也去掉，这类渐变是切换闪烁的来源之一
                        val d = param.args.getOrNull(0) as? android.graphics.drawable.GradientDrawable ?: return
                        val colors = d.colors ?: return
                        if (colors.size >= 2) {
                            val topA = colors[0] ushr 24
                            val bottomA = colors[colors.size - 1] ushr 24
                            if (topA >= 0xC0 && bottomA <= 0x40) {
                                param.args[0] = null
                            }
                        }
                    }
                }
            })

            // 状态栏窗口自带一条不透明底/渐变，下拉时会在顶部压出黑边
            runCatching {
                val clazz = getDefaultCL().loadClass(
                    "com.android.systemui.statusbar.window.StatusBarWindowView"
                )
                XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val root = param.thisObject as? ViewGroup ?: return
                        root.postDelayed({
                            runCatching {
                                val id = root.resources.getIdentifier(
                                    "status_bar_container", "id", "com.android.systemui"
                                )
                                if (id != 0) root.findViewById<View>(id)?.background = null
                            }
                        }, 1200)
                    }
                })
            }.onFailure { Log.px("W", "statusbar bg skip", it, logInRelease = true) }

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
        val blur = XSPUtils.getInt("shade_bg_blur", 0).coerceIn(0, 60)
        val dim = XSPUtils.getInt("shade_bg_dim", 30).coerceIn(0, 80)

        val iv = ImageView(ctx).apply {
            tag = TAG
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        runCatching { root.addView(iv, 0, ViewGroup.LayoutParams(-1, -1)) }

        if (mode == 1) {
            iv.setBackgroundColor(0xFF1C1C20.toInt())
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
