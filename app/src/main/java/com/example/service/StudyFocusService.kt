package com.example.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.StudyGuardApp
import com.example.util.EmergencyLockManager
import com.example.util.StudyPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class StudyFocusService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var timerJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, ACTION_RESUME -> {
                startForeground(NOTIFICATION_ID, buildNotification("Focusing...", "Study session in progress"))
                startTimerLoop()
            }
            ACTION_STOP -> {
                if (EmergencyLockManager.getInstance(applicationContext).isNuclearLockActive()) {
                    return START_STICKY
                }
                stopTimerLoop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startTimerLoop() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (isActive) {
                val endTime = StudyPreferences.getSessionEndTime(applicationContext)
                val now = System.currentTimeMillis()
                val remainingMillis = endTime - now

                if (endTime > 0L && remainingMillis <= 0L) {
                    onSessionComplete()
                    break
                }

                if (!StudyPreferences.isStudyActive(applicationContext)) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    break
                }

                val minutes = (remainingMillis / 1000) / 60
                val seconds = (remainingMillis / 1000) % 60
                val timeString = String.format("%02d:%02d", minutes, seconds)
                val subject = StudyPreferences.getSessionSubject(applicationContext)

                val isNuclear = EmergencyLockManager.getInstance(applicationContext).isNuclearLockActive()
                val notification = if (isNuclear) {
                    val status = EmergencyLockManager.getInstance(applicationContext).checkStatus()
                    buildNotification(
                        title = "☢️ Total Lockout: ${status.formattedRemaining} remaining",
                        contentText = "Phone is completely locked to focus. Unstoppable until 00:00."
                    )
                } else {
                    buildNotification(
                        title = "📚 $subject: $timeString remaining",
                        contentText = "Distraction apps locked. YouTube Shorts protected."
                    )
                }
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                manager.notify(NOTIFICATION_ID, notification)

                checkForegroundAppWatchdog()

                delay(1000L)
            }
        }
    }

    private fun checkForegroundAppWatchdog() {
        try {
            val emergencyLock = EmergencyLockManager.getInstance(applicationContext)
            val isNuclearBlockActive = emergencyLock.isNuclearBlockAppsActive()
            val isStudyActive = StudyPreferences.isStudyActive(applicationContext) || isNuclearBlockActive
            if (!isStudyActive) return

            val fgPkg = com.example.util.UsageStatsManagerHelper.getForegroundPackageName(applicationContext) ?: return
            if (fgPkg == packageName || fgPkg == "com.android.systemui" || fgPkg.contains("launcher")) {
                return
            }

            val isDistraction = com.example.data.repository.StudyRepository.isPackageDistraction(fgPkg)
            if (isDistraction) {
                val appName = try {
                    val appInfo = packageManager.getApplicationInfo(fgPkg, 0)
                    packageManager.getApplicationLabel(appInfo).toString()
                } catch (e: Exception) {
                    fgPkg
                }
                val intent = Intent(applicationContext, com.example.ui.lock.BlockedOverlayActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                    putExtra(com.example.ui.lock.BlockedOverlayActivity.EXTRA_BLOCKED_APP_NAME, appName)
                    putExtra(com.example.ui.lock.BlockedOverlayActivity.EXTRA_IS_NUCLEAR_LOCK, isNuclearBlockActive)
                    putExtra(com.example.ui.lock.BlockedOverlayActivity.EXTRA_RULE_NAME, "Study Focus Lock")
                }
                applicationContext.startActivity(intent)
            }
        } catch (e: Exception) {
            // Guard against background inspection exceptions
        }
    }

    private fun onSessionComplete() {
        val subject = StudyPreferences.getSessionSubject(applicationContext)
        val sessionId = StudyPreferences.getCurrentSessionId(applicationContext)
        val startTime = StudyPreferences.getSessionStartTime(applicationContext)
        val plannedMinutes = StudyPreferences.getPlannedMinutes(applicationContext)
        val elapsedMinutes = (((System.currentTimeMillis() - startTime) / 60000L).toInt())
        val actualMinutes = elapsedMinutes.coerceAtLeast(plannedMinutes).coerceAtLeast(1)

        serviceScope.launch(Dispatchers.IO) {
            StudyGuardApp.instance.repository.completeSession(
                sessionId = sessionId,
                actualMinutes = actualMinutes,
                distractionsIntercepted = 0
            )
        }

        StudyPreferences.stopStudySession(applicationContext)
        triggerCompletionFeedback()

        val completionNotification = NotificationCompat.Builder(this, StudyGuardApp.CHANNEL_ID_STUDY_TIMER)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("🎉 Study Session Complete!")
            .setContentText("Well done! You stayed disciplined and conquered '$subject'.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID + 1, completionNotification)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun triggerCompletionFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                val vibrator = vibratorManager.defaultVibrator
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 300, 100, 500), -1))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 300, 100, 500), -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(500)
                }
            }
        } catch (e: Exception) {
            // Ignore if vibration not permitted or not supported
        }
    }

    private fun stopTimerLoop() {
        timerJob?.cancel()
        timerJob = null
    }

    private fun buildNotification(title: String, contentText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, StudyGuardApp.CHANNEL_ID_STUDY_TIMER)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(contentText)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTimerLoop()
    }

    companion object {
        const val ACTION_START = "com.example.service.ACTION_START"
        const val ACTION_STOP = "com.example.service.ACTION_STOP"
        const val ACTION_RESUME = "com.example.service.ACTION_RESUME"
        const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, StudyFocusService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, StudyFocusService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
