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
 * 用 Mika 自己的无障碍服务把系统设置里的「无线调试」开关点开（2026-11-20，ccode）。
 *
 * 为什么要有这一条：那个开关是 `Settings.Global.adb_wifi_enabled`，写它要 `WRITE_SECURE_SETTINGS`——
 * 开发权限，任何设置界面都授不了，只能 adb/root；而 Mika 自己补授权只能在**连上之后**
 * （[com.wangzan101.mika.core.shell.RecoveryPermissions.applyPending]）。于是「没授权→不连」「不连→没授权」
 * 互为前提，无线调试一被系统关掉，App 就再也连不回来，表现是「每次回 App 都断开」。
 * 无障碍这条路不吃任何授权；配对那一步已经证明 Mika 能读懂系统设置界面。
 *
 * 只做三件事：拉起「无线调试」页 → 点开这一页的总开关（系统弹「要允许在此网络上进行无线调试吗」就点允许）→ 按返回退出。
 * 三条边界（都是故意的）：
 *  - Mika 不在前台就不动手——不抢主人的屏（他可能在导航、在别的 App 里）；
 *  - 页面上找不到「无线调试」这几个字就不点——宁可不做，也不乱点别处（开发者选项里紧挨着 USB 调试）；
 *  - 读到的开关已经是开着就直接返回 true，不碰屏幕。
 *
 * 失败表现：15 秒内没点开就返回 false 并退出设置页，调用方按原来的办法（提示用户手动打开）。
 */
internal object WirelessDebuggingScreenOn {
    private const val TAG = "MikaWirelessOn"
    /** 拉起设置页后等窗口出现、等点击生效的总时长。 */
    private const val WAIT_MS = 15_000L
    /** 点完开关后再确认多久（系统把值写回是异步的，还可能要等用户点「允许」）。 */
    private const val CONFIRM_MS = 8_000L
    private const val POLL_MS = 250L
    /** 同一次里最多点几次开关（被弹框挡住、点了没生效之类的重试）。 */
    private const val MAX_CLICKS = 3
    /** 遍历节点上限：设置页一屏几百个节点，给足余量但不无限跑。 */
    private const val MAX_NODES = 600
    private val ALLOW_TEXTS = setOf("允许", "Allow")
    private const val PAGE_WORD = "无线调试"

    /** [isOn] 读系统那个开关（读不到给 null）。返回 true = 确认已经打开。 */
    suspend fun turnOn(app: Context, isOn: () -> Boolean?): Boolean {
        if (isOn() == true) return true
        val service = ScreenAccessibility.instance.value ?: return false
        val settings = SystemPickers.settingsPackage(app.packageManager) ?: return false
        if (!mikaInFront(service, app.packageName)) {
            Log.i(TAG, "Mika is not in front; leaving the screen alone")
            return false
        }
        if (!open(app)) {
            Log.i(TAG, "no settings page accepted the wireless debugging intent")
            return false
        }
        try {
            var clicks = 0
            var allowClicks = 0
            val until = System.currentTimeMillis() + WAIT_MS
            while (System.currentTimeMillis() < until) {
                delay(POLL_MS)
                if (isOn() == true) return true
                val roots = settingsRoots(service, settings)
                if (roots.isEmpty()) continue
                // 系统问「要允许在此网络上进行无线调试吗」：先点「允许」，开关才会保持打开
                if (allowClicks < MAX_CLICKS && clickAllow(roots)) {
                    allowClicks++
                    continue
                }
                if (clicks >= MAX_CLICKS) break
                val sw = roots.firstNotNullOfOrNull(::findSwitch) ?: continue
                if (sw.isChecked) continue
                if (click(sw)) clicks++
            }
            val confirmUntil = System.currentTimeMillis() + CONFIRM_MS
            while (System.currentTimeMillis() < confirmUntil) {
                delay(POLL_MS)
                if (isOn() == true) return true
                clickAllow(settingsRoots(service, settings))
            }
            Log.i(TAG, "wireless debugging did not turn on from the screen")
            return false
        } finally {
            leave(service, settings)
        }
    }

    /** Mika 自己有没有可见窗口：有才动屏幕。读不到窗口就当不在前台（保守）。 */
    private fun mikaInFront(service: ScreenAccessibility, self: String): Boolean =
        runCatching {
            service.windowsOn(Display.DEFAULT_DISPLAY).any { w ->
                w.root?.packageName?.toString() == self
            }
        }.getOrDefault(false)

    /** 拉起「无线调试」页：优先带高亮的那几个 intent，哪个能起就用哪个。 */
    private fun open(app: Context): Boolean {
        for (base in DeveloperSettings.wirelessDebuggingPage()) {
            val intent = Intent(base).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { app.startActivity(intent) }.isSuccess) return true
        }
        return false
    }

    /** 系统设置当前每个窗口的根节点。 */
    private fun settingsRoots(service: ScreenAccessibility, settings: String): List<AccessibilityNodeInfo> =
        service.windowsOn(Display.DEFAULT_DISPLAY).mapNotNull { w ->
            val root = runCatching { w.root }.getOrNull() ?: return@mapNotNull null
            if (root.packageName?.toString() != settings) null else root
        }

    /**
     * 这一页的总开关：先确认页面上有「无线调试」这几个字，再取第一个可勾选的控件。
     * 找不到字就返回 null——不认识的页面一律不点。
     */
    private fun findSwitch(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque(listOf(root))
        var hasTitle = false
        while (queue.isNotEmpty() && nodes.size < MAX_NODES) {
            val n = queue.removeFirst()
            nodes += n
            if (n.text?.toString()?.contains(PAGE_WORD) == true) hasTitle = true
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
        }
        if (!hasTitle) return null
        return nodes.firstOrNull { n ->
            n.className?.toString()?.contains("Switch") == true || n.isCheckable
        }
    }

    /** 点「允许」。只在设置包里找，且必须是 ALLOW_TEXTS 里的文字。 */
    private fun clickAllow(roots: List<AccessibilityNodeInfo>): Boolean {
        for (root in roots) {
            val n = findText(root, ALLOW_TEXTS) ?: continue
            if (click(n)) return true
        }
        return false
    }

    private fun findText(root: AccessibilityNodeInfo, texts: Set<String>): AccessibilityNodeInfo? {
        val queue = ArrayDeque(listOf(root))
        var seen = 0
        while (queue.isNotEmpty() && seen++ < MAX_NODES) {
            val n = queue.removeFirst()
            if (n.text?.toString()?.trim() in texts) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
        }
        return null
    }

    /** 自身可点就点，不可点就往上找可点的祖先（开关常被包在一行里）。 */
    private fun click(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth++ < 5) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = runCatching { n.parent }.getOrNull()
        }
        return runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
    }

    /** 退出设置页，把屏幕还给主人。已经不在设置页就不按（多按会把用户别的页面退掉）。 */
    private fun leave(service: ScreenAccessibility, settings: String) {
        repeat(2) {
            val still = settingsRoots(service, settings).isNotEmpty()
            if (!still) return
            runCatching { service.performGlobalBack() }
        }
    }
}
