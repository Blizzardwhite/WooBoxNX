package com.bw.wooboxnx.hook.app.settings

import android.content.Context
import com.bw.wooboxnx.util.hasEnable
import com.bw.wooboxnx.util.xposed.Log
import com.bw.wooboxnx.util.xposed.base.HookRegister
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

object ForceShowVASwitch : HookRegister() {
    override fun init() = hasEnable("setting_force_show_va_switch") {
        val maskVA = getDefaultCL().loadClass("com.zte.settings.utils.ZteUtilityUtils")
            .getDeclaredMethod("needMaskVoiceAssistant", Context::class.java)
        val maskVAHooker = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                super.beforeHookedMethod(param)
                param!!.result = false
                Log.i("MaskVoiceAssistant set to false")
            }
        }
        XposedBridge.hookMethod(maskVA, maskVAHooker)
    }
}