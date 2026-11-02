package com.example.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.example.StudyGuardApp
import com.example.ui.lock.BlockedOverlayActivity
import com.example.util.AppLimitCheckResult
import com.example.util.AppLimitManager
import com.example.util.EmergencyLockManager
import com.example.util.ScheduledAutoLockManager
import com.example.util.StudyPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class FocusAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val packageInterceptTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var lastShortsInterceptTime = 0L
    private var lastLimitInterceptTime = 0L
    private var currentForegroundPackage: String? = null
    private var foregroundStartTime: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceScope.launch {
            try {
                val list = StudyGuardApp.instance.repository.getDistractionPackageNames()
                com.example.data.repository.StudyRepository.distractionCache.addAll(list)
            } catch (e: Exception) {
                // Ignore initialization race
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val context = applicationContext
        val emergencyLock = EmergencyLockManager.getInstance(context)
        val isNuclearLockActive = emergencyLock.isNuclearLockActive()
        val isNuclearBlockActive = emergencyLock.isNuclearBlockAppsActive()
        val isStudyActive = StudyPreferences.isStudyActive(context) || isNuclearBlockActive
        val packageName = event.packageName?.toString()
            ?: rootInActiveWindow?.packageName?.toString()
            ?: return

        // Track foreground time for app limits
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val now = System.currentTimeMillis()
            val prev = currentForegroundPackage
            if (prev != null && prev != packageName && foregroundStartTime > 0L) {
                val elapsed = now - foregroundStartTime
                if (elapsed > 1000L) {
                    AppLimitManager.getInstance(context).recordForegroundUsage(prev, elapsed)
                }
            }
            currentForegroundPackage = packageName
            foregroundStartTime = now
        }

        // Ignore our own app & system essentials (system UI, launchers, input methods)
        if (isSystemEssentialPackage(packageName)) {
            return
        }

        // 1. YouTube Shorts Detection & Blocking (Allow regular study videos!)
        if (packageName == "com.google.android.youtube") {
            if ((isStudyActive && StudyPreferences.isBlockYouTubeShortsEnabled(context)) || isNuclearBlockActive) {
                handleYouTubeAccessibility(event)
            }
            return
        }

        // 2. Prevent Uninstall & System Settings Tampering during Study Hours
        if (isSettingsOrInstallerPackage(packageName)) {
            if ((isStudyActive && StudyPreferences.isStrictUninstallLockEnabled(context)) || isNuclearLockActive) {
                checkAndPreventUninstallAttempt(event)
            }
            return
        }

        // 3. App Daily Limit & Half-Time Cooldown Check (Applies to all configured apps!)
        if (checkAppLimitLockout(packageName)) {
            return
        }

        // 4. Distraction App Interception (Timer, Nuclear Lock, or Scheduled Auto-Lock Gate)
        checkAndBlockDistractionApp(packageName, isStudyActive, isNuclearBlockActive)
    }

    private fun checkAppLimitLockout(packageName: String): Boolean {
        val now = System.currentTimeMillis()
        val limitManager = AppLimitManager.getInstance(applicationContext)
        val limitResult = limitManager.checkAppLimit(applicationContext, packageName)

        if (limitResult is AppLimitCheckResult.LimitExceeded) {
            if (now - lastLimitInterceptTime > 1200L) {
                lastLimitInterceptTime = now
                val appName = try {
                    val appInfo = packageManager.getApplicationInfo(packageName, 0)
                    packageManager.getApplicationLabel(appInfo).toString()
                } catch (e: Exception) {
                    packageName
                }
                performGlobalAction(GLOBAL_ACTION_HOME)
                launchAppLimitBlockScreen(
                    appName = appName,
                    isCooldown = false,
                    limitMins = limitResult.limitMinutes,
                    usedMins = limitResult.usedMinutes,
                    cooldownRemainingSec = 0L
                )
            }
            return true
        } else if (limitResult is AppLimitCheckResult.CooldownActive) {
            if (now - lastLimitInterceptTime > 1200L) {
                lastLimitInterceptTime = now
                val appName = try {
                    val appInfo = packageManager.getApplicationInfo(packageName, 0)
                    packageManager.getApplicationLabel(appInfo).toString()
                } catch (e: Exception) {
                    packageName
                }
                performGlobalAction(GLOBAL_ACTION_HOME)
                launchAppLimitBlockScreen(
                    appName = appName,
                    isCooldown = true,
                    limitMins = limitResult.limitMinutes,
                    usedMins = limitResult.usedMinutes,
                    cooldownRemainingSec = limitResult.remainingCooldownSeconds
                )
            }
            return true
        }

        return false
    }

    private fun launchAppLimitBlockScreen(
        appName: String,
        isCooldown: Boolean,
        limitMins: Int,
        usedMins: Int,
        cooldownRemainingSec: Long
    ) {
        val intent = Intent(applicationContext, BlockedOverlayActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(BlockedOverlayActivity.EXTRA_BLOCKED_APP_NAME, appName)
            putExtra(BlockedOverlayActivity.EXTRA_IS_APP_LIMIT, !isCooldown)
            putExtra(BlockedOverlayActivity.EXTRA_IS_COOLDOWN, isCooldown)
            putExtra(BlockedOverlayActivity.EXTRA_LIMIT_MINS, limitMins)
            putExtra(BlockedOverlayActivity.EXTRA_USED_MINS, usedMins)
            putExtra(BlockedOverlayActivity.EXTRA_COOLDOWN_REMAINING_SEC, cooldownRemainingSec)
        }
        applicationContext.startActivity(intent)
    }

    private fun handleYouTubeAccessibility(event: AccessibilityEvent) {
        val rootNode = rootInActiveWindow ?: return
        val isShorts = isYouTubeShortsNode(rootNode) || isShortsEvent(event)

        if (isShorts) {
            val now = System.currentTimeMillis()
            if (now - lastShortsInterceptTime > 2000L) {
                lastShortsInterceptTime = now
                performGlobalAction(GLOBAL_ACTION_BACK)

                mainHandler.post {
                    Toast.makeText(
                        applicationContext,
                        "🛑 YouTube Shorts blocked! Normal study videos are allowed 📚",
                        Toast.LENGTH_LONG
                    ).show()
                }

                serviceScope.launch {
                    StudyGuardApp.instance.repository.logDistractionEvent(
                        packageName = "com.google.android.youtube",
                        appName = "YouTube Shorts",
                        eventType = "YOUTUBE_SHORTS_BLOCKED",
                        reason = "Shorts blocked during study session"
                    )
                }
            }
        }
    }

    private fun isYouTubeShortsNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        if (viewId.contains("reel_recycler") ||
            viewId.contains("reel_player") ||
            viewId.contains("shorts_player") ||
            viewId.contains("shorts_container") ||
            viewId.contains("reel_player_page_container") ||
            viewId.contains("pivot_button")
        ) {
            return true
        }

        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""

        if (node.isSelected && (desc == "shorts" || text == "shorts")) {
            return true
        }

        if (desc.contains("sound used in this short") ||
            desc.contains("remix this video") ||
            desc.contains("shorts player") ||
            text.contains("sound used in this short")
        ) {
            return true
        }

        val childCount = node.childCount
        for (i in 0 until childCount.coerceAtMost(15)) {
            val child = node.getChild(i)
            if (child != null) {
                val found = isYouTubeShortsNode(child)
                child.recycle()
                if (found) return true
            }
        }

        return false
    }

    private fun isShortsEvent(event: AccessibilityEvent): Boolean {
        val className = event.className?.toString()?.lowercase() ?: ""
        if (className.contains("reel") || className.contains("shortsactivity")) {
            return true
        }
        val textList = event.text
        for (cs in textList) {
            val s = cs.toString().lowercase()
            if (s.contains("sound used in this short") || s.contains("remix this video")) {
                return true
            }
        }
        return false
    }

    private fun checkAndPreventUninstallAttempt(event: AccessibilityEvent) {
        val rootNode = rootInActiveWindow ?: return
        val containsStudyGuard = searchNodeText(rootNode, "studyguard")

        if (containsStudyGuard) {
            val containsUninstallWords = searchNodeText(rootNode, "uninstall") ||
                    searchNodeText(rootNode, "force stop") ||
                    searchNodeText(rootNode, "deactivate") ||
                    searchNodeText(rootNode, "clear data")

            if (containsUninstallWords) {
                performGlobalAction(GLOBAL_ACTION_HOME)
                mainHandler.post {
                    Toast.makeText(
                        applicationContext,
                        "🛡️ StudyGuard cannot be uninstalled or stopped during study hours!",
                        Toast.LENGTH_LONG
                    ).show()
                }

                StudyPreferences.setLastBlockedApp(
                    applicationContext,
                    "Uninstall Protection",
                    "Tampering and uninstallation are strictly locked during active study sessions."
                )

                val isNuclear = EmergencyLockManager.getInstance(applicationContext).isNuclearLockActive()
                launchBlockScreen(
                    appName = "Uninstall Protection",
                    isScheduled = false,
                    isNuclear = isNuclear,
                    ruleName = "Uninstall Tamper Protection",
                    remainingStudyMins = 0,
                    requiredStudyMins = 0,
                    studiedMins = 0
                )

                serviceScope.launch {
                    StudyGuardApp.instance.repository.logDistractionEvent(
                        packageName = "com.android.settings",
                        appName = "Settings / App Manager",
                        eventType = "SETTINGS_TAMPER_PREVENTED",
                        reason = "Attempted uninstall/deactivation intercepted during study session"
                    )
                }
            }
        }
    }

    private fun searchNodeText(node: AccessibilityNodeInfo?, target: String): Boolean {
        if (node == null) return false
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (text.contains(target) || desc.contains(target)) return true

        val count = node.childCount
        for (i in 0 until count.coerceAtMost(10)) {
            val child = node.getChild(i)
            if (child != null) {
                val found = searchNodeText(child, target)
                child.recycle()
                if (found) return true
            }
        }
        return false
    }

    private fun checkAndBlockDistractionApp(packageName: String, isStudyActive: Boolean, isNuclearActive: Boolean) {
        // Fast synchronous check against in-memory distraction cache
        val isDistraction = com.example.data.repository.StudyRepository.isPackageDistraction(packageName)
        if (!isDistraction) return

        val now = System.currentTimeMillis()
        val lastIntercept = packageInterceptTimes[packageName] ?: 0L
        if (now - lastIntercept < 800L) {
            return // Debounce rapid accessibility window content events for the exact same package
        }

        val autoLockManager = ScheduledAutoLockManager(applicationContext)
        // If neither study nor nuclear is active, check if any auto-lock rule is enabled
        if (!isStudyActive && !isNuclearActive) {
            val enabledRules = autoLockManager.getRules().filter { it.isEnabled }
            if (enabledRules.isEmpty()) return
        }

        packageInterceptTimes[packageName] = now

        serviceScope.launch {
            val todayMins = StudyGuardApp.instance.repository.getTodayStudiedMinutes()
            val autoLockStatus = autoLockManager.getActiveRule(todayMins)

            // If timer is not active AND no nuclear lock AND no auto-lock schedule rule is active, allow the app
            if (!isStudyActive && !isNuclearActive && autoLockStatus == null) {
                return@launch
            }

            val appName = try {
                val appInfo = packageManager.getApplicationInfo(packageName, 0)
                packageManager.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                packageName
            }

            val nuclearStatus = if (isNuclearActive) EmergencyLockManager.getInstance(applicationContext).checkStatus() else null
            val isScheduled = !isStudyActive && !isNuclearActive && autoLockStatus != null
            val reason = when {
                isNuclearActive -> "☢️ Nuclear Total Lockout Active (${nuclearStatus?.formattedRemaining} remaining). No early exit permitted!"
                isScheduled -> "First study for ${autoLockStatus!!.remainingStudyMinutes} more mins today to unlock this app!"
                else -> "Distraction app locked until study session finishes."
            }

            StudyPreferences.setLastBlockedApp(applicationContext, appName, reason)

            // Minimize distraction app to Home and pop lock overlay
            performGlobalAction(GLOBAL_ACTION_HOME)
            launchBlockScreen(
                appName = appName,
                isScheduled = isScheduled,
                isNuclear = isNuclearActive,
                ruleName = if (isNuclearActive) "Nuclear Total Lockout" else (autoLockStatus?.rule?.name ?: "Scheduled Focus Gate"),
                remainingStudyMins = autoLockStatus?.remainingStudyMinutes ?: 0,
                requiredStudyMins = autoLockStatus?.rule?.requiredStudyMinutes ?: 0,
                studiedMins = autoLockStatus?.completedStudyMinutes ?: todayMins
            )

            StudyGuardApp.instance.repository.logDistractionEvent(
                packageName = packageName,
                appName = appName,
                eventType = if (isNuclearActive) "NUCLEAR_LOCK_BLOCKED" else if (isScheduled) "SCHEDULED_AUTOLOCK_BLOCKED" else "APP_BLOCKED",
                reason = reason
            )
        }
    }

    private fun launchBlockScreen(
        appName: String,
        isScheduled: Boolean,
        isNuclear: Boolean,
        ruleName: String,
        remainingStudyMins: Int,
        requiredStudyMins: Int,
        studiedMins: Int
    ) {
        val intent = Intent(applicationContext, BlockedOverlayActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            putExtra(BlockedOverlayActivity.EXTRA_BLOCKED_APP_NAME, appName)
            putExtra(BlockedOverlayActivity.EXTRA_IS_SCHEDULED_LOCK, isScheduled)
            putExtra(BlockedOverlayActivity.EXTRA_IS_NUCLEAR_LOCK, isNuclear)
            putExtra(BlockedOverlayActivity.EXTRA_RULE_NAME, ruleName)
            putExtra(BlockedOverlayActivity.EXTRA_REMAINING_STUDY_MINS, remainingStudyMins)
            putExtra(BlockedOverlayActivity.EXTRA_REQUIRED_STUDY_MINS, requiredStudyMins)
            putExtra(BlockedOverlayActivity.EXTRA_STUDIED_MINS, studiedMins)
        }
        applicationContext.startActivity(intent)
    }

    private fun isSettingsOrInstallerPackage(pkg: String): Boolean {
        return pkg == "com.android.settings" ||
                pkg == "com.google.android.packageinstaller" ||
                pkg == "com.android.packageinstaller" ||
                pkg.contains("packageinstaller")
    }

    private fun isSystemEssentialPackage(pkg: String): Boolean {
        return pkg == packageName ||
                pkg == "com.android.systemui" ||
                pkg.contains("launcher") ||
                pkg.contains("nexuslauncher") ||
                pkg.contains("inputmethod") ||
                pkg == "android"
    }

    override fun onInterrupt() {
        // Accessibility service interrupted
    }
}
