package com.u9521.wooboxforredmagicos.hook.app.android

import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import com.u9521.wooboxforredmagicos.util.hasEnable
import com.u9521.wooboxforredmagicos.util.xposed.Log
import com.u9521.wooboxforredmagicos.util.xposed.base.HookRegister
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * 长按电源键启动默认数字助理。
 *
 * 和旧实现相比的关键区别：
 * 1. 不再依赖 getResolvedLongPressOnPowerBehavior() 必须等于"语音助手"，
 *    只要系统里有默认数字助理就直接接管长按；
 * 2. 用公开的 RoleManager.getRoleHolders 取助理，找不到再读 Settings.Secure.assistant，
 *    避免隐藏方法不存在时整个 hook 注册失败；
 * 3. 任何一步失败都不拦截原生行为，至少还能正常弹出关机菜单。
 */
object PowerWakeupAssist : HookRegister() {

    private const val ROLE_ASSISTANT = "android.app.role.ASSISTANT"

    @SuppressLint("PrivateApi")
    override fun init() = hasEnable("long_power_key_wakeup_assist") {
        runCatching {
            val clazz = getDefaultCL().loadClass("com.android.server.policy.PhoneWindowManager")
            val powerLongPress =
                clazz.getDeclaredMethod("powerLongPress", Long::class.javaPrimitiveType)
            val resolvePowerLongBH =
                clazz.getDeclaredMethod("getResolvedLongPressOnPowerBehavior")
            val performHaptic = runCatching {
                clazz.getDeclaredMethod(
                    "performHapticFeedback",
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    String::class.java
                )
            }.getOrNull()
            val powerKeyHandled = clazz.getDeclaredField("mPowerKeyHandled")
            val mContext = clazz.getDeclaredField("mContext")

            XposedBridge.hookMethod(powerLongPress, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val self = param.thisObject
                    val ctx = runCatching { mContext.get(self) as Context }.getOrNull() ?: return
                    val powerBH = runCatching {
                        resolvePowerLongBH.invoke(self) as Int
                    }.getOrDefault(-1)
                    val assist = findAssistant(ctx)
                    Log.i("powerKey long pressed, behavior=$powerBH, assistant=$assist")

                    if (assist == null) {
                        // 没有默认数字助理，交给系统原生处理（正常弹关机菜单）
                        Log.i("no default assistant, keep original power behavior")
                        return
                    }

                    val ok = runCatching {
                        powerKeyHandled.setBoolean(self, true)
                        performHaptic?.invoke(
                            self, 10003, false, "Power - Long Press - Go To Voice Assist"
                        )
                        if (!launchAssist(ctx, assist)) throw IllegalStateException("launchAssist failed")
                        true
                    }.getOrElse {
                        Log.px("E", "launch assist failed: ${it}", it, logInRelease = true)
                        false
                    }
                    if (ok) {
                        param.result = null
                        Log.i("handled by digital assistant")
                    }
                }
            })
            Log.i("PowerWakeupAssist hooked")
        }.onFailure {
            Log.px("E", "PowerWakeupAssist init failed: ${it}", it, logInRelease = true)
        }
    }

    /** 取默认数字助理的包名：优先 RoleManager，再退回 Settings.Secure */
    private fun findAssistant(ctx: Context): String? {
        runCatching {
            // getRoleHolders 是 @SystemApi，编译期不可见，这里走反射（system_server 有权限）
            val rm = ctx.getSystemService(Context.ROLE_SERVICE)
            val holders = rm?.javaClass?.getMethod("getRoleHolders", String::class.java)
                ?.invoke(rm, ROLE_ASSISTANT) as? List<*>
            val first = holders?.firstOrNull() as? String
            if (!first.isNullOrBlank()) return first
        }
        runCatching {
            val raw = Settings.Secure.getString(ctx.contentResolver, "assistant")
            if (!raw.isNullOrBlank()) {
                return ComponentName.unflattenFromString(raw)?.packageName ?: raw
            }
        }
        return null
    }

    /** 先试 SearchManager.launchAssist（真正拉起助理浮层），失败再退到 ACTION_ASSIST */
    private fun launchAssist(ctx: Context, pkg: String): Boolean {
        val first = runCatching {
            val sm = ctx.getSystemService(Context.SEARCH_SERVICE) as SearchManager
            sm.javaClass.getMethod("launchAssist", Bundle::class.java).invoke(sm, Bundle())
            true
        }.getOrDefault(false)
        if (first) return true

        return runCatching {
            val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
                ?: Intent(Intent.ACTION_ASSIST).setPackage(pkg)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
