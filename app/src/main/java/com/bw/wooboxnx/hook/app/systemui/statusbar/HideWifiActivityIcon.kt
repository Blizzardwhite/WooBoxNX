package com.bw.wooboxnx.hook.app.systemui.statusbar

import android.annotation.SuppressLint
import android.view.View
import android.widget.ImageView
import com.bw.wooboxnx.util.hasEnable
import com.bw.wooboxnx.util.xposed.base.HookRegister
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge


object HideWifiActivityIcon : HookRegister() {
    @SuppressLint("PrivateApi")
    override fun init() = hasEnable("hide_wifi_activity_icon") {
        //隐藏WIFI箭头
        val sBWView = getDefaultCL().loadClass("com.android.systemui.statusbar.StatusBarWifiView")
        val wifiArrayHooker = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam?) {
                super.afterHookedMethod(param)
                val mInView =
                    sBWView.getDeclaredField("mIn").get(param!!.thisObject) as ImageView
                mInView.visibility = View.GONE
            }
        }
        val initViewState = sBWView.getDeclaredMethod("initViewState")
        val updateState = sBWView.getDeclaredMethod(
            "updateState",
            getDefaultCL().loadClass("com.android.systemui.statusbar.phone.StatusBarSignalPolicy\$WifiIconState")
        )
        XposedBridge.hookMethod(initViewState, wifiArrayHooker)
        XposedBridge.hookMethod(updateState, wifiArrayHooker)
    }
}