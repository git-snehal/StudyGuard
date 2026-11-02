package com.example.util

import android.app.AppOpsManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import java.util.Calendar

data class AppUsageInfo(
    val packageName: String,
    val appName: String,
    val totalTimeInForegroundMs: Long,
    val lastTimeUsedMs: Long,
    val isDistraction: Boolean = false,
    val isStudyApp: Boolean = false
)

object UsageStatsManagerHelper {

    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun openUsageAccessSettings(context: Context) {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    fun getTodayAppUsageList(context: Context): List<AppUsageInfo> {
        if (!hasUsageStatsPermission(context)) {
            return emptyList()
        }

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyList()

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startTime = calendar.timeInMillis
        val endTime = System.currentTimeMillis()

        val stats: List<UsageStats> = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime,
            endTime
        ) ?: emptyList()

        val pm = context.packageManager
        val list = mutableListOf<AppUsageInfo>()

        for (u in stats) {
            if (u.totalTimeInForeground > 30_000L) { // Filter out less than 30s
                val pkg = u.packageName
                if (pkg == context.packageName || pkg.contains("systemui") || pkg.contains("launcher")) {
                    continue
                }

                val appName = try {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    pm.getApplicationLabel(appInfo).toString()
                } catch (e: Exception) {
                    pkg
                }

                val isDistraction = InstalledAppsHelper.KNOWN_DISTRACTIONS.contains(pkg)
                val isStudyApp = InstalledAppsHelper.KNOWN_STUDY_APPS.contains(pkg) || pkg == "com.google.android.youtube"

                list.add(
                    AppUsageInfo(
                        packageName = pkg,
                        appName = appName,
                        totalTimeInForegroundMs = u.totalTimeInForeground,
                        lastTimeUsedMs = u.lastTimeUsed,
                        isDistraction = isDistraction,
                        isStudyApp = isStudyApp
                    )
                )
            }
        }

        return list.sortedByDescending { it.totalTimeInForegroundMs }
    }

    fun getTodayPackageUsageMs(context: Context, packageName: String): Long {
        if (!hasUsageStatsPermission(context)) {
            return 0L
        }
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return 0L

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startTime = calendar.timeInMillis
        val endTime = System.currentTimeMillis()

        val stats: List<UsageStats> = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime,
            endTime
        ) ?: emptyList()

        val target = stats.find { it.packageName == packageName }
        return target?.totalTimeInForeground ?: 0L
    }

    fun getTodayTotalScreenTimeMs(context: Context): Long {
        return getTodayAppUsageList(context).sumOf { it.totalTimeInForegroundMs }
    }

    fun getTodayDistractionTimeMs(context: Context): Long {
        return getTodayAppUsageList(context).filter { it.isDistraction }.sumOf { it.totalTimeInForegroundMs }
    }

    fun getForegroundPackageName(context: Context): String? {
        if (!hasUsageStatsPermission(context)) return null
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        val now = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(now - 10_000L, now) ?: return null
        var lastPkg: String? = null
        val event = android.app.usage.UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED ||
                event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND
            ) {
                lastPkg = event.packageName
            }
        }
        return lastPkg
    }
}
