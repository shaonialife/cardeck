package io.github.shaonialife.cardeck.launcher.settings

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.shaonialife.cardeck.R
import io.github.shaonialife.cardeck.launcher.VirtualLauncher
import io.github.shaonialife.cardeck.util.Bg

/** 设置面板宿主：页内轻提示（车机=页内 Toast 视图；手机=系统 Toast）。 */
interface SettingsToastHost {
    fun showToast(text: String)
}

/**
 * 设置内容面板基类：共享应用清单（缓存直读 + 冷路径异步扫描）与宿主轻提示。
 * 应用清单进程级共享（VirtualLauncher 缓存），桌面扫描后各面板秒开。
 */
abstract class SettingsPanelFragment : Fragment() {
    var apps: List<VirtualLauncher.AppEntry> = emptyList()
        private set

    /** 清单就绪回调（主线程；缓存命中时同步回调）。 */
    protected fun loadApps(onReady: () -> Unit) {
        val appContext = requireContext().applicationContext
        VirtualLauncher.peekCachedApps()?.takeIf { it.isNotEmpty() }?.let {
            apps = it
            onReady()
            return
        }
        Bg.run("apps-scan") {
            // cachedApps（带扫描锁）而非 launchableApps（裸扫）：连接起步的预热扫描
            // 可能正在进行——等它合并结果，绝不并发再扫一遍（2026-11-09）
            val scanned = VirtualLauncher.cachedApps(appContext)
            VirtualLauncher.adoptAppsCache(scanned)
            requireActivity().runOnUiThread {
                if (!isAdded || isDetached) return@runOnUiThread
                apps = scanned
                onReady()
            }
        }
    }

    /** 应用清单列表的通用装配：LayoutManager + 字母导航条 + 异步装载（含 loading 占位）。 */
    protected fun setupAppList(
        container: View,
        listId: Int,
        indexBarId: Int,
        adapter: AppRowsAdapter,
        onAppsReady: () -> Unit = {},
    ) {
        val list = container.findViewById<RecyclerView>(listId)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        SettingsRows.setupIndexBar(this, container.findViewById<LinearLayout>(indexBarId), list) { adapter.rows }
        val loading = container.findViewById<TextView>(R.id.listLoading)
        loadApps {
            loading.visibility = View.GONE
            onAppsReady()
            adapter.notifyDataSetChanged()
        }
    }

    protected fun hostToast(text: String) {
        val host = activity
        if (host is SettingsToastHost) {
            host.showToast(text)
        } else {
            android.widget.Toast
                .makeText(requireContext(), text, android.widget.Toast.LENGTH_SHORT)
                .show()
        }
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        onViewCreated(view)
    }

    /** onViewCreated 简化版（本页无状态恢复需求）。 */
    protected abstract fun onViewCreated(view: View)
}
