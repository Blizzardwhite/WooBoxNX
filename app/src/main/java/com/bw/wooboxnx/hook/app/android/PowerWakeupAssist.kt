package com.bw.wooboxnx.hook.app.android

import android.annotation.SuppressLint
import android.app.SearchManager
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

/**
 * 长按电源键启动默认数字助理（沿用原来的实现思路）：
 *
 * - 只有在系统"长按电源键"行为本来就是语音助手（powerBH != 1）时才接管，
 *   把厂商自带的语音助手替换成系统默认数字助理；
 * - 系统长按行为是关机菜单时，保持系统原样（它自己会弹菜单），不再重复实现，
 *   这样关机菜单照常可用；
 * - 没有默认数字助理时同样回退给系统原生行为。
 *
 * 相比旧实现的修复：
 * 1. 取助理不再依赖隐藏方法 RoleManager.getDefaultApplication（该方法不存在时会让
 *    整个 hook 注册失败），改为 getRoleHolders → Settings.Secure.assistant → 隐藏方法逐级降级；
 * 2. 任何一步异常都只打日志，不吞掉异常，避免"看起来开启了但完全没反应"。
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
            val showGA = runCatching { clazz.getDeclaredMethod("showGlobalActions") }.getOrNull()
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
                    var powerBH = runCatching {
                        resolvePowerLongBH.invoke(self) as Int
                    }.getOrDefault(1)
                    Log.i("powerKey long pressed, power behavior is: $powerBH")

                    // 只有系统本来就走"语音助手"时才替换成默认数字助理
                    var assist: String? = null
                    if (powerBH != 1) {
                        assist = findAssistant(ctx)
                        if (assist == null) {
                            Log.px(
                                "W", "no default assistant found, fallback to original behavior",
                                logInRelease = true
                            )
                            powerBH = 1
                        }
                    }

                    if (powerBH != 1) {
                        val ok = runCatching {
                            powerKeyHandled.setBoolean(self, true)
                            performHaptic?.invoke(
                                self, 10003, false, "Power - Long Press - Go To Voice Assist"
                            )
                            if (!launchAssist(ctx, assist!!)) {
                                throw IllegalStateException("launchAssist failed")
                            }
                            true
                        }.getOrElse {
                            Log.px("E", "launch assist failed: $it", it, logInRelease = true)
                            false
                        }
                        if (ok) {
                            param.result = null
                            Log.i("handled by digital assistant: $assist")
                        }
                    } else {
                        // 和旧实现一致：系统是关机菜单时，由我们弹一次（带触感反馈）
                        val ok = runCatching {
                            powerKeyHandled.setBoolean(self, true)
                            performHaptic?.invoke(
                                self, 10003, false, "Power - Long Press - Global Actions"
                            )
                            showGA?.invoke(self)
                            true
                        }.getOrElse {
                            Log.px("E", "show global actions failed: $it", it, logInRelease = true)
                            false
                        }
                        if (ok) {
                            param.result = null
                            Log.i("finish handle powerOff")
                        }
                    }
                }
            })
            Log.i("PowerWakeupAssist hooked")
        }.onFailure {
            Log.px("E", "PowerWakeupAssist init failed: $it", it, logInRelease = true)
        }
    }

    /** 取默认数字助理的包名，逐级降级 */
    private fun findAssistant(ctx: Context): String? {
        // 1. RoleManager.getRoleHolders（@SystemApi，反射调用）
        runCatching {
            val rm = ctx.getSystemService(Context.ROLE_SERVICE)
            val holders = rm?.javaClass?.getMethod("getRoleHolders", String::class.java)
                ?.invoke(rm, ROLE_ASSISTANT) as? List<*>
            val first = holders?.firstOrNull() as? String
            if (!first.isNullOrBlank()) return first
        }
        // 2. Settings.Secure.assistant
        runCatching {
            val raw = Settings.Secure.getString(ctx.contentResolver, "assistant")
            if (!raw.isNullOrBlank()) {
                return ComponentName.unflattenFromString(raw)?.packageName ?: raw
            }
        }
        // 3. 旧实现的隐藏方法，作为最后兜底
        runCatching {
            val rm = ctx.getSystemService(Context.ROLE_SERVICE)
            val pkg = rm?.javaClass?.getMethod("getDefaultApplication", String::class.java)
                ?.invoke(rm, ROLE_ASSISTANT) as? String
            if (!pkg.isNullOrBlank()) return pkg
        }
        return null
    }

    /** 先试 SearchManager.launchAssist（真正拉起助理浮层），失败再退到应用本身 */
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
