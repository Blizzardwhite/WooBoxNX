package com.bw.wooboxnx.hook.app.doubleApp

import android.annotation.SuppressLint
import android.content.Context
import com.bw.wooboxnx.util.hasEnable
import com.bw.wooboxnx.util.xposed.base.HookRegister
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

object RmLowMemoryLimit : HookRegister() {

    @SuppressLint("PrivateApi")
    override fun init() = hasEnable("rm_double_low_memory_limit") {
        val showLimited = getDefaultCL().loadClass("com.zte.cn.doubleapp.common.Utils")
            .getDeclaredMethod("showLimitedApps", Context::class.java)
        val showLimitedHooker = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                super.beforeHookedMethod(param)
                param!!.result = false
            }
        }
        XposedBridge.hookMethod(showLimited, showLimitedHooker)
    }
}