package com.bw.wooboxnx.hook.app.android

import com.bw.wooboxnx.util.hasEnable
import com.bw.wooboxnx.util.xposed.Log
import com.bw.wooboxnx.util.xposed.base.HookRegister

/**
 * 解除本 ROM 的启动画面屏蔽名单。
 *
 * ActivityRecord 里有一份 mNonSplashScreenApp（微信、应用中心、应用市场等），
 * addStartingWindow() 命中时不创建启动窗口，点击后没有任何过渡画面。
 * 清空这份名单后，这些应用也会正常显示系统启动画面。
 */
object AllowSplashScreen : HookRegister() {
    override fun init() = hasEnable("splash_allow_list_all") {
        runCatching {
            val ar = getDefaultCL().loadClass("com.android.server.wm.ActivityRecord")
            val field = ar.getDeclaredField("mNonSplashScreenApp")
            field.isAccessible = true
            val set = field.get(null) as? MutableSet<*>
            val size = set?.size ?: 0
            set?.clear()
            Log.px("I", "cleared splash blacklist, $size apps", logInRelease = true)
        }.onFailure {
            Log.px("E", "clear splash blacklist failed", it, logInRelease = true)
        }
    }
}
