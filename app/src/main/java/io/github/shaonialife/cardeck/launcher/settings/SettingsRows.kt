package io.github.shaonialife.cardeck.launcher.settings

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.shaonialife.cardeck.R
import io.github.shaonialife.cardeck.launcher.DesktopApps
import io.github.shaonialife.cardeck.launcher.VirtualLauncher

/** 设置列表行：分组标题 / 拼音字母标题 / 应用（管理桌面应用列表）。 */
sealed class SettingsRow {
    abstract val id: Long

    data class Header(
        val title: String,
        val key: String,
    ) : SettingsRow() {
        override val id: Long get() = key.hashCode().toLong()
    }

    data class App(
        val entry: VirtualLauncher.AppEntry,
    ) : SettingsRow() {
        override val id: Long get() = entry.component.hashCode().toLong()
    }
}

/**
 * 行构建与字母导航条（管理桌面应用面板；历史上桌面/超采样/横屏三面板共用，2026-11-18
 * 横屏并入桌面、超采样面板早已删除，现仅剩一个使用方）：
 * 两级分组——先按桌面勾选（「在桌面的应用」→「不在桌面的应用」）。
 * 仅「不在桌面」组内按拼音首字母分节（A/B/…/#，数字应用归 #），字母导航条
 * 也只对应后者——**在桌面组不分节**：其数量通常少，若分节，其字母会出现在
 * 导航条首位（排在 #/A 之前），而后面分节里不再出现该字母，导航条看起来
 * 「排序错误」（2026-10-16 用户反馈：高德在桌面 → G 排在导航条第一个）。
 */
object SettingsRows {
    fun build(
        context: Context,
        apps: List<VirtualLauncher.AppEntry>,
    ): List<SettingsRow> {
        val selected = DesktopApps.selected(context, apps)
        val onDesktop = apps.filter { it.component.flattenToString() in selected }
        val offDesktop = apps.filter { it.component.flattenToString() !in selected }
        val rows = ArrayList<SettingsRow>(apps.size * 2)
        if (onDesktop.isNotEmpty()) {
            rows += SettingsRow.Header("在桌面的应用（${onDesktop.size}）", "on")
            // 扁平直出（清单本身按拼音有序，过滤后仍有序），不分字母节、不进导航条
            rows += onDesktop.map { SettingsRow.App(it) }
        }
        if (offDesktop.isNotEmpty()) {
            rows += SettingsRow.Header("不在桌面的应用（${offDesktop.size}）", "off")
            rows += letterRows(offDesktop)
        }
        return rows
    }

    private fun letterRows(group: List<VirtualLauncher.AppEntry>): List<SettingsRow> {
        val byLetter = LinkedHashMap<String, MutableList<VirtualLauncher.AppEntry>>()
        group.forEach { entry ->
            byLetter
                .getOrPut(VirtualLauncher.indexLetterOf(entry.label)) { mutableListOf() }
                .add(entry)
        }
        val rows = ArrayList<SettingsRow>(group.size + byLetter.size)
        byLetter.forEach { (letter, list) ->
            rows += SettingsRow.Header(letter, "letter_$letter")
            rows += list.map { SettingsRow.App(it) }
        }
        return rows
    }

    /** 右侧字母导航条：行集合变化时由 AdapterDataObserver 驱动重建。 */
    fun setupIndexBar(
        fragment: androidx.fragment.app.Fragment,
        bar: LinearLayout,
        list: RecyclerView,
        rowsProvider: () -> List<SettingsRow>,
    ) {
        val density = fragment.resources.displayMetrics.density

        fun rebuild() {
            bar.removeAllViews()
            val letters = LinkedHashSet<String>()
            rowsProvider().forEach { row ->
                if (row is SettingsRow.Header && row.key.startsWith("letter_")) {
                    letters += row.key.removePrefix("letter_")
                }
            }
            letters.forEach { letter ->
                bar.addView(
                    TextView(fragment.requireContext()).apply {
                        text = letter
                        textSize = 9f
                        setTextColor(0xFF8E8E93.toInt())
                        setPadding(0, (1.5f * density).toInt(), 0, (1.5f * density).toInt())
                        setOnClickListener {
                            val target =
                                rowsProvider()
                                    .indexOfFirst { it is SettingsRow.Header && it.key == "letter_$letter" }
                            if (target >= 0) {
                                (list.layoutManager as? LinearLayoutManager)
                                    ?.scrollToPositionWithOffset(target, 0)
                            }
                        }
                    },
                )
            }
        }
        rebuild()
        list.adapter?.registerAdapterDataObserver(
            object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() = rebuild()

                override fun onItemRangeChanged(
                    positionStart: Int,
                    itemCount: Int,
                ) = rebuild()

                override fun onItemRangeInserted(
                    positionStart: Int,
                    itemCount: Int,
                ) = rebuild()

                override fun onItemRangeRemoved(
                    positionStart: Int,
                    itemCount: Int,
                ) = rebuild()

                override fun onItemRangeMoved(
                    fromPosition: Int,
                    toPosition: Int,
                    itemCount: Int,
                ) = rebuild()
            },
        )
    }

    /** 分组标题行复用 ViewHolder。 */
    class HeaderHolder(
        val title: TextView,
    ) : RecyclerView.ViewHolder(title) {
        fun bind(text: String) {
            title.text = text
        }
    }

    fun inflateHeader(parent: ViewGroup): TextView =
        android.view.LayoutInflater
            .from(parent.context)
            .inflate(R.layout.item_section_header, parent, false) as TextView
}

/**
 * 应用清单列表的通用适配器骨架（管理桌面应用面板；历史上三面板共用）：两级分组头由骨架
 * 统一渲染，应用行的布局与绑定由子类提供——替代此前多份近乎复制的适配器样板。
 */
abstract class AppRowsAdapter(
    private val fragment: androidx.fragment.app.Fragment,
    private val rowLayout: Int,
    private val appsProvider: () -> List<VirtualLauncher.AppEntry>,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    /** 应用行 Holder（分组头之外的内容行）。 */
    abstract class AppHolder(
        row: android.view.View,
    ) : RecyclerView.ViewHolder(row) {
        abstract fun bind(entry: VirtualLauncher.AppEntry)
    }

    /** 创建应用行 Holder（[rowLayout] 已 inflate 好，无需再关心 viewType）。 */
    protected abstract fun onCreateAppHolder(row: android.view.View): AppHolder

    val rows: List<SettingsRow>
        get() = SettingsRows.build(fragment.requireContext(), appsProvider())

    final override fun getItemViewType(position: Int): Int = if (rows[position] is SettingsRow.Header) 0 else 1

    final override fun getItemId(position: Int): Long = rows[position].id

    final override fun getItemCount(): Int = rows.size

    final override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RecyclerView.ViewHolder =
        if (viewType == 0) {
            SettingsRows.HeaderHolder(SettingsRows.inflateHeader(parent))
        } else {
            onCreateAppHolder(fragment.layoutInflater.inflate(rowLayout, parent, false))
        }

    final override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
    ) {
        when (val row = rows[position]) {
            is SettingsRow.Header -> (holder as SettingsRows.HeaderHolder).bind(row.title)
            is SettingsRow.App -> (holder as AppHolder).bind(row.entry)
        }
    }
}
