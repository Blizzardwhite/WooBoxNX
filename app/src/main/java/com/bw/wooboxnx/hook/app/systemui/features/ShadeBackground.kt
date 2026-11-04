package com.bw.wooboxnx.hook.app.systemui.features

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import com.bw.wooboxnx.util.hasEnable
import com.bw.wooboxnx.util.xposed.Log
import com.bw.wooboxnx.util.xposed.base.HookRegister
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * 在两块面板的「模糊层」位置铺同一层不透明底色。
 *
 * 通知中心和快速设置是两个窗口，各自有自己的底色/遮罩，左右切换时互相接力会闪。
 * 这里把两页的底色都去掉，只在最底层放一层共用的底色：
 *   - 位置在内容之下、壁纸之上（就是原来模糊层的位置）
 *   - 两页颜色完全一致，且不透明，切换时无论怎么叠加/交叉淡入，露出来的都是同一个颜色
 */
object ShadeBackground : HookRegister() {

    private const val TAG = "woobox_shade_scrim"
    private var containerId = 0

    override fun init() = hasEnable("shade_scrim") {
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

            // 去掉面板自带的底色/渐变，只保留我们这一层
            XposedBridge.hookAllMethods(View::class.java, "setBackground", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val v = param.thisObject as? View ?: return
                        if (containerId != 0 && v.id == containerId) {
                            param.args[0] = null
                            return
                        }
                        val d = param.args.getOrNull(0) as? android.graphics.drawable.GradientDrawable ?: return
                        val colors = d.colors ?: return
                        if (colors.size >= 2) {
                            val topA = colors[0] ushr 24
                            val bottomA = colors[colors.size - 1] ushr 24
                            if (topA >= 0xC0 && bottomA <= 0x40) param.args[0] = null
                        }
                    }
                }
            })

            Log.px("I", "ShadeBackground hooked", logInRelease = true)
        }.onFailure {
            Log.px("E", "ShadeBackground init failed", it, logInRelease = true)
        }
    }

    private fun attach(root: ViewGroup) {

        if (root.findViewWithTag<View>(TAG) != null) return
        val v = View(root.context).apply {
            tag = TAG
            setBackgroundColor(0xFF1C1C20.toInt()) // 不透明底色：叠加或交叉淡入都不会变色
        }
        runCatching { root.addView(v, 0, ViewGroup.LayoutParams(-1, -1)) }

        // 窗口自身可能带渐变底色，它会透出来
        root.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                view: View, left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
            ) {
                root.removeOnLayoutChangeListener(this)
                runCatching { root.background = null }
                runCatching { (root.rootView as? View)?.background = null }
                // 窗口里的 ScrimView 会在切换过程中额外叠一层暗色，直接隐藏
                runCatching {
                    fun hideScrims(v: View) {
                        if (v.javaClass.simpleName.contains("ScrimView")) {
                            v.visibility = View.GONE
                        }
                        if (v is ViewGroup) for (i in 0 until v.childCount) hideScrims(v.getChildAt(i))
                    }
                    hideScrims(root)
                }
            }
        })
    }
}
