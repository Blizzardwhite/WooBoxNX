package com.bw.wooboxnx.hook.app.android

import android.annotation.SuppressLint
import com.bw.wooboxnx.util.hasEnable
import com.bw.wooboxnx.util.xposed.Deoptimizer
import com.bw.wooboxnx.util.xposed.base.HookRegister
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

object AllowUntrustedTouches : HookRegister() {
    @SuppressLint("PrivateApi")
    override fun init() = hasEnable("allow_untrusted_touches") {
        //Landroid/view/InputWindowHandle;-><init>(Landroid/view/InputWindowHandle;)V
        // .field public static final blacklist ALLOW:I = 0x2
        // .field public static final blacklist BLOCK_UNTRUSTED:I = 0x0
        // .field public static final blacklist USE_OPACITY:I = 0x1
        val getTOM = getDefaultCL().loadClass("com.android.server.wm.WindowState")
            .getDeclaredMethod("getTouchOcclusionMode")
        val tOMHooker = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                super.beforeHookedMethod(param)
                param!!.result = 2
            }
        }
        XposedBridge.hookMethod(getTOM, tOMHooker)

        Deoptimizer.deoptimizeMethods(
            getDefaultCL().loadClass("com.android.server.wm.InputMonitor"),
            "populateOverlayInputInfo",
            "populateInputWindowHandle"
        )
    }
}