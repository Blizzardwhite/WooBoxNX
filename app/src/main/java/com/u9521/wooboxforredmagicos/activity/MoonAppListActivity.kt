package com.u9521.wooboxforredmagicos.activity

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import java.io.File
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.text.Collator
import java.util.Locale

/**
 * 自定义"显示月亮"的应用名单。
 * 选择结果写入 WooboxConfig 的 moon_apps（分号分隔的包名），
 * SystemUI 里的 MoonNotch 直接读取这个值。
 */
class MoonAppListActivity : Activity() {

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

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics)
            .toInt()

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F0F0F"))
        }

        // 标题栏
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(14), dp(16), dp(6))
        }
        titleBar.addView(TextView(this).apply {
            text = "‹"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            setPadding(dp(8), 0, dp(12), dp(4))
            setOnClickListener { finish() }
        })
        titleBar.addView(TextView(this).apply {
            text = getString(com.u9521.wooboxforredmagicos.R.string.moon_apps_title)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        selectAllText = TextView(this).apply {
            text = getString(com.u9521.wooboxforredmagicos.R.string.moon_apps_select_all)
            setTextColor(Color.parseColor("#4FA3FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(14), dp(10), dp(6), dp(10))
            setOnClickListener { toggleSelectAll() }
        }
        titleBar.addView(selectAllText)
        root.addView(titleBar)

        // 搜索框
        root.addView(EditText(this).apply {
            hint = getString(com.u9521.wooboxforredmagicos.R.string.moon_apps_search)
            setHintTextColor(Color.parseColor("#888888"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setSingleLine()
            setBackgroundColor(Color.parseColor("#1C1C1C"))
            setPadding(dp(16), dp(10), dp(16), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = filter(s?.toString().orEmpty())
            })
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(16), dp(4), dp(16), dp(8)) })

        // 列表
        val listView = ListView(this).apply {
            dividerHeight = 0
            setBackgroundColor(Color.TRANSPARENT)
            isFastScrollEnabled = true
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
            setPadding(dp(20), dp(10), dp(16), dp(16))
        }
        countText = TextView(this).apply {
            setTextColor(Color.parseColor("#AAAAAA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        bottom.addView(countText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(Button(this).apply {
            text = getString(com.u9521.wooboxforredmagicos.R.string.moon_apps_done)
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
            if (allSelected) com.u9521.wooboxforredmagicos.R.string.moon_apps_clear_all
            else com.u9521.wooboxforredmagicos.R.string.moon_apps_select_all
        )
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
            countText.text = getString(
                com.u9521.wooboxforredmagicos.R.string.moon_apps_count,
                selected.size
            )
        }
    }

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount(): Int = shownApps.size
        override fun getItem(position: Int): Any = shownApps[position]
        override fun getItemId(position: Int): Long = shownApps[position].pkg.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val entry = shownApps[position]
            val context: Context = this@MoonAppListActivity

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(9), dp(16), dp(9))
                isClickable = true
            }

            val icon = ImageView(context).apply {
                setImageDrawable(entry.icon)
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                rightMargin = dp(14)
            })

            val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(context).apply {
                text = entry.label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
                setSingleLine()
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            texts.addView(TextView(context).apply {
                text = entry.pkg
                setTextColor(Color.parseColor("#7A7A7A"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setSingleLine()
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val check = CheckBox(context).apply {
                isChecked = entry.pkg in selected
                isClickable = false
                isFocusable = false
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
