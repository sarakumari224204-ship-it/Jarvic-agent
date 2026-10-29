package com.jarvis.os.accessibility

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import com.jarvis.os.domain.agent.AppResolver
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class JarvisAccessibilityService : AccessibilityService() {

    @Volatile
    private var nodeRefs: Map<Int, AccessibilityNodeInfo> = emptyMap()

    @Volatile
    private var lastEventUptime: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        _instance.value = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        lastEventUptime = SystemClock.uptimeMillis()
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        release()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun release() {
        if (_instance.value === this) _instance.value = null
        nodeRefs = emptyMap()
    }

    /**
     * Capture the active window hierarchy.
     * Distinguishes: transient root unavailability (loading) vs hard blocked packages.
     * Retries across app transitions so a temporary null root is not reported as
     * "accessibility disabled/restricted".
     */
    suspend fun inspectScreen(
        timeoutMs: Long = INSPECT_TIMEOUT_MS
    ): ScreenSnapshot = withContext(Dispatchers.Default) {
        val (w, h) = screenSize()
        val start = SystemClock.uptimeMillis()
        var lastBlockedPkg: String? = null

        while (SystemClock.uptimeMillis() - start < timeoutMs) {
            val root = activeRoot()
            if (root == null) {
                delay(ROOT_RETRY_DELAY_MS)
                continue
            }
            val pkg = root.packageName?.toString()
            // Self / system UI during transition — wait for the real target window
            if (pkg != null && pkg in blockedPackages) {
                lastBlockedPkg = pkg
                delay(ROOT_RETRY_DELAY_MS)
                continue
            }
            val collected = collect(root)
            nodeRefs = collected.refs
            return@withContext ScreenSnapshot(
                packageName = pkg,
                width = w,
                height = h,
                nodes = collected.nodes,
                truncated = collected.truncated
            )
        }

        if (lastBlockedPkg != null && currentPackage() in blockedPackages) {
            nodeRefs = emptyMap()
            return@withContext ScreenSnapshot(
                packageName = lastBlockedPkg,
                width = w,
                height = h,
                nodes = emptyList(),
                blocked = true
            )
        }
        nodeRefs = emptyMap()
        ScreenSnapshot(
            packageName = currentPackage(),
            width = w,
            height = h,
            nodes = emptyList(),
            rootUnavailable = true
        )
    }

    private class Collected(
        val nodes: List<ScreenNode>,
        val refs: Map<Int, AccessibilityNodeInfo>,
        val truncated: Boolean
    )

    private fun collect(root: AccessibilityNodeInfo): Collected {
        val nodes = mutableListOf<ScreenNode>()
        val refs = mutableMapOf<Int, AccessibilityNodeInfo>()
        var truncated = false
        var nextId = 1

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (nodes.size >= MAX_NODES) {
                truncated = true
                return
            }
            if (depth > MAX_DEPTH) {
                truncated = true
                return
            }
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val text = node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            val desc = node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            val hint = if (android.os.Build.VERSION.SDK_INT >= 26) {
                node.hintText?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            } else null
            val viewId = node.viewIdResourceName?.substringAfterLast('/')
            val id = nextId++
            refs[id] = node
            nodes += ScreenNode(
                id = id,
                className = node.className?.toString() ?: "",
                text = text?.clip(MAX_TEXT),
                contentDescription = desc?.clip(MAX_TEXT),
                hint = hint?.clip(MAX_TEXT),
                viewId = viewId,
                bounds = BoundsRect(bounds.left, bounds.top, bounds.right, bounds.bottom),
                clickable = node.isClickable,
                longClickable = node.isLongClickable,
                scrollable = node.isScrollable,
                editable = node.isEditable,
                focused = node.isFocused,
                checked = if (node.isCheckable) node.isChecked else null,
                enabled = node.isEnabled,
                passwordField = node.isPassword
            )
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                visit(child, depth + 1)
            }
        }

        visit(root, 0)
        return Collected(nodes, refs, truncated)
    }

    fun currentPackage(): String? = activeRoot()?.packageName?.toString()
        ?: rootInActiveWindow?.packageName?.toString()

    suspend fun clickNode(nodeId: Int): ActionResult = withContext(Dispatchers.Default) {
        blockedResult()?.let { return@withContext it }
        val node = nodeRefs[nodeId]
            ?: return@withContext ActionResult.fail("Unknown node $nodeId. Inspect the screen again.")
        if (!node.refresh()) {
            return@withContext ActionResult.fail("Node $nodeId is stale.")
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            ActionResult.ok("Clicked node $nodeId")
        } else {
            ActionResult.fail("Click failed on node $nodeId")
        }
    }

    suspend fun longClickNode(nodeId: Int): ActionResult = withContext(Dispatchers.Default) {
        blockedResult()?.let { return@withContext it }
        val node = nodeRefs[nodeId]
            ?: return@withContext ActionResult.fail("Unknown node $nodeId.")
        if (!node.refresh()) return@withContext ActionResult.fail("Node $nodeId is stale.")
        if (node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
            ActionResult.ok("Long-clicked node $nodeId")
        } else {
            ActionResult.fail("Long-click failed on node $nodeId")
        }
    }

    suspend fun setText(text: String, nodeId: Int? = null, append: Boolean = false): ActionResult =
        withContext(Dispatchers.Default) {
            blockedResult()?.let { return@withContext it }
            val node = resolveEditable(nodeId)
                ?: return@withContext ActionResult.fail("No editable field found.")
            if (!node.refresh()) return@withContext ActionResult.fail("Editable node is stale.")
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    if (append) ((node.text?.toString() ?: "") + text) else text
                )
            }
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                ActionResult.ok("Typed text")
            } else {
                ActionResult.fail("Could not set text")
            }
        }

    suspend fun pressEnter(nodeId: Int? = null): ActionResult = withContext(Dispatchers.Default) {
        blockedResult()?.let { return@withContext it }
        val node = resolveEditable(nodeId)
        if (node != null && node.refresh()) {
            val args = Bundle().apply {
                putInt(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
                    AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE
                )
                putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, false)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, args)
        }
        ActionResult.ok("Enter")
    }

    private fun resolveEditable(nodeId: Int?): AccessibilityNodeInfo? {
        if (nodeId != null) {
            val n = nodeRefs[nodeId]
            if (n != null && n.isEditable) return n
        }
        nodeRefs.values.firstOrNull { it.isEditable && it.isFocused }?.let { return it }
        nodeRefs.values.firstOrNull { it.isEditable }?.let { return it }
        return activeRoot()?.let { findFirstEditable(it, 0) }
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > MAX_DEPTH) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findFirstEditable(child, depth + 1)?.let { return it }
        }
        return null
    }

    suspend fun tap(x: Int, y: Int): ActionResult {
        blockedResult()?.let { return it }
        checkPoint(x, y)?.let { return it }
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return if (dispatchStroke(path, TAP_MS)) ActionResult.ok("Tapped ($x,$y)")
        else ActionResult.fail("Tap gesture failed")
    }

    suspend fun longPress(x: Int, y: Int): ActionResult {
        blockedResult()?.let { return it }
        checkPoint(x, y)?.let { return it }
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return if (dispatchStroke(path, LONG_PRESS_MS)) ActionResult.ok("Long-pressed ($x,$y)")
        else ActionResult.fail("Long-press failed")
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = SWIPE_MS): ActionResult {
        blockedResult()?.let { return it }
        checkPoint(x1, y1)?.let { return it }
        checkPoint(x2, y2)?.let { return it }
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        return if (dispatchStroke(path, durationMs)) ActionResult.ok("Swiped")
        else ActionResult.fail("Swipe failed")
    }

    suspend fun scroll(direction: ScrollDirection): ActionResult {
        blockedResult()?.let { return it }
        val (w, h) = screenSize()
        val (x1, y1, x2, y2) = when (direction) {
            ScrollDirection.UP -> listOf(w / 2, (h * 0.7).toInt(), w / 2, (h * 0.3).toInt())
            ScrollDirection.DOWN -> listOf(w / 2, (h * 0.3).toInt(), w / 2, (h * 0.7).toInt())
            ScrollDirection.LEFT -> listOf((w * 0.7).toInt(), h / 2, (w * 0.3).toInt(), h / 2)
            ScrollDirection.RIGHT -> listOf((w * 0.3).toInt(), h / 2, (w * 0.7).toInt(), h / 2)
        }
        return swipe(x1, y1, x2, y2, SCROLL_MS)
    }

    private suspend fun dispatchStroke(path: Path, durationMs: Long): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return suspendCancellableCoroutine { cont ->
            val ok = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }, null)
            if (!ok && cont.isActive) cont.resume(false)
        }
    }

    private fun checkPoint(x: Int, y: Int): ActionResult? {
        val (w, h) = screenSize()
        if (x !in 0 until w || y !in 0 until h) {
            return ActionResult.fail("Point ($x,$y) outside screen ${w}x$h")
        }
        return null
    }

    fun globalAction(command: GlobalCommand): ActionResult {
        val code = when (command) {
            GlobalCommand.HOME -> GLOBAL_ACTION_HOME
            GlobalCommand.BACK -> GLOBAL_ACTION_BACK
            GlobalCommand.RECENTS -> GLOBAL_ACTION_RECENTS
            GlobalCommand.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
            GlobalCommand.QUICK_SETTINGS -> GLOBAL_ACTION_QUICK_SETTINGS
        }
        return if (performGlobalAction(code)) ActionResult.ok("Performed ${command.name}")
        else ActionResult.fail("The system refused ${command.name}.")
    }

    suspend fun launchApp(nameOrPackage: String): ActionResult = withContext(Dispatchers.Default) {
        val app = resolveApp(nameOrPackage)
            ?: return@withContext ActionResult.fail(
                "\"$nameOrPackage\" install nahi hai ya match nahi mila."
            )
        if (app.packageName in blockedPackages) {
            return@withContext ActionResult.fail("Blocked: JARVIS is not allowed to open ${app.label}.")
        }

        val intent = packageManager.getLaunchIntentForPackage(app.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            ?: Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(app.packageName, app.activityName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

        val failure: ActionResult? = try {
            startActivity(intent)
            null
        } catch (e: ActivityNotFoundException) {
            ActionResult.fail("${app.label} has no launchable screen.")
        } catch (e: SecurityException) {
            ActionResult.fail("Android refused to open ${app.label}.")
        } catch (e: Exception) {
            ActionResult.fail("${app.label} open nahi ho paya.")
        }
        if (failure != null) return@withContext failure

        if (awaitForegroundPackage(app.packageName, LAUNCH_TIMEOUT_MS)) {
            awaitRootReady(ROOT_WAIT_TIMEOUT_MS)
            awaitUiSettled(500L, 3000L)
            ActionResult.ok("Opened ${app.label}")
        } else {
            ActionResult.fail(
                "Requested ${app.label}, but it did not reach the foreground " +
                    "(background launch may be blocked). Try HOME and tap its icon."
            )
        }
    }

    fun installedAppLabels(): List<String> =
        launchableApps().map { it.label }.distinct().sortedBy { it.lowercase() }

    fun dial(number: String, placeCall: Boolean = false): ActionResult {
        val cleaned = number.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        if (cleaned.isEmpty()) return ActionResult.fail("\"$number\" is not a valid phone number.")

        val canCall = placeCall &&
            checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val intent = Intent(
            if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL,
            Uri.fromParts("tel", cleaned, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            startActivity(intent)
            if (canCall) ActionResult.ok("Calling $cleaned") else ActionResult.ok("Dialer opened with $cleaned")
        } catch (e: ActivityNotFoundException) {
            ActionResult.fail("No dialer app is available.")
        } catch (e: SecurityException) {
            ActionResult.fail("Android refused to start the call.")
        }
    }

    suspend fun awaitUiSettled(quietMs: Long = 400L, timeoutMs: Long = 4000L) {
        val start = SystemClock.uptimeMillis()
        delay(quietMs.coerceAtMost(timeoutMs))
        while (SystemClock.uptimeMillis() - lastEventUptime < quietMs &&
            SystemClock.uptimeMillis() - start < timeoutMs
        ) {
            delay(50L)
        }
    }

    suspend fun awaitForegroundPackage(pkg: String, timeoutMs: Long): Boolean {
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < timeoutMs) {
            if (currentPackage() == pkg) return true
            delay(150L)
        }
        return false
    }

    private fun activeRoot(): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { return it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val wins = windows ?: return null
                wins.firstOrNull { it.isActive }?.root?.let { return it }
                wins.firstOrNull {
                    it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
                }?.root?.let { return it }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private suspend fun awaitRoot(timeoutMs: Long = ROOT_WAIT_TIMEOUT_MS): AccessibilityNodeInfo? {
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < timeoutMs) {
            activeRoot()?.let { return it }
            delay(ROOT_RETRY_DELAY_MS)
        }
        return null
    }

    suspend fun awaitRootReady(timeoutMs: Long = ROOT_WAIT_TIMEOUT_MS): Boolean {
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < timeoutMs) {
            val root = activeRoot()
            val pkg = root?.packageName?.toString()
            if (root != null && (pkg == null || pkg !in blockedPackages)) {
                return true
            }
            delay(ROOT_RETRY_DELAY_MS)
        }
        return activeRoot() != null
    }

    private fun blockedResult(): ActionResult? {
        val pkg = currentPackage() ?: return null
        return if (pkg in blockedPackages) {
            ActionResult.fail("Blocked: JARVIS is not allowed to operate $pkg.")
        } else null
    }

    private fun screenSize(): Pair<Int, Int> {
        val m = resources.displayMetrics
        return m.widthPixels to m.heightPixels
    }

    private data class AppEntry(val label: String, val packageName: String, val activityName: String)

    private fun launchableApps(): List<AppEntry> {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager
            .queryIntentActivities(query, PackageManager.ResolveInfoFlags.of(0L))
            .map {
                AppEntry(
                    label = it.loadLabel(packageManager).toString(),
                    packageName = it.activityInfo.packageName,
                    activityName = it.activityInfo.name
                )
            }
    }

    private fun resolveApp(query: String): AppEntry? {
        val apps = launchableApps()
        val launchables = apps.map {
            AppResolver.Launchable(it.label, it.packageName, it.activityName)
        }
        val hit = AppResolver.resolve(query, launchables) ?: return null
        return apps.firstOrNull { it.packageName == hit.packageName && it.label == hit.label }
            ?: apps.firstOrNull { it.packageName == hit.packageName }
    }

    private fun String.clip(max: Int): String = if (length <= max) this else take(max) + "…"

    companion object {
        private const val MAX_NODES = 350
        private const val MAX_DEPTH = 30
        private const val MAX_TEXT = 200
        private const val MAX_PARENT_HOPS = 5
        private const val ROOT_RETRY_DELAY_MS = 150L
        private const val ROOT_WAIT_TIMEOUT_MS = 5_000L
        private const val INSPECT_TIMEOUT_MS = 4_000L
        private const val TAP_MS = 50L
        private const val LONG_PRESS_MS = 800L
        private const val SWIPE_MS = 300L
        private const val SCROLL_MS = 350L
        private const val LAUNCH_TIMEOUT_MS = 5_000L

        private val _instance = MutableStateFlow<JarvisAccessibilityService?>(null)

        val instance: StateFlow<JarvisAccessibilityService?> = _instance.asStateFlow()

        @Volatile
        var blockedPackages: Set<String> = emptySet()

        fun isEnabled(context: Context): Boolean {
            val am = context.getSystemService(AccessibilityManager::class.java) ?: return false
            return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any {
                    val si = it.resolveInfo.serviceInfo
                    si.packageName == context.packageName &&
                        si.name == JarvisAccessibilityService::class.java.name
                }
        }
    }
}
