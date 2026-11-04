package com.bw.wooboxnx.util

import android.content.Context

/** dp 转像素（代替原来 blockmiui 提供的实现） */
fun dp2px(context: Context, dp: Float): Int =
    (dp * context.resources.displayMetrics.density + 0.5f).toInt()
