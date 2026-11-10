package com.bw.wooboxnx.hook.app.systemui.features

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import com.bw.wooboxnx.util.hasEnable
import com.bw.wooboxnx.util.xposed.Log
import com.bw.wooboxnx.util.xposed.base.HookRegister
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

object GestureStartDefaultDigitalAssist : HookRegister() {
    override fun init() = hasEnable("gesture_use_default_digital_assist") {
        //adapt 返回默认数字助理
        val adaptClazz = getDefaultCL().loadClass("com.zte.adapt.mifavor.navbar.AssistManagerAdapt")
        val getAssistMe =
            adaptClazz.getDeclaredMethod("getAssistInfoForUser", ComponentName::class.java)
        val getAssistMeHooker = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                super.beforeHookedMethod(param)
                param!!.result = param.args[0]
            }
        }
        XposedBridge.hookMethod(getAssistMe, getAssistMeHooker)
        //不要接管
        val handleSAMe = adaptClazz.getDeclaredMethod("handleStartAssist", Bundle::class.java)
        val handleSAMeHooker = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                super.beforeHookedMethod(param)
                param!!.result = false
            }
        }
        XposedBridge.hookMethod(handleSAMe, handleSAMeHooker)
        //直接进语音界面
        hookVoiceEntry()
    }

    /**
     * 手势拉起助理时改走 ACTION_VOICE_COMMAND：
     * ACTION_ASSIST 只会打开助理主页，VOICE_COMMAND 才是语音监听入口。
     * 拉起失败（没装助理 / 助理不支持）就保持原样，让系统走原生流程。
     */
    private fun hookVoiceEntry() {
        runCatching {
            val assistCls = getDefaultCL().loadClass("com.android.systemui.assist.AssistManager")
            val startAssist = assistCls.getDeclaredMethod("startAssist", Bundle::class.java)
            val ctxField = assistCls.getDeclaredField("mContext")
            XposedBridge.hookMethod(startAssist, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val ctx = ctxField.get(param.thisObject) as? Context ?: return
                        val pkg = assistantPackage(ctx) ?: return
                        ctx.startActivity(
                            Intent(Intent.ACTION_VOICE_COMMAND)
                                .setPackage(pkg)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        // 不再走系统助理流程（否则会打开助理主页）
                        param.result = null
                        Log.i("gesture assist -> voice command: $pkg")
                    }.onFailure {
                        Log.px("W", "voice command launch failed: $it", logInRelease = true)
                    }
                }
            })
            Log.i("GestureStartDefaultDigitalAssist voice entry hooked")
        }.onFailure {
            Log.px("E", "hook voice entry failed: $it", it, logInRelease = true)
        }
    }

    /** 当前默认数字助理的包名 */
    private fun assistantPackage(ctx: Context): String? {
        val raw = runCatching {
            Settings.Secure.getString(ctx.contentResolver, "assistant")
        }.getOrNull()
        val pkg = runCatching {
            if (raw.isNullOrBlank()) null
            else ComponentName.unflattenFromString(raw)?.packageName
        }.getOrNull()
        return pkg ?: runCatching {
            ctx.packageManager.resolveActivity(Intent(Intent.ACTION_ASSIST), 0)
                ?.activityInfo?.packageName
        }.getOrNull()
    }
}
