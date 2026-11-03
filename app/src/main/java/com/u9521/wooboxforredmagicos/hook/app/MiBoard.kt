package com.u9521.wooboxforredmagicos.hook.app

import android.appwidget.AppWidgetManager
import android.content.Context
import com.u9521.wooboxforredmagicos.util.XSPUtils
import com.u9521.wooboxforredmagicos.util.xposed.Log
import com.u9521.wooboxforredmagicos.util.xposed.base.AppRegister
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 负一屏（Z-Board / miboard）解除小部件白名单。
 *
 * 它的"更多服务"列表来自服务器 get_widget_list，只包含被收录的应用。
 * 这里在列表组装入口 CardAddListUtils.G(Context, ArrayList) 前，
 * 把本机所有已安装的 AppWidgetProvider 追加进去，
 * 之后由它自己的逻辑去匹配 provider、生成预览并绑定 AppWidgetHost。
 *
 * 注意：只支持 2x2 / 4x2（这是 miboard 自己的布局限制）。
 */
object MiBoard : AppRegister() {
    override val packageName: List<String> = listOf("com.zte.mifavor.miboard")
    override val processName: List<String> = emptyList()
    override val logTag: String = "WooBox-MiBoard"
    override val loadDexkit: Boolean = false

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!runCatching { XSPUtils.getBoolean("miboard_unlock_widgets", false) }
                .getOrDefault(false)) {
            return
        }
        runCatching {
            val e = lpparam.classLoader.loadClass("com.zte.mifavor.miboard.add.e")
            val g = e.getDeclaredMethod(
                "G", Context::class.java, ArrayList::class.java
            )
            XposedBridge.hookMethod(g, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val ctx = param.args[0] as Context
                        @Suppress("UNCHECKED_CAST")
                        val list = param.args[1] as? ArrayList<Any> ?: return
                        inject(ctx, list, lpparam.classLoader)
                    }.onFailure {
                        Log.px("E", "inject miboard widgets failed: $it", it, logInRelease = true)
                    }
                }
            })
            Log.i("MiBoard widget unlock hooked")
        }.onFailure {
            Log.px("E", "MiBoard init failed: $it", it, logInRelease = true)
        }
    }

    private fun inject(ctx: Context, list: ArrayList<Any>, cl: ClassLoader) {
        val awm = runCatching { AppWidgetManager.getInstance(ctx) }.getOrNull() ?: return
        val providers = runCatching { awm.installedProviders }.getOrNull() ?: return
        if (providers.isEmpty()) return

        val dataCls = cl.loadClass("com.zte.mifavor.miboard.add.data.CardAddListData")
        val detailCls = cl.loadClass("com.zte.mifavor.miboard.add.data.CardAddDetailsData")

        val dPkg = dataCls.getField("pkg")
        val dCls = dataCls.getField("cls")
        val dAppName = dataCls.getField("appName")
        val dSpanX = dataCls.getField("spanX")
        val dSpanY = dataCls.getField("spanY")
        val cardsField = dataCls.getField("cards")

        val iCls = detailCls.getField("cls")
        val iPkg = detailCls.getField("pkg")
        val iSpanX = detailCls.getField("spanX")
        val iSpanY = detailCls.getField("spanY")

        // 已经存在的 pkg|cls 不重复注入
        val existing = HashSet<String>()
        for (c in list) {
            val pkg = runCatching { dPkg.get(c) as? String }.getOrNull() ?: continue
            val cards = runCatching { cardsField.get(c) as? List<*> }.getOrNull() ?: continue
            for (d in cards) {
                val cls = runCatching { iCls.get(d) as? String }.getOrNull() ?: continue
                existing.add("$pkg|$cls")
            }
        }

        val pm = ctx.packageManager
        val added = ArrayList<Any>()
        for (p in providers) {
            val comp = p.provider ?: continue
            val key = "${comp.packageName}|${comp.className}"
            if (key in existing) continue
            // 2x2 或 4x2（miboard 只支持这两种跨度）
            val spanX = if (p.minWidth > 180) 4 else 2
            val label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(comp.packageName, 0)).toString()
            }.getOrDefault(comp.packageName)

            val detail = detailCls.getDeclaredConstructor().also { it.isAccessible = true }.newInstance()
            iPkg.set(detail, comp.packageName)
            iCls.set(detail, comp.className)
            iSpanX.setInt(detail, spanX)
            iSpanY.setInt(detail, 2)

            val card = dataCls.getDeclaredConstructor().also { it.isAccessible = true }.newInstance()
            dPkg.set(card, comp.packageName)
            dCls.set(card, comp.className)
            dAppName.set(card, label)
            dSpanX.setInt(card, spanX)
            dSpanY.setInt(card, 2)
            cardsField.set(card, arrayListOf(detail))
            added.add(card)
        }
        if (added.isNotEmpty()) {
            list.addAll(added)
            Log.i("injected ${added.size} widget cards")
        }
    }
}
