package com.bw.wooboxnx.hook.app.android

import android.annotation.SuppressLint
import com.bw.wooboxnx.util.hasEnable
import com.bw.wooboxnx.util.xposed.base.HookRegister
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

object RemoveAlertWindowsNotification : HookRegister() {
    @SuppressLint("PrivateApi")
    override fun init() {
        hasEnable("remove_alert_windows_notification") {
            val onPN = getDefaultCL().loadClass("com.android.server.wm.AlertWindowNotification")
                .getDeclaredMethod("onPostNotification")
            val onPNHooker = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam?) {
                    super.beforeHookedMethod(param)
                    param!!.result = null
                }
            }
            XposedBridge.hookMethod(onPN, onPNHooker)
        }
    }
}