package com.example.util

import android.content.Context
import android.content.SharedPreferences

object StudyPreferences {
    private const val PREFS_NAME = "study_guard_prefs"
    private const val KEY_IS_STUDY_ACTIVE = "is_study_active"
    private const val KEY_SESSION_END_TIME = "session_end_time"
    private const val KEY_SESSION_START_TIME = "session_start_time"
    private const val KEY_SESSION_ID = "current_session_id"
    private const val KEY_SESSION_SUBJECT = "session_subject"
    private const val KEY_PLANNED_MINUTES = "planned_minutes"
    private const val KEY_BLOCK_YOUTUBE_SHORTS = "block_youtube_shorts"
    private const val KEY_STRICT_UNINSTALL_LOCK = "strict_uninstall_lock"
    private const val KEY_EMERGENCY_PASS_COUNT = "emergency_pass_count"
    private const val KEY_LAST_KNOWN_BLOCKED_APP = "last_known_blocked_app"
    private const val KEY_LAST_BLOCKED_REASON = "last_blocked_reason"
    private const val KEY_DAILY_GOAL_MINUTES = "daily_goal_minutes"
    private const val KEY_PERSISTED_DISTRACTION_PACKAGES = "persisted_distraction_packages"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getDailyGoalMinutes(context: Context): Int {
        return getPrefs(context).getInt(KEY_DAILY_GOAL_MINUTES, 180)
    }

    fun setDailyGoalMinutes(context: Context, minutes: Int) {
        getPrefs(context).edit().putInt(KEY_DAILY_GOAL_MINUTES, minutes.coerceAtLeast(15)).apply()
    }

    fun getPersistedDistractionPackages(context: Context): Set<String> {
        return getPrefs(context).getStringSet(KEY_PERSISTED_DISTRACTION_PACKAGES, emptySet()) ?: emptySet()
    }

    fun setPersistedDistractionPackages(context: Context, packages: Set<String>) {
        getPrefs(context).edit().putStringSet(KEY_PERSISTED_DISTRACTION_PACKAGES, packages).apply()
    }

    fun isStudyActive(context: Context): Boolean {
        val prefs = getPrefs(context)
        return prefs.getBoolean(KEY_IS_STUDY_ACTIVE, false)
    }

    fun hasActiveSessionExpired(context: Context): Boolean {
        val prefs = getPrefs(context)
        val active = prefs.getBoolean(KEY_IS_STUDY_ACTIVE, false)
        if (!active) return false
        val endTime = prefs.getLong(KEY_SESSION_END_TIME, 0L)
        return endTime > 0L && System.currentTimeMillis() >= endTime
    }

    fun setStudyActive(context: Context, active: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_IS_STUDY_ACTIVE, active).apply()
    }

    fun startStudySession(
        context: Context,
        sessionId: Long,
        subject: String,
        durationMinutes: Int
    ) {
        val now = System.currentTimeMillis()
        val endTime = now + (durationMinutes * 60 * 1000L)
        getPrefs(context).edit()
            .putBoolean(KEY_IS_STUDY_ACTIVE, true)
            .putLong(KEY_SESSION_ID, sessionId)
            .putString(KEY_SESSION_SUBJECT, subject)
            .putInt(KEY_PLANNED_MINUTES, durationMinutes)
            .putLong(KEY_SESSION_START_TIME, now)
            .putLong(KEY_SESSION_END_TIME, endTime)
            .apply()
    }

    fun stopStudySession(context: Context) {
        getPrefs(context).edit()
            .putBoolean(KEY_IS_STUDY_ACTIVE, false)
            .putLong(KEY_SESSION_END_TIME, 0L)
            .apply()
    }

    fun getPlannedMinutes(context: Context): Int {
        return getPrefs(context).getInt(KEY_PLANNED_MINUTES, 25)
    }

    fun getSessionEndTime(context: Context): Long {
        return getPrefs(context).getLong(KEY_SESSION_END_TIME, 0L)
    }

    fun getSessionStartTime(context: Context): Long {
        return getPrefs(context).getLong(KEY_SESSION_START_TIME, 0L)
    }

    fun getSessionSubject(context: Context): String {
        return getPrefs(context).getString(KEY_SESSION_SUBJECT, "Focus Study") ?: "Focus Study"
    }

    fun getCurrentSessionId(context: Context): Long {
        return getPrefs(context).getLong(KEY_SESSION_ID, -1L)
    }

    fun isBlockYouTubeShortsEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_BLOCK_YOUTUBE_SHORTS, true)
    }

    fun setBlockYouTubeShorts(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_BLOCK_YOUTUBE_SHORTS, enabled).apply()
    }

    fun isStrictUninstallLockEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_STRICT_UNINSTALL_LOCK, true)
    }

    fun setStrictUninstallLock(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_STRICT_UNINSTALL_LOCK, enabled).apply()
    }

    fun setLastBlockedApp(context: Context, appName: String, reason: String) {
        getPrefs(context).edit()
            .putString(KEY_LAST_KNOWN_BLOCKED_APP, appName)
            .putString(KEY_LAST_BLOCKED_REASON, reason)
            .apply()
    }

    fun getLastBlockedApp(context: Context): String {
        return getPrefs(context).getString(KEY_LAST_KNOWN_BLOCKED_APP, "Distraction App") ?: "Distraction App"
    }

    fun getLastBlockedReason(context: Context): String {
        return getPrefs(context).getString(KEY_LAST_BLOCKED_REASON, "Locked during active study session") 
            ?: "Locked during active study session"
    }

    enum class ThemeMode(val displayName: String, val emoji: String) {
        SYSTEM("System Default", "📱"),
        LIGHT("Classic Light", "☀️"),
        DARK("Deep Slate", "🌙"),
        MIDNIGHT_OLED("Midnight OLED", "🌌"),
        COZY_LOFI("Cozy Lo-Fi", "☕"),
        FOREST_ZEN("Forest Zen", "🌿")
    }

    fun getThemeMode(context: Context): ThemeMode {
        val str = getPrefs(context).getString("app_theme_mode", ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name
        return try {
            ThemeMode.valueOf(str)
        } catch (e: Exception) {
            ThemeMode.SYSTEM
        }
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        getPrefs(context).edit().putString("app_theme_mode", mode.name).apply()
    }
}
