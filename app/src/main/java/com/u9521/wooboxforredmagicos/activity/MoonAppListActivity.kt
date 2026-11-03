package com.u9521.wooboxforredmagicos.activity

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.checkbox.MaterialCheckBox
import com.u9521.wooboxforredmagicos.R
import com.u9521.wooboxforredmagicos.ui.M3Color
import com.u9521.wooboxforredmagicos.ui.M3Theme
import com.u9521.wooboxforredmagicos.ui.M3Ui
import java.io.File
import java.text.Collator
import java.util.Locale

/**
 * 自定义"显示屏下内容"的应用名单（MD3 界面）。
 * 选择结果写入 files/moon_apps.txt（SystemUI 通过 ContentProvider 读取），
 * 同时继续写一份到 WooboxConfig 以兼容旧数据。
 */
class MoonAppListActivity : AppCompatActivity() {

    private data class AppEntry(
        val pkg: String,
        val label: String,
        val icon: Drawable?
    )

    private lateinit var prefs: SharedPreferences
    private lateinit var adapter: AppAdapter
    private lateinit var countText: TextView
    private var selectAllText: TextView? = null
    private val allApps = ArrayList<AppEntry>()
    private val shownApps = ArrayList<AppEntry>()
    private val selected = LinkedHashSet<String>()

    @SuppressLint("WorldReadableFiles")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        M3Theme.apply(this)
        prefs = try {
            @Suppress("DEPRECATION")
            getSharedPreferences("WooboxConfig", MODE_WORLD_READABLE)
        } catch (e: SecurityException) {
            getSharedPreferences("WooboxConfig", MODE_PRIVATE)
        }
        selected += loadFromSettings().orEmpty()
            .split(";")
            .filter { it.isNotBlank() }
        setContentView(buildUi())
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        persist()
    }

    private fun dp(value: Int): Int = M3Ui.dp(this, value)

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(M3Color.SURFACE)
        }

        // 顶栏
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(16), dp(16), dp(8))
        }
        titleBar.addView(TextView(this).apply {
            text = "‹"
            setTextColor(M3Color.ON_SURFACE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            setPadding(dp(12), 0, dp(12), dp(2))
            setOnClickListener { finish() }
        })
        titleBar.addView(M3Ui.text(this, getString(R.string.moon_apps_title), 22f,
            M3Color.ON_SURFACE, true))
        titleBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        selectAllText = TextView(this).apply {
            text = getString(R.string.moon_apps_select_all)
            setTextColor(M3Color.PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), M3Color.OUTLINE_VAR)
            }
            isClickable = true
            setOnClickListener { toggleSelectAll() }
        }
        titleBar.addView(selectAllText)
        root.addView(titleBar)

        // 搜索
        val searchWrap = LinearLayout(this).apply {
            setPadding(dp(16), dp(4), dp(16), dp(8))
        }
        val search = EditText(this).apply {
            hint = getString(R.string.moon_apps_search)
            setHintTextColor(M3Color.ON_SURFACE_VAR)
            setTextColor(M3Color.ON_SURFACE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setSingleLine()
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(M3Color.SURFACE_CONT)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = filter(s?.toString().orEmpty())
            })
        }
        searchWrap.addView(search, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(searchWrap)

        // 列表
        val listView = ListView(this).apply {
            dividerHeight = 0
            setBackgroundColor(Color.TRANSPARENT)
            isFastScrollEnabled = false
            setPadding(dp(8), 0, dp(8), 0)
            clipToPadding = false
        }
        adapter = AppAdapter()
        listView.adapter = adapter
        root.addView(listView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // 底部
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(8), dp(12), dp(12))
        }
        countText = M3Ui.text(this, "", 13f, M3Color.ON_SURFACE_VAR)
        bottom.addView(countText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(com.google.android.material.button.MaterialButton(this).apply {
            text = getString(R.string.moon_apps_done)
            backgroundTintList = ColorStateList.valueOf(M3Color.PRIMARY_CONT)
            setTextColor(M3Color.ON_PRIMARY_CONT)
            cornerRadius = dp(22)
            setOnClickListener { finish() }
        })
        root.addView(bottom)

        updateCount()
        return root
    }

    private fun loadApps() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("QueryPermissionsNeeded")
        val infos = pm.queryIntentActivities(intent, 0)
            .mapNotNull { it.activityInfo?.applicationInfo }
            .distinctBy { it.packageName }
        val collator = Collator.getInstance(Locale.CHINA)
        infos.map { info ->
            AppEntry(
                pkg = info.packageName,
                label = info.loadLabel(pm).toString(),
                icon = runCatching { info.loadIcon(pm) }.getOrNull()
            )
        }.sortedWith(Comparator { a, b -> collator.compare(a.label, b.label) })
            .forEach { allApps.add(it) }
        filter("")
    }

    private fun filter(keyword: String) {
        val key = keyword.trim()
        shownApps.clear()
        if (key.isEmpty()) {
            shownApps.addAll(allApps)
        } else {
            allApps.filter {
                it.label.contains(key, true) || it.pkg.contains(key, true)
            }.forEach { shownApps.add(it) }
        }
        adapter.notifyDataSetChanged()
        updateSelectAllLabel()
    }

    private fun toggle(pkg: String, checked: Boolean) {
        if (checked) selected += pkg else selected -= pkg
        persist()
        updateCount()
        updateSelectAllLabel()
    }

    /** 全选 / 全不选（只作用于当前搜索过滤后的列表） */
    private fun toggleSelectAll() {
        if (shownApps.isEmpty()) return
        val allSelected = shownApps.all { it.pkg in selected }
        if (allSelected) {
            shownApps.forEach { selected -= it.pkg }
        } else {
            shownApps.forEach { selected += it.pkg }
        }
        persist()
        updateCount()
        adapter.notifyDataSetChanged()
        updateSelectAllLabel()
    }

    private fun updateSelectAllLabel() {
        val label = selectAllText ?: return
        val allSelected = shownApps.isNotEmpty() && shownApps.all { it.pkg in selected }
        label.text = getString(
            if (allSelected) R.string.moon_apps_clear_all else R.string.moon_apps_select_all
        )
        label.setTextColor(M3Color.PRIMARY)
    }

    private fun persist() {
        val value = selected.joinToString(";")
        // 主存储：应用私有文件，SystemUI 通过 ContentProvider 读取
        runCatching { File(filesDir, MoonStorage.FILE_NAME).writeText(value) }
        prefs.edit().putString(KEY_MOON_APPS, value).apply()
        // 不依赖 canWrite 的判断，直接尝试写；没授权时会抛 SecurityException
        runCatching { Settings.System.putString(contentResolver, SYS_KEY, value) }
    }

    private fun loadFromSettings(): String? {
        val file = File(filesDir, MoonStorage.FILE_NAME)
        if (file.exists()) {
            val value = runCatching { file.readText() }.getOrNull()
            if (!value.isNullOrBlank()) return value
        }
        val sys = runCatching { Settings.System.getString(contentResolver, SYS_KEY) }.getOrNull()
        if (!sys.isNullOrBlank()) return sys
        return prefs.getString(KEY_MOON_APPS, "")
    }

    private fun updateCount() {
        if (this::countText.isInitialized) {
            countText.text = getString(R.string.moon_apps_count, selected.size)
        }
    }

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount(): Int = shownApps.size
        override fun getItem(position: Int): Any = shownApps[position]
        override fun getItemId(position: Int): Long = shownApps[position].pkg.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val entry = shownApps[position]
            val row = LinearLayout(this@MoonAppListActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(9), dp(8), dp(9))
                isClickable = true
                val tv = TypedValue()
                if (theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
                    setBackgroundResource(tv.resourceId)
                }
            }

            val icon = ImageView(this@MoonAppListActivity).apply {
                setImageDrawable(entry.icon)
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(38), dp(38)).apply {
                rightMargin = dp(14)
            })

            val texts = LinearLayout(this@MoonAppListActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            texts.addView(TextView(this@MoonAppListActivity).apply {
                text = entry.label
                setTextColor(M3Color.ON_SURFACE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.END
            })
            texts.addView(TextView(this@MoonAppListActivity).apply {
                text = entry.pkg
                setTextColor(M3Color.ON_SURFACE_VAR)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.END
            })
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val check = MaterialCheckBox(this@MoonAppListActivity).apply {
                isChecked = entry.pkg in selected
                isFocusable = false
                isClickable = false
                buttonTintList = ColorStateList.valueOf(M3Color.PRIMARY)
                // 选框自己吞掉触摸，这里把它转发给整行，点框和点行效果一致
                setOnTouchListener { v, e ->
                    if (e.action == MotionEvent.ACTION_UP) row.performClick()
                    true
                }
            }
            row.addView(check, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))

            row.setOnClickListener {
                val now = entry.pkg !in selected
                check.isChecked = now
                toggle(entry.pkg, now)
            }
            return row
        }
    }

    companion object {
        const val KEY_MOON_APPS = "moon_apps"
        const val SYS_KEY = "woobox_moon_apps"
    }
}
