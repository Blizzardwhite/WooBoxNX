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
                            runCatching { attach(param.thisObject as? ViewGroup ?: return) }
                        }
                    })
                }.onFailure {
                    Log.px("W", "ShadeBackground skip $name", it, logInRelease = true)
                }
            }
            // 通知页有几层全屏 ScrimView（其中一层是从上往下的渐变），
            // 它们让两页底色不一致，这里让通知窗口里的 ScrimView 不绘制。
            runCatching {
                val scrim = getDefaultCL().loadClass("com.android.systemui.scrim.ScrimView")
                val onDraw = scrim.getDeclaredMethod("onDraw", android.graphics.Canvas::class.java)
                XposedBridge.hookMethod(onDraw, object : XC_MethodHook() {
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
                Log.px("W", "ScrimView onDraw hook skipped", it, logInRelease = true)
            }

            // 状态栏窗口自己有一层不透明底（status_bar_container 的 BarBackgroundDrawable），
            // 下拉时会在顶部留一条黑边，和我们的统一背景不一致，这里把它去掉。
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
            }.onFailure { Log.px("W", "status bar bg hook skipped", it, logInRelease = true) }
            // 只要面板背景功能开启，就拦截通知/QS 容器再次设置那层黑色渐变
            XposedBridge.hookAllMethods(View::class.java, "setBackground", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        if (containerId == 0) return
                        val v = param.thisObject as? View ?: return
                        if (v.id == containerId) param.args[0] = null
                    }
                }
            })

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

        // 通知/QS 容器自带的黑色渐变背景（#FF000000 → #59*），
        // 它就是切换时看到的那层"上黑下浅"，用统一背景时去掉它。
        root.postDelayed({
            runCatching {
                if (containerId == 0) {
                    containerId = root.resources.getIdentifier(
                        "notification_container_parent", "id", "com.android.systemui"
                    )
                }
                if (containerId != 0) root.findViewById<View>(containerId)?.background = null
            }
        }, 1200)

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
