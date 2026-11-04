package com.u9521.wooboxforredmagicos.activity

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import java.io.File

/**
 * 给 SystemUI 里的 MoonNotch 读取"自定义应用名单"和"显示内容样式"用。
 * 数据写在应用私有目录的 files/ 下，不依赖任何系统权限。
 */
class MoonProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val dir = requireNotNull(context).filesDir
        return if (uri.lastPathSegment == "style") {
            val file = File(dir, MoonStorage.FILE_STYLE)
            val value = runCatching {
                if (file.exists()) file.readText().trim().toIntOrNull() ?: MoonStyle.MOON else MoonStyle.MOON
            }.getOrDefault(MoonStyle.MOON)
            MatrixCursor(arrayOf(MoonStorage.COLUMN)).apply { addRow(arrayOf<Any>(value)) }
        } else {
            val file = File(dir, MoonStorage.FILE_NAME)
            val value = runCatching { if (file.exists()) file.readText() else "" }.getOrDefault("")
            MatrixCursor(arrayOf(MoonStorage.COLUMN)).apply { addRow(arrayOf<Any>(value)) }
        }
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.woobox.moon"

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val dir = context?.filesDir ?: return null
        if (uri.lastPathSegment == "heartbeat") {
            val v = values?.getAsString("v").orEmpty()
            val pkg = values?.getAsString("pkg")
            runCatching { File(dir, MoonStorage.FILE_ALIVE).writeText(v) }
            if (!pkg.isNullOrBlank()) {
                runCatching {
                    File(dir, "beat_" + pkg.replace('.', '_') + ".txt").writeText(v)
                }
            }
            return uri
        }
        return null
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}

object MoonStorage {
    const val FILE_NAME = "moon_apps.txt"
    const val FILE_STYLE = "moon_style.txt"
    const val FILE_ALIVE = "hook_alive.txt"
    const val COLUMN = "value"
    const val AUTHORITY = "com.u9521.wooboxforredmagicos.mod.moon"
    const val URI = "content://$AUTHORITY/moon"
    const val URI_STYLE = "content://$AUTHORITY/style"
    const val URI_HEARTBEAT = "content://$AUTHORITY/heartbeat"
}

/** 摄像头位置圆里显示的内容样式 */
object MoonStyle {
    const val MOON = 0        // 弯月 + 星星
    const val COLOR_ICON = 1  // 应用图标（彩色）
    const val MONO_ICON = 2   // 应用图标（单色剪影)
    const val PLAIN_BLACK = 3 // 纯黑遮罩

    fun get(context: android.content.Context): Int = runCatching {
        val file = File(context.filesDir, MoonStorage.FILE_STYLE)
        if (file.exists()) file.readText().trim().toIntOrNull() ?: MOON else MOON
    }.getOrDefault(MOON)

    fun set(context: android.content.Context, style: Int) {
        runCatching {
            File(context.filesDir, MoonStorage.FILE_STYLE).writeText(style.toString())
        }
    }
}
