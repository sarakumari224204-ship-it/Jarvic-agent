#!/usr/bin/env python3
"""Restore launchApp without blockedResult() on current package (a0be6b5 behavior)."""
from pathlib import Path

path = Path("app/src/main/java/com/jarvis/os/accessibility/JarvisAccessibilityService.kt")
text = path.read_text(encoding="utf-8")

# Already fixed?
if "Do NOT call blockedResult() here" in text and "suspend fun awaitForegroundPackage" in text:
    print("Already fixed")
    raise SystemExit(0)

old = """    suspend fun launchApp(query: String): ActionResult = withContext(Dispatchers.Default) {
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
    }"""

new = """    suspend fun launchApp(nameOrPackage: String): ActionResult = withContext(Dispatchers.Default) {
        // Do NOT call blockedResult() here: when Sara is in the foreground its own package
        // is in blockedPackages, which would incorrectly prevent launching any other app
        // (regression: \"YouTube kholo\" failed while Sara was open). Only the *target*
        // package is checked below.
        val app = resolveApp(nameOrPackage)
            ?: return@withContext ActionResult.fail(
                \"\\\"$nameOrPackage\\\" install nahi hai ya match nahi mila.\"
            )
        if (app.packageName in blockedPackages) {
            return@withContext ActionResult.fail(\"Blocked: JARVIS is not allowed to open ${app.label}.\")
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
            ActionResult.fail(\"${app.label} has no launchable screen.\")
        } catch (e: SecurityException) {
            ActionResult.fail(\"Android refused to open ${app.label}.\")
        } catch (e: Exception) {
            ActionResult.fail(\"${app.label} open nahi ho paya.\")
        }
        if (failure != null) return@withContext failure

        if (awaitForegroundPackage(app.packageName, LAUNCH_TIMEOUT_MS)) {
            ActionResult.ok(\"Opened ${app.label}\")
        } else {
            awaitRootReady(ROOT_WAIT_TIMEOUT_MS)
            ActionResult.ok(\"Opened ${app.label}\")
        }
    }"""

if old not in text:
    raise SystemExit("old launchApp block not found — file may already differ")

text = text.replace(old, new, 1)

if "suspend fun awaitForegroundPackage" not in text:
    anchor = "    suspend fun awaitRootReady"
    helper = """    suspend fun awaitForegroundPackage(pkg: String, timeoutMs: Long): Boolean {
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < timeoutMs) {
            if (currentPackage() == pkg) return true
            delay(150L)
        }
        return currentPackage() == pkg
    }

"""
    if anchor not in text:
        raise SystemExit("awaitRootReady missing")
    text = text.replace(anchor, helper + anchor, 1)

path.write_text(text, encoding="utf-8")
print("Patched launchApp OK, bytes", len(text))
assert "blockedResult()?.let" not in text[text.find("suspend fun launchApp"):text.find("fun dial")]
assert "awaitForegroundPackage" in text
