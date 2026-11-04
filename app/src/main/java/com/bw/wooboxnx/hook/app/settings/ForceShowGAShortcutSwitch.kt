package com.bw.wooboxnx.hook.app.settings

import com.bw.wooboxnx.util.hasEnable
import com.bw.wooboxnx.util.xposed.Log
import com.bw.wooboxnx.util.xposed.base.HookRegister
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

object ForceShowGAShortcutSwitch : HookRegister() {
    override fun init() = hasEnable("setting_force_show_ga_shortcut_switch") {
        val maskGA =
            getDefaultCL().loadClass("com.zte.settings.utils.ZteUtilityUtils")
                .getDeclaredMethod("needMaskWakeupGoogleAssistant")
        val maskGAHooker = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                super.beforeHookedMethod(param)
                param!!.result = false
                Log.i("MaskWakeupGoogleAssistant set to false")
            }
        }
        XposedBridge.hookMethod(maskGA, maskGAHooker)
    }
}