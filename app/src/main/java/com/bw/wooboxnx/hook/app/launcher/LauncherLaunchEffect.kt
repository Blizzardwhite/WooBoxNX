package com.bw.wooboxnx.hook.app.launcher

import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import com.bw.wooboxnx.util.hasEnable
import com.bw.wooboxnx.util.xposed.Log
import com.bw.wooboxnx.util.xposed.base.HookRegister
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * 点击图标时先播一个"放大淡出"动画。
 *
 * 应用冷启动期间桌面会静止一段（系统要 fork 进程、加载 APK），
 * 这里在点击的瞬间把图标复制一份铺到窗口上放大淡出，
 * 让这几百毫秒看起来是"正在打开"而不是卡住。
 */
object LauncherLaunchEffect : HookRegister() {

    private const val DURATION = 260L

    override fun init() = hasEnable("launcher_launch_effect") {
        runCatching {
            val launcher = getDefaultCL().loadClass("com.android.launcher3.Launcher")
            val onClick = launcher.getDeclaredMethod("onClick", View::class.java)
            XposedBridge.hookMethod(onClick, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val v = param.args.getOrNull(0) as? View ?: return
                        play(v)
                    }
                }
            })
            Log.px("I", "LauncherLaunchEffect hooked", logInRelease = true)
        }.onFailure {
            Log.px("E", "LauncherLaunchEffect init failed", it, logInRelease = true)
        }
    }
    private fun play(v: View) {
        // 只对应用图标生效：文件夹、小部件、空白区域都跳过
        val tagName = v.tag?.javaClass?.name.orEmpty()
        if (!tagName.contains("ShortcutInfo") && !tagName.contains("AppInfo")) return

        val drawable = when (v) {
            is TextView -> v.compoundDrawables.getOrNull(1) // 图标在上
            is ImageView -> v.drawable
            else -> null
        } ?: run {
            return
        }
        val root = v.rootView as? ViewGroup ?: return
        val size = if (v.width > 0) v.width else v.height
        if (size <= 0) return

        val loc = IntArray(2)
        v.getLocationInWindow(loc)

        val ctx = v.context
        val copy = ImageView(ctx).apply {
            val d = drawable.constantState?.newDrawable()?.mutate()
            setImageDrawable(d ?: drawable)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val lp = ViewGroup.LayoutParams(size, size)
        runCatching { root.addView(copy, lp) }.onFailure { return }
        copy.x = loc[0].toFloat()
        copy.y = loc[1].toFloat()

        val target = (root.width.toFloat() / size.coerceAtLeast(1)) * 1.15f
        copy.animate()
            .scaleX(target)
            .scaleY(target)
            .alpha(0f)
            .setDuration(DURATION)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .withEndAction {
                (copy.parent as? ViewGroup)?.removeView(copy)
            }
            .start()
    }
}
