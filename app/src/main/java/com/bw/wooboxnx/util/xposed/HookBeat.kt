package com.bw.wooboxnx.util.xposed

import android.app.AndroidAppHelper
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import de.robv.android.xposed.XposedHelpers

/**
 * 让每个作用域进程给模块写一个心跳（开机时的时间戳），
 * 设置界面据此判断"这个作用域到底有没有被注入"。
 *
 * system_server 的心跳要走后台线程：它自己的主线程正在处理 Provider 启动，
 * 同步调用会互相等待。普通应用用主线程 Handler 重试最稳。
 */
object HookBeat {
    private const val MODULE_PKG = "com.bw.wooboxnx"
    private const val URI_HEARTBEAT = "content://com.bw.wooboxnx.moon/heartbeat"

    @Volatile
    private var cachedApp: Context? = null

    @Volatile
    private var sent = false

    /** 依次尝试：Xposed 的应用实例 → ActivityThread 里的 Application/SystemContext */
    @Suppress("PrivateApi")
    private fun context(): Context? {
        cachedApp?.let { return it }
        runCatching {
            AndroidAppHelper.currentApplication()?.let {
                cachedApp = it
                return it
            }
        }
        runCatching {
            val at = Class.forName("android.app.ActivityThread")
            val current = XposedHelpers.callStaticMethod(at, "currentActivityThread") ?: return@runCatching
            (XposedHelpers.getObjectField(current, "mInitialApplication") as? Context)?.let {
                cachedApp = it
                return it
            }
            (XposedHelpers.getObjectField(current, "mSystemContext") as? Context)?.let {
                cachedApp = it
                return it
            }
            (XposedHelpers.callMethod(current, "getSystemContext") as? Context)?.let {
                cachedApp = it
                return it
            }
        }
        return null
    }

    /** 模块安装时间，用来判断某个进程里跑的到底是不是最新代码 */
    private fun codeVersion(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(MODULE_PKG, 0).lastUpdateTime.toString()
    }.getOrDefault("")

    private fun beatNow(pkg: String): Boolean {
        val ctx = context() ?: return false
        val now = SystemClock.elapsedRealtime()
        val value = now.toString() + "|" + System.currentTimeMillis() + "|" + codeVersion(ctx)
        return runCatching {
            ctx.contentResolver.insert(
                Uri.parse(URI_HEARTBEAT),
                ContentValues().apply {
                    put("pkg", pkg)
                    put("v", value)
                }
            ) != null
        }.getOrDefault(false)
    }

    /** 调试用：把一行日志写到模块 files/hook_debug.log，方便在真机上排查 hook */
    fun debug(line: String) {
        Thread({
            // 进程刚起来时可能还拿不到 Context，重试几次
            repeat(8) { i ->
                val ctx = context()
                if (ctx != null) {
                    val ok = runCatching {
                        ctx.contentResolver.insert(
                            Uri.parse("content://com.bw.wooboxnx.moon/log"),
                            ContentValues().apply { put("v", line) }
                        ) != null
                    }.getOrDefault(false)
                    if (ok) return@Thread
                }
                runCatching { Thread.sleep(if (i < 4) 1000L else 3000L) }
            }
        }, "woobox-log").apply { isDaemon = true }.start()
    }

    fun report(pkg: String) {
        if (sent) return
        sent = true
        if (pkg == "android") {
            // system_server：后台线程重试，别占住主线程
            Thread({
                var delay = 3_000L
                repeat(40) { i ->
                    if (beatNow(pkg)) return@Thread
                    if (i >= 6) delay = 15_000L
                    runCatching { Thread.sleep(delay) }
                }
                Log.px("E", "heartbeat failed for $pkg", logInRelease = true)
            }, "woobox-beat").apply { isDaemon = true }.start()
        } else {
            Handler(Looper.getMainLooper()).post { retryOnMain(pkg, 0) }
        }
    }

    private fun retryOnMain(pkg: String, attempt: Int) {
        if (beatNow(pkg)) return
        if (attempt >= 14) {
            Log.px("E", "heartbeat failed for $pkg", logInRelease = true)
            return
        }
        Handler(Looper.getMainLooper()).postDelayed({
            retryOnMain(pkg, attempt + 1)
        }, if (attempt < 5) 5_000L else 15_000L)
    }
}
