package com.bw.wooboxnx.hook.app

import android.appwidget.AppWidgetManager
import android.content.Context
import com.bw.wooboxnx.util.XSPUtils
import com.bw.wooboxnx.util.xposed.Log
import com.bw.wooboxnx.util.xposed.base.AppRegister
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

            // 完整列表构建完成时注入（避免 G 被分片调用导致重复注入）
            runCatching {
                val y = e.getDeclaredMethod("y", Context::class.java)
                XposedBridge.hookMethod(y, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching {
                            val ctx = param.args[0] as? Context ?: return
                            @Suppress("UNCHECKED_CAST")
                            val list = param.result as? ArrayList<Any> ?: return
                            inject(ctx, list, lpparam.classLoader)
                        }.onFailure {
                            Log.px("E", "inject (y) failed: $it", it, logInRelease = true)
                        }
                    }
                })
            }

            // 网络/本地数据组装完成后、分片之前注入
            val f = e.declaredMethods.firstOrNull {
                it.name == "F" && it.parameterTypes.size == 3 &&
                    it.parameterTypes[0] == Context::class.java &&
                    it.parameterTypes[1] == ArrayList::class.java
            }
            if (f != null) {
                XposedBridge.hookMethod(f, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        runCatching {
                            val ctx = param.args[0] as? Context ?: return
                            @Suppress("UNCHECKED_CAST")
                            val list = param.args[1] as? ArrayList<Any> ?: return
                            inject(ctx, list, lpparam.classLoader)
                        }.onFailure {
                            Log.px("E", "inject (F) failed: $it", it, logInRelease = true)
                        }
                    }
                })
            }
            Log.i("MiBoard widget unlock hooked, F=$f")
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
        val dType = dataCls.getField("type")
        val dSpanX = dataCls.getField("spanX")
        val dSpanY = dataCls.getField("spanY")
        val cardsField = dataCls.getField("cards")

        val iCls = detailCls.getField("cls")
        val iPkg = detailCls.getField("pkg")
        val iSpanX = detailCls.getField("spanX")
        val iSpanY = detailCls.getField("spanY")

        // 已存在的条目：pkg -> CardAddListData（同一应用只保留一个），同时记录已有的 pkg|cls
        val existingByPkg = HashMap<String, Any>()
        val existingKeys = HashSet<String>()
        for (c in list) {
            val pkg = runCatching { dPkg.get(c) as? String }.getOrNull() ?: continue
            if (pkg.isBlank()) continue
            val cards = runCatching { cardsField.get(c) as? MutableList<Any> }.getOrNull()
            if (cards == null) continue
            existingByPkg.putIfAbsent(pkg, c)
            for (d in cards) {
                val cls = runCatching { iCls.get(d) as? String }.getOrNull() ?: continue
                existingKeys.add("$pkg|$cls")
            }
        }

        val pm = ctx.packageManager
        val perPkgDetails = LinkedHashMap<String, MutableList<Any>>()
        val perPkgLabel = HashMap<String, String>()

        for (p in providers) {
            val comp = p.provider ?: continue
            val pkg = comp.packageName
            val key = "$pkg|${comp.className}"
            if (key in existingKeys) continue
            val spanX = if (p.minWidth > 180) 4 else 2

            val detail = detailCls.getDeclaredConstructor().also { it.isAccessible = true }.newInstance()
            iPkg.set(detail, pkg)
            iCls.set(detail, comp.className)
            iSpanX.setInt(detail, spanX)
            iSpanY.setInt(detail, 2)

            perPkgDetails.getOrPut(pkg) { ArrayList() }.add(detail)
            if (!perPkgLabel.containsKey(pkg)) {
                perPkgLabel[pkg] = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                }.getOrDefault(pkg)
            }
            existingKeys.add(key)
        }

        if (perPkgDetails.isEmpty()) return

        for ((pkg, details) in perPkgDetails) {
            val exist = existingByPkg[pkg]
            if (exist != null) {
                // 服务器已有这个应用的条目，把缺的小部件补进同一个条目
                runCatching {
                    val cards = cardsField.get(exist) as MutableList<Any>
                    cards.addAll(details)
                }
            } else {
                val first = details.first()
                val card = dataCls.getDeclaredConstructor().also { it.isAccessible = true }.newInstance()
                dPkg.set(card, pkg)
                dCls.set(card, iCls.get(first))
                dAppName.set(card, perPkgLabel[pkg])
                runCatching { dType.set(card, "list_item") }
                dSpanX.setInt(card, iSpanX.getInt(first))
                dSpanY.setInt(card, 2)
                cardsField.set(card, ArrayList(details))
                list.add(card)
            }
        }
        Log.i("injected widget cards for ${perPkgDetails.size} package(s)")
    }
}