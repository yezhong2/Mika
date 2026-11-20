package com.wangzan101.mika.cap.phone.setup

import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.cap.phone.a11y.SystemPickers
import kotlinx.coroutines.delay

/**
 * 用 Mika 自己的无障碍服务读/开系统设置里的「无线调试」(2026-11-20，ccode)。
 *
 * 为什么要有这一条:那个开关是 `Settings.Global.adb_wifi_enabled`,写它要 `WRITE_SECURE_SETTINGS`——
 * 开发权限,任何设置界面都授不了,只能 adb/root;而 Mika 自己补授权只能在**连上之后**
 * ([com.wangzan101.mika.core.shell.RecoveryPermissions.applyPending])。于是「没授权→不连」「不连→没授权」
 * 互为前提;更糟的是**那个设置值会骗人**:Honor 这种机器上它一直是 1,而 adbd 其实没在监听
 * (主人现场:配对窗口里显示已连接、一退出就断)。所以这里不看设置值,看系统页上真实的「IP 地址和端口」。
 *
 * 三个能力,都只在 Mika 前台时动屏幕(不抢主人的屏),页面上没有「无线调试」四个字一律不点:
 *  - [readEndpoint]:读页上的「IP 地址和端口」,给连接用(比盲扫端口准,也不受网段限制);
 *  - [restart]:把总开关关掉再打开一次,让 adbd 重新起来(读不到端口说明它没起);
 *  - [turnOn]:开关确实关着时把它打开。
 *
 * 失败表现:每一步都等到超时就返回 null/false,并退出设置页,调用方按原来的办法提示用户。
 */
internal object WirelessDebuggingScreenOn {
    private const val TAG = "MikaWirelessOn"
    /** 拉起设置页后等窗口/等点击生效的总时长。 */
    private const val WAIT_MS = 12_000L
    /** 开关拨动后等 adbd 重新起来、页面上出现端口。 */
    private const val RESTART_MS = 15_000L
    private const val POLL_MS = 250L
    private const val MAX_CLICKS = 3
    private const val MAX_NODES = 600
    private val ALLOW_TEXTS = setOf("允许", "Allow")
    private const val PAGE_WORD = "无线调试"

    /** 页面上「IP 地址和端口」那一行的值。读不到(daemon 没起、页面没开)给 null。 */
    suspend fun readEndpoint(app: Context): Pair<String, Int>? {
        val service = ScreenAccessibility.instance.value ?: return null
        val settings = SystemPickers.settingsPackage(app.packageManager) ?: return null
        if (!mikaInFront(service, app.packageName)) {
            Log.i(TAG, "Mika is not in front; not reading the page")
            return null
        }
        if (!open(app)) return null
        try {
            val until = System.currentTimeMillis() + WAIT_MS
            while (System.currentTimeMillis() < until) {
                delay(POLL_MS)
                for (root in settingsRoots(service, settings)) {
                    endpointIn(root)?.let {
                        Log.i(TAG, "read endpoint from the page")
                        return it
                    }
                }
            }
            return null
        } finally {
            leave(service, settings)
        }
    }

    /**
     * 关掉再打开无线调试:设置写着开着、页面上却没有端口(daemon 没在监听)时用它把服务重启一遍。
     * 只在页面上确认到「无线调试」这几个字之后才拨开关。返回值只表示「端口重新出现了」,不代表连上。
     */
    suspend fun restart(app: Context, isOn: () -> Boolean?): Boolean {
        val service = ScreenAccessibility.instance.value ?: return false
        val settings = SystemPickers.settingsPackage(app.packageManager) ?: return false
        if (!mikaInFront(service, app.packageName)) return false
        if (!open(app)) return false
        try {
            if (!clickSwitch(service, settings, wantChecked = false)) return false
            delay(1_500)
            if (!clickSwitch(service, settings, wantChecked = true)) return false
            // 打开后系统会问「要允许在此网络上进行无线调试吗」:点允许,否则它自己又关回去
            val until = System.currentTimeMillis() + RESTART_MS
            while (System.currentTimeMillis() < until) {
                delay(POLL_MS)
                clickAllow(settingsRoots(service, settings))
                if (isOn() != false && settingsRoots(service, settings).any { endpointIn(it) != null }) {
                    Log.i(TAG, "wireless debugging restarted from the screen")
                    return true
                }
            }
            Log.i(TAG, "wireless debugging did not come back")
            return false
        } finally {
            leave(service, settings)
        }
    }

    /** [isOn] 读系统那个开关(读不到给 null)。返回 true = 确认已经打开。 */
    suspend fun turnOn(app: Context, isOn: () -> Boolean?): Boolean {
        if (isOn() == true) return true
        val service = ScreenAccessibility.instance.value ?: return false
        val settings = SystemPickers.settingsPackage(app.packageManager) ?: return false
        if (!mikaInFront(service, app.packageName)) {
            Log.i(TAG, "Mika is not in front; leaving the screen alone")
            return false
        }
        if (!open(app)) return false
        try {
            var clicks = 0
            var allowClicks = 0
            val until = System.currentTimeMillis() + WAIT_MS
            while (System.currentTimeMillis() < until) {
                delay(POLL_MS)
                if (isOn() == true) return true
                val roots = settingsRoots(service, settings)
                if (roots.isEmpty()) continue
                if (allowClicks < MAX_CLICKS && clickAllow(roots)) {
                    allowClicks++
                    continue
                }
                if (clicks >= MAX_CLICKS) break
                val sw = roots.firstNotNullOfOrNull(::findSwitch) ?: continue
                if (sw.isChecked) continue
                if (click(sw)) clicks++
            }
            Log.i(TAG, "wireless debugging did not turn on from the screen")
            return false
        } finally {
            leave(service, settings)
        }
    }

    /** Mika 自己有没有可见窗口:有才动屏幕。读不到窗口就当不在前台(保守)。 */
    private fun mikaInFront(service: ScreenAccessibility, self: String): Boolean =
        runCatching {
            service.windowsOn(Display.DEFAULT_DISPLAY).any { w -> w.root?.packageName?.toString() == self }
        }.getOrDefault(false)

    /** 拉起「无线调试」页:优先带高亮的那几个 intent,哪个能起就用哪个。 */
    private fun open(app: Context): Boolean {
        for (base in DeveloperSettings.wirelessDebuggingPage()) {
            val intent = Intent(base).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { app.startActivity(intent) }.isSuccess) return true
        }
        return false
    }

    private fun settingsRoots(service: ScreenAccessibility, settings: String): List<AccessibilityNodeInfo> =
        service.windowsOn(Display.DEFAULT_DISPLAY).mapNotNull { w ->
            val root = runCatching { w.root }.getOrNull() ?: return@mapNotNull null
            if (root.packageName?.toString() != settings) null else root
        }

    private fun nodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty() && out.size < MAX_NODES) {
            val n = queue.removeFirst()
            out += n
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
        }
        return out
    }

    /** 「IP 地址和端口」那一行:`192.168.43.197:41235`。必须先确认页面上有「无线调试」四个字。 */
    private fun endpointIn(root: AccessibilityNodeInfo): Pair<String, Int>? {
        val all = nodes(root)
        if (all.none { it.text?.toString()?.contains(PAGE_WORD) == true }) return null
        for (n in all) {
            val text = n.text?.toString() ?: continue
            val m = ENDPOINT.find(text) ?: continue
            val port = m.groupValues[2].toIntOrNull() ?: continue
            if (port in 1..65535) return m.groupValues[1] to port
        }
        return null
    }

    /** 这一页的总开关:先确认页面上有「无线调试」这四个字,再取第一个可勾选的控件。 */
    private fun findSwitch(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val all = nodes(root)
        if (all.none { it.text?.toString()?.contains(PAGE_WORD) == true }) return null
        return all.firstOrNull { n -> n.className?.toString()?.contains("Switch") == true || n.isCheckable }
    }

    /** 把总开关拨到 [wantChecked];已经是这个状态就当成功。 */
    private suspend fun clickSwitch(service: ScreenAccessibility, settings: String, wantChecked: Boolean): Boolean {
        val until = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < until) {
            delay(POLL_MS)
            val sw = settingsRoots(service, settings).firstNotNullOfOrNull(::findSwitch)
            if (sw == null) {
                clickAllow(settingsRoots(service, settings))
                continue
            }
            if (sw.isChecked == wantChecked) return true
            if (click(sw)) {
                // 拨动后等它自己稳定下来,再让调用方的下一次读取判断真假
                delay(1_200)
                return true
            }
        }
        return false
    }

    private fun clickAllow(roots: List<AccessibilityNodeInfo>): Boolean {
        for (root in roots) {
            val n = nodes(root).firstOrNull { it.text?.toString()?.trim() in ALLOW_TEXTS } ?: continue
            if (click(n)) return true
        }
        return false
    }

    /** 自身可点就点,不可点就往上找可点的祖先(开关常被包在一行里)。 */
    private fun click(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth++ < 5) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = runCatching { n.parent }.getOrNull()
        }
        return runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
    }

    /** 退出设置页,把屏幕还给主人。已经不在设置页就不按(多按会把用户别的页面退掉)。 */
    private fun leave(service: ScreenAccessibility, settings: String) {
        repeat(2) {
            if (settingsRoots(service, settings).isEmpty()) return
            runCatching { service.performGlobalBack() }
        }
    }

    private val ENDPOINT = Regex("(\\d{1,3}(?:\\.\\d{1,3}){3}):(\\d{1,5})")
}
