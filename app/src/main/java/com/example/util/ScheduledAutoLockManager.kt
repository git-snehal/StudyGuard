package com.example.util

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

data class AutoLockScheduleRule(
    val id: String,
    val name: String,
    val isEnabled: Boolean = true,
    val daysOfWeek: List<Int>, // Calendar.SUNDAY=1, MONDAY=2, ... SATURDAY=7
    val startHour: Int, // 0..23
    val startMinute: Int, // 0..59
    val endHour: Int, // 0..23
    val endMinute: Int, // 0..59
    val requiredStudyMinutes: Int // e.g. 120 (2 hours)
) {
    val formattedStartTime: String
        get() = formatTime(startHour, startMinute)

    val formattedEndTime: String
        get() = formatTime(endHour, endMinute)

    val formattedRequiredStudy: String
        get() {
            val hours = requiredStudyMinutes / 60
            val mins = requiredStudyMinutes % 60
            return when {
                hours > 0 && mins > 0 -> "${hours}h ${mins}m"
                hours > 0 -> "${hours}h"
                else -> "${mins}m"
            }
        }

    val daysFormatted: String
        get() {
            if (daysOfWeek.size == 7) return "Every day"
            if (daysOfWeek.containsAll(listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY)) && daysOfWeek.size == 5) {
                return "Weekdays"
            }
            if (daysOfWeek.containsAll(listOf(Calendar.SATURDAY, Calendar.SUNDAY)) && daysOfWeek.size == 2) {
                return "Weekends"
            }
            val map = mapOf(
                Calendar.MONDAY to "Mon",
                Calendar.TUESDAY to "Tue",
                Calendar.WEDNESDAY to "Wed",
                Calendar.THURSDAY to "Thu",
                Calendar.FRIDAY to "Fri",
                Calendar.SATURDAY to "Sat",
                Calendar.SUNDAY to "Sun"
            )
            return daysOfWeek.sorted().mapNotNull { map[it] }.joinToString(", ")
        }

    private fun formatTime(hour: Int, minute: Int): String {
        val period = if (hour >= 12) "PM" else "AM"
        val displayHour = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        return String.format(Locale.getDefault(), "%d:%02d %s", displayHour, minute, period)
    }
}

data class ActiveAutoLockStatus(
    val rule: AutoLockScheduleRule,
    val remainingStudyMinutes: Int,
    val completedStudyMinutes: Int
)

class ScheduledAutoLockManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("scheduled_auto_lock_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_SCHEDULES_JSON = "schedules_json"
        private const val KEY_INITIALIZED = "schedules_initialized_v2"
        private const val PREFIX_ACTIVATED_DATE = "rule_activated_date_"

        fun getTodayDateKey(): String {
            val cal = Calendar.getInstance()
            return String.format(
                Locale.US,
                "%04d-%02d-%02d",
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH)
            )
        }
    }

    private val defaultRules = listOf(
        AutoLockScheduleRule(
            id = "rule_morning_focus",
            name = "Morning Study Gate",
            isEnabled = true,
            daysOfWeek = listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY),
            startHour = 8,
            startMinute = 30,
            endHour = 13,
            endMinute = 0,
            requiredStudyMinutes = 120 // 2 hours required
        ),
        AutoLockScheduleRule(
            id = "rule_evening_revision",
            name = "Evening Revision Gate",
            isEnabled = true,
            daysOfWeek = listOf(Calendar.MONDAY, Calendar.WEDNESDAY, Calendar.FRIDAY),
            startHour = 18,
            startMinute = 0,
            endHour = 22,
            endMinute = 0,
            requiredStudyMinutes = 90 // 1.5 hours required
        )
    )

    fun getRules(): List<AutoLockScheduleRule> {
        val isInitialized = prefs.getBoolean(KEY_INITIALIZED, false)
        if (!isInitialized) {
            saveRules(defaultRules)
            prefs.edit().putBoolean(KEY_INITIALIZED, true).apply()
            return defaultRules
        }

        val json = prefs.getString(KEY_SCHEDULES_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            val list = mutableListOf<AutoLockScheduleRule>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val daysArr = obj.getJSONArray("daysOfWeek")
                val days = mutableListOf<Int>()
                for (j in 0 until daysArr.length()) {
                    days.add(daysArr.getInt(j))
                }
                list.add(
                    AutoLockScheduleRule(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        isEnabled = obj.optBoolean("isEnabled", true),
                        daysOfWeek = days,
                        startHour = obj.getInt("startHour"),
                        startMinute = obj.getInt("startMinute"),
                        endHour = obj.getInt("endHour"),
                        endMinute = obj.getInt("endMinute"),
                        requiredStudyMinutes = obj.getInt("requiredStudyMinutes")
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveRules(rules: List<AutoLockScheduleRule>) {
        val arr = JSONArray()
        rules.forEach { r ->
            val obj = JSONObject().apply {
                put("id", r.id)
                put("name", r.name)
                put("isEnabled", r.isEnabled)
                val daysArr = JSONArray()
                r.daysOfWeek.forEach { daysArr.put(it) }
                put("daysOfWeek", daysArr)
                put("startHour", r.startHour)
                put("startMinute", r.startMinute)
                put("endHour", r.endHour)
                put("endMinute", r.endMinute)
                put("requiredStudyMinutes", r.requiredStudyMinutes)
            }
            arr.put(obj)
        }
        prefs.edit().putString(KEY_SCHEDULES_JSON, arr.toString()).apply()
    }

    /**
     * If a custom lockdown is set for today (enabled on today's day of week or activated today)
     * OR currently running, it CANNOT be turned off, modified, or deleted until tomorrow.
     */
    fun isRuleLockedForToday(rule: AutoLockScheduleRule, todayStudiedMinutes: Int = 0): Boolean {
        if (!rule.isEnabled) return false
        val active = getActiveRule(todayStudiedMinutes)
        if (active?.rule?.id == rule.id) return true

        val now = Calendar.getInstance()
        val currentDay = now.get(Calendar.DAY_OF_WEEK)
        if (rule.daysOfWeek.contains(currentDay)) return true

        val todayKey = getTodayDateKey()
        val activatedDate = prefs.getString(PREFIX_ACTIVATED_DATE + rule.id, "")
        if (activatedDate == todayKey) return true

        return false
    }

    fun canModifyRule(rule: AutoLockScheduleRule, todayStudiedMinutes: Int = 0): Pair<Boolean, String> {
        if (isRuleLockedForToday(rule, todayStudiedMinutes)) {
            val active = getActiveRule(todayStudiedMinutes)
            val msg = if (active?.rule?.id == rule.id) {
                "🔒 '${rule.name}' is currently running! Distractions are locked until you study for ${active.remainingStudyMinutes} more minutes today. Cannot be turned down."
            } else {
                "🔒 '${rule.name}' is scheduled for today. Once a custom lockdown is set for the day, it cannot be turned down or deleted until tomorrow morning!"
            }
            return Pair(false, msg)
        }
        return Pair(true, "")
    }

    fun toggleRule(id: String, isEnabled: Boolean, todayStudiedMinutes: Int = 0): Boolean {
        val rule = getRules().find { it.id == id } ?: return false
        if (!isEnabled && isRuleLockedForToday(rule, todayStudiedMinutes)) {
            return false // Strict commitment: Refuse to turn down once set for today!
        }
        if (isEnabled) {
            val now = Calendar.getInstance()
            val currentDay = now.get(Calendar.DAY_OF_WEEK)
            if (rule.daysOfWeek.contains(currentDay)) {
                prefs.edit().putString(PREFIX_ACTIVATED_DATE + id, getTodayDateKey()).apply()
            }
        }
        val current = getRules().map {
            if (it.id == id) it.copy(isEnabled = isEnabled) else it
        }
        saveRules(current)
        return true
    }

    fun addRule(
        name: String,
        daysOfWeek: List<Int>,
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int,
        requiredStudyMinutes: Int
    ) {
        val newId = "rule_${System.currentTimeMillis()}"
        val chosenDays = if (daysOfWeek.isEmpty()) {
            listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY)
        } else daysOfWeek

        val newRule = AutoLockScheduleRule(
            id = newId,
            name = name.ifBlank { "Study Gate" },
            isEnabled = true,
            daysOfWeek = chosenDays,
            startHour = startHour,
            startMinute = startMinute,
            endHour = endHour,
            endMinute = endMinute,
            requiredStudyMinutes = requiredStudyMinutes.coerceAtLeast(15)
        )

        val now = Calendar.getInstance()
        val currentDay = now.get(Calendar.DAY_OF_WEEK)
        if (chosenDays.contains(currentDay)) {
            prefs.edit().putString(PREFIX_ACTIVATED_DATE + newId, getTodayDateKey()).apply()
        }

        val list = getRules().toMutableList().apply { add(newRule) }
        saveRules(list)
    }

    fun updateRule(updated: AutoLockScheduleRule, todayStudiedMinutes: Int = 0): Boolean {
        val existing = getRules().find { it.id == updated.id } ?: return false
        if (isRuleLockedForToday(existing, todayStudiedMinutes)) {
            val now = Calendar.getInstance()
            val currentDay = now.get(Calendar.DAY_OF_WEEK)
            if (!updated.isEnabled || !updated.daysOfWeek.contains(currentDay) || updated.requiredStudyMinutes < existing.requiredStudyMinutes) {
                return false
            }
        }
        val current = getRules().map {
            if (it.id == updated.id) updated else it
        }
        saveRules(current)
        return true
    }

    fun deleteRule(id: String, todayStudiedMinutes: Int = 0): Boolean {
        val rule = getRules().find { it.id == id } ?: return false
        if (isRuleLockedForToday(rule, todayStudiedMinutes)) {
            return false // Strict commitment: Refuse to delete!
        }
        val current = getRules().filterNot { it.id == id }
        saveRules(current)
        prefs.edit().remove(PREFIX_ACTIVATED_DATE + id).apply()
        return true
    }

    /**
     * Checks if any rule is active right now and if the study requirement has not yet been satisfied today.
     */
    fun getActiveRule(todayStudiedMinutes: Int): ActiveAutoLockStatus? {
        val rules = getRules().filter { it.isEnabled }
        val now = Calendar.getInstance()
        val currentDay = now.get(Calendar.DAY_OF_WEEK)
        val currentMinutesOfDay = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        for (rule in rules) {
            if (!rule.daysOfWeek.contains(currentDay)) continue

            val ruleStartMinutes = rule.startHour * 60 + rule.startMinute
            val ruleEndMinutes = rule.endHour * 60 + rule.endMinute

            val inWindow = if (ruleEndMinutes >= ruleStartMinutes) {
                currentMinutesOfDay in ruleStartMinutes..ruleEndMinutes
            } else {
                // Crosses midnight
                currentMinutesOfDay >= ruleStartMinutes || currentMinutesOfDay <= ruleEndMinutes
            }

            if (inWindow) {
                if (todayStudiedMinutes < rule.requiredStudyMinutes) {
                    val remaining = rule.requiredStudyMinutes - todayStudiedMinutes
                    return ActiveAutoLockStatus(
                        rule = rule,
                        remainingStudyMinutes = remaining.coerceAtLeast(1),
                        completedStudyMinutes = todayStudiedMinutes
                    )
                }
            }
        }
        return null
    }
}
