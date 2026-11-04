package com.bw.wooboxnx.hook.app.systemui.features

import com.bw.wooboxnx.util.hasEnable
import com.bw.wooboxnx.util.xposed.base.HookRegister
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

object NoVibrateVolKeyLongPress : HookRegister() {
    override fun init() = hasEnable("no_vibrate_volKey_Long_press") {
        val vibrateForVolMe =
            getDefaultCL().loadClass("com.zte.adapt.mifavor.volume.VolumeDialogImplAdapt")
                .getDeclaredMethod("richTapVibrateForVolumeKeyLongPress")
        val vibrateForVolMeHooker = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                super.beforeHookedMethod(param)
                param!!.result = null
            }
        }
        XposedBridge.hookMethod(vibrateForVolMe, vibrateForVolMeHooker)
    }
}