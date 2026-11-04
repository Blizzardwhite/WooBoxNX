package com.bw.wooboxnx.hook.app.android

import android.content.ContentValues
import android.net.Uri
import com.bw.wooboxnx.util.hasEnable
import com.bw.wooboxnx.util.xposed.Log
import com.bw.wooboxnx.util.xposed.base.HookRegister
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 给「关闭了启动预览窗口」的应用补一个启动画面。
 *
 * 原理：ActivityRecord.getStartingWindowType(...) 返回本次启动要显示的启动窗口类型，
 * 0=不显示、1=任务快照、2=Splash。某些应用（以及本 ROM 的 mNonSplashScreenApp 名单）
 * 会得到 0，于是点击后屏幕上什么都没有，直到应用自己画出首帧。
 * 这里在「进程尚未启动」（冷启动）且类型为 0 时强制改成 Splash。
 */
object ForceStartingWindow : HookRegister() {

    private const val URI_LOG = "content://com.bw.wooboxnx.moon/log"

    override fun init() = hasEnable("force_starting_window") {
        runCatching {
            val cl = getDefaultCL()
            val ar = cl.loadClass("com.android.server.wm.ActivityRecord")

            // 这个 ROM 自带一份"不显示启动画面"的名单（微信、菜鸟、游戏空间等），
            // 命中时 addStartingWindow() 会直接返回 false。清空它即可恢复启动画面。
            runCatching {
                val f = ar.getDeclaredField("mNonSplashScreenApp")
                f.isAccessible = true
                val set = f.get(null) as? MutableSet<*>
                val size = set?.size ?: 0
                set?.clear()
                com.bw.wooboxnx.util.xposed.HookBeat.debug("cleared mNonSplashScreenApp size=$size")
                Log.px("I", "cleared mNonSplashScreenApp size=$size", logInRelease = true)
            }.onFailure {
                Log.px("E", "clear mNonSplashScreenApp failed", it, logInRelease = true)
            }
            val snapshotCls = Class.forName("android.window.TaskSnapshot", false, cl)
            val boolCls = java.lang.Boolean.TYPE
            val method = ar.getDeclaredMethod(
                "getStartingWindowType",
                boolCls, boolCls, boolCls, boolCls, boolCls, boolCls, snapshotCls
            )
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val newTask = param.args.getOrNull(0) as? Boolean ?: false
                        val taskSwitch = param.args.getOrNull(1) as? Boolean ?: false
                        val processRunning = param.args.getOrNull(2) as? Boolean ?: true
                        val type = param.result as? Int ?: return
                        val pkg = runCatching {
                            XposedHelpers.getObjectField(param.thisObject, "packageName") as String
                        }.getOrNull().orEmpty()
                        com.bw.wooboxnx.util.xposed.HookBeat.debug(
                            "startWindow pkg=$pkg type=$type newTask=$newTask switch=$taskSwitch running=$processRunning"
                        )
                        if (!processRunning && type == 0) {
                            param.result = 2
                            com.bw.wooboxnx.util.xposed.HookBeat.debug("force splash for $pkg")
                        }
                    }
                }
            })
            Log.px("I", "ForceStartingWindow hooked", logInRelease = true)
        }.onFailure {
            Log.px("E", "ForceStartingWindow init failed", it, logInRelease = true)
        }
    }
}
