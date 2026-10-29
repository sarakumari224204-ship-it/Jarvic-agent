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
        // Explicitly apply config so the service binds cleanly on every enable.
        // Do not perform heavy work or throw here — any exception marks the service
        // as "malfunctioning" in Android Settings.
        try {
            serviceInfo = serviceInfo?.apply {
                flags = flags or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                notificationTimeout = 100
            }
        } catch (_: Exception) {
            // Config already applied from XML; ignore if framework rejects a flag.
        }
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
        // Restored from last known-good (a0be6b5): only keep visible + interesting nodes.
        // Collecting every node (including invisible/empty) previously increased memory
        // pressure and node-ref churn; that path is not required for automation.
        val nodes = ArrayList<ScreenNode>()
        val refs = HashMap<Int, AccessibilityNodeInfo>()
        var truncated = false
        val rect = Rect()

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (nodes.size >= MAX_NODES) {
                truncated = true
                return
            }
            if (depth > MAX_DEPTH || !node.isVisibleToUser) return

            node.getBoundsInScreen(rect)
            val isPassword = node.isPassword
            val text = if (isPassword) null else node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            val desc = node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            val hint = if (Build.VERSION.SDK_INT >= 26) {
                node.hintText?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            } else null

            val interesting = text != null || desc != null ||
                node.isClickable || node.isLongClickable || node.isEditable ||
                node.isScrollable || node.isCheckable

            if (interesting && rect.width() > 0 && rect.height() > 0) {
                val id = nodes.size
                nodes += ScreenNode(
                    id = id,
                    className = node.className?.toString() ?: "View",
                    text = text?.clip(MAX_TEXT),
                    contentDescription = desc?.clip(MAX_TEXT),
                    hint = hint?.clip(MAX_TEXT),
                    viewId = node.viewIdResourceName?.substringAfter(":id/"),
                    bounds = BoundsRect(rect.left, rect.top, rect.right, rect.bottom),
                    clickable = node.isClickable,
                    longClickable = node.isLongClickable,
                    scrollable = node.isScrollable,
                    editable = node.isEditable,
                    focused = node.isFocused,
                    checked = if (node.isCheckable) node.isChecked else null,
                    enabled = node.isEnabled,
                    passwordField = isPassword
                )
                refs[id] = node
            }

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
            return@withContext ActionResult.fail("Node $nodeId is stale. Inspect the screen again.")
        }

        // Restored from last known-good: walk parents for a clickable target, then fall back to gesture tap.
        var target: AccessibilityNodeInfo? = node
        var hops = 0
        while (target != null && !target.isClickable && hops < MAX_PARENT_HOPS) {
            target = target.parent
            hops++
        }
        if (target != null && target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return@withContext ActionResult.ok("Clicked node $nodeId")
        }

        val rect = Rect()
        node.getBoundsInScreen(rect)
        tap(rect.centerX(), rect.centerY())
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val imeEnter = 0x01000007
                if (node.performAction(imeEnter)) {
                    return@withContext ActionResult.ok("IME enter")
                }
            }
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return@withContext ActionResult.ok("Enter via field click")
            }
        }
        val searchHit = nodeRefs.entries.firstOrNull { (_, n) ->
            if (!n.refresh()) return@firstOrNull false
            if (!n.isClickable && !n.isEnabled) return@firstOrNull false
            val blob = listOfNotNull(
                n.text?.toString(),
                n.contentDescription?.toString(),
                n.viewIdResourceName
            ).joinToString(" ").lowercase()
            blob.contains("search") || blob.contains("go") || blob.contains("enter") ||
                blob.contains("खोज") || blob.contains("जाएँ") || blob.contains("ime_action")
        }
        if (searchHit != null) {
            val n = searchHit.value
            if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return@withContext ActionResult.ok("Clicked search control ${searchHit.key}")
            }
        }
        ActionResult.ok("Enter attempted")
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
        return performGesture(x.toFloat(), y.toFloat(), x.toFloat(), y.toFloat(), TAP_MS, "tap")
    }

    suspend fun longPress(x: Int, y: Int): ActionResult {
        blockedResult()?.let { return it }
        checkPoint(x, y)?.let { return it }
        return performGesture(x.toFloat(), y.toFloat(), x.toFloat(), y.toFloat(), LONG_PRESS_MS, "long-press")
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int): ActionResult {
        blockedResult()?.let { return it }
        checkPoint(x1, y1)?.let { return it }
        return performGesture(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), SWIPE_MS, "swipe")
    }

    suspend fun scroll(direction: String): ActionResult = withContext(Dispatchers.Default) {
        blockedResult()?.let { return@withContext it }
        val root = activeRoot() ?: return@withContext ActionResult.fail("No active window")
        val action = when (direction.lowercase()) {
            "up", "forward" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            "down", "backward" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else -> return@withContext ActionResult.fail("Unknown scroll direction: $direction")
        }
        fun findScrollable(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
            if (depth > MAX_DEPTH) return null
            if (node.isScrollable) return node
            for (i in 0 until node.childCount) {
                val c = node.getChild(i) ?: continue
                findScrollable(c, depth + 1)?.let { return it }
            }
            return null
        }
        val target = findScrollable(root, 0)
        if (target != null && target.performAction(action)) {
            ActionResult.ok("Scrolled $direction")
        } else {
            ActionResult.fail("No scrollable node for $direction")
        }
    }

    private fun checkPoint(x: Int, y: Int): ActionResult? {
        val (w, h) = screenSize()
        if (x < 0 || y < 0 || x > w || y > h) {
            return ActionResult.fail("Point ($x,$y) is outside screen ${w}x$h")
        }
        return null
    }

    private suspend fun performGesture(
        x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long, label: String
    ): ActionResult = suspendCancellableCoroutine { cont ->
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(ActionResult.ok(label))
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(ActionResult.fail("$label cancelled"))
            }
        }, null)
        if (!ok && cont.isActive) cont.resume(ActionResult.fail("Could not dispatch $label"))
    }

    suspend fun globalAction(command: String): ActionResult {
        blockedResult()?.let { return it }
        val action = when (command.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents", "recent" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "power" -> GLOBAL_ACTION_POWER_DIALOG
            else -> return ActionResult.fail("Unknown global action: $command")
        }
        return if (performGlobalAction(action)) ActionResult.ok(command) else ActionResult.fail("Global $command failed")
    }

    suspend fun launchApp(query: String): ActionResult = withContext(Dispatchers.Default) {
        blockedResult()?.let { return@withContext it }
        val app = resolveApp(query) ?: return@withContext ActionResult.fail("App not found: $query")
        if (app.packageName in blockedPackages) {
            return@withContext ActionResult.fail("Blocked: JARVIS is not allowed to operate ${app.packageName}.")
        }
        val intent = packageManager.getLaunchIntentForPackage(app.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            ?: Intent().setClassName(app.packageName, app.activityName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return@withContext try {
            startActivity(intent)
            awaitRootReady(ROOT_WAIT_TIMEOUT_MS)
            ActionResult.ok("Opened ${app.label}")
        } catch (e: ActivityNotFoundException) {
            ActionResult.fail("Could not launch ${app.label}")
        } catch (e: SecurityException) {
            ActionResult.fail("Permission denied launching ${app.label}")
        } catch (e: Exception) {
            ActionResult.fail("Launch failed: ${e.message}")
        }
    }

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
