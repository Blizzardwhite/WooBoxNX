package com.u9521.wooboxforredmagicos.activity

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import java.io.File

/**
 * 给 SystemUI 里的 MoonNotch 读取"自定义应用名单"用。
 * 名单写在应用私有目录的 files/moon_apps.txt，不依赖任何系统权限。
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
        val file = File(requireNotNull(context).filesDir, MoonStorage.FILE_NAME)
        val value = runCatching { if (file.exists()) file.readText() else "" }.getOrDefault("")
        return MatrixCursor(arrayOf(MoonStorage.COLUMN)).apply {
            addRow(arrayOf<Any>(value))
        }
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.woobox.moon"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

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
    const val COLUMN = "value"
    const val AUTHORITY = "com.u9521.wooboxforredmagicos.mod.moon"
    const val URI = "content://$AUTHORITY/moon"
}
