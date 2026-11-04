package com.bw.wooboxnx.hook.app.systemui.features

import android.annotation.SuppressLint
import com.bw.wooboxnx.util.hasEnable
import com.bw.wooboxnx.util.xposed.base.HookRegister
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

object UnHideClipBoardOverlay : HookRegister() {
    @SuppressLint("PrivateApi")
    override fun init() = hasEnable("unhide_clipboard_overlay") {
        val clipListener =
            getDefaultCL().loadClass("com.android.systemui.clipboardoverlay.ClipboardListener")
                .getDeclaredMethod("forceSuppressOverlay")
        val clipHooker = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                super.beforeHookedMethod(param)
                param!!.result = false
            }
        }
        XposedBridge.hookMethod(clipListener, clipHooker)
    }
}