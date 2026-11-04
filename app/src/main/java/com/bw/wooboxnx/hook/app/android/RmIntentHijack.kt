package com.bw.wooboxnx.hook.app.android

import android.annotation.SuppressLint
import com.bw.wooboxnx.util.hasEnable
import com.bw.wooboxnx.util.xposed.Deoptimizer
import com.bw.wooboxnx.util.xposed.Log
import com.bw.wooboxnx.util.xposed.base.HookRegister
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

object RmIntentHijack : HookRegister() {
    @SuppressLint("PrivateApi")
    override fun init() = hasEnable("remove_intent_hijack") {
        val resolveIHClazz = getDefaultCL().loadClass("com.android.server.pm.ResolveIntentHelper")
        val isCTSMe = resolveIHClazz.getDeclaredMethod("isCtsTesting")
        val isCTSHooker = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                super.beforeHookedMethod(param)
                Log.i("ResolveIntentHelper: mock is cts")
                param!!.result = true
            }
        }
        XposedBridge.hookMethod(isCTSMe, isCTSHooker)

        Deoptimizer.deoptimizeMethod(resolveIHClazz, "chooseBestActivity")
    }
}