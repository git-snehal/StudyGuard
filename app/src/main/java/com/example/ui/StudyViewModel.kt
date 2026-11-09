package com.example.ui

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.StudyGuardApp
import com.example.data.model.AchievementEntity
import com.example.data.model.BlockedAppEntity
import com.example.data.model.DistractionEvent
import com.example.data.model.PlaylistEntity
import com.example.data.model.StudyMusicTrack
import com.example.data.model.StudySession
import com.example.data.repository.StudyRepository
import com.example.receiver.StudyDeviceAdminReceiver
import com.example.service.FloatingFocusWidgetService
import com.example.service.StudyFocusService
import com.example.util.AppUsageInfo
import com.example.util.AudioPlayerState
import com.example.util.InstalledAppsHelper
import com.example.util.MarathonConfig
import com.example.util.MarathonPhase
import com.example.util.MarathonState
import com.example.util.SmartReminderItem
import com.example.util.StudyAudioPlayer
import com.example.util.StudyPreferences
import com.example.util.StudyReminderManager
import com.example.util.StudySyncManager
import com.example.util.UsageStatsManagerHelper
import com.example.util.ChatMessage
import com.example.util.CoachRole
import com.example.util.MessageSender
import com.example.util.GeminiStudyCoach
import com.example.util.AiCoachResult
import com.example.widget.StudyGuardAppWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StudyUiState(
    val isStudyActive: Boolean = false,
    val remainingSeconds: Long = 0L,
    val currentSubject: String = "Focused Study",
    val plannedMinutes: Int = 25,
    val isAccessibilityEnabled: Boolean = false,
    val isDeviceAdminEnabled: Boolean = false,
    val isUsageStatsEnabled: Boolean = false,
    val isOverlayEnabled: Boolean = false,
    val isNotificationAccessEnabled: Boolean = false,
    val isFloatingWidgetActive: Boolean = false,
    val isYouTubeShortsBlocked: Boolean = true,
    val isStrictUninstallLockEnabled: Boolean = true,
    val searchQuery: String = "",
    val selectedCategoryFilter: String = "All",
    val todayScreenTimeMs: Long = 0L,
    val todayDistractionTimeMs: Long = 0L,
    val usageStatsList: List<AppUsageInfo> = emptyList(),
    val currentThemeMode: StudyPreferences.ThemeMode = StudyPreferences.ThemeMode.SYSTEM,
    val isNuclearLockActive: Boolean = false,
    val isNuclearMarathonActive: Boolean = false,
    val isNuclearMarathonBreak: Boolean = false,
    val nuclearRemainingSeconds: Long = 0L,
    val nuclearFormattedRemaining: String = "00:00",
    val currentSessionBlockedNotifications: Int = 0,
    val showNotificationReportDialog: Boolean = false,
    val latestNotificationReport: com.example.util.NotificationShieldReport? = null,
    val showCelebrationConfetti: Boolean = false
)

class StudyViewModel(
    private val repository: StudyRepository,
    private val context: Context
) : ViewModel() {

    private val emergencyLockManager = com.example.util.EmergencyLockManager.getInstance(context)
    private val appLimitManager = com.example.util.AppLimitManager.getInstance(context)
    val appLimitsMap: StateFlow<Map<String, com.example.util.AppLimitItemState>> = appLimitManager.limitsFlow
    val notificationBlockReportManager = com.example.util.NotificationBlockReportManager.getInstance(context)
    val lastNotificationReport: StateFlow<com.example.util.NotificationShieldReport?> = notificationBlockReportManager.lastReportFlow

    fun dismissNotificationReportDialog() {
        _uiState.value = _uiState.value.copy(showNotificationReportDialog = false)
    }

    fun showNotificationReportDialog() {
        _uiState.value = _uiState.value.copy(showNotificationReportDialog = true)
    }

    fun openNotificationSettings() {
        com.example.util.NotificationBlockReportManager.openNotificationAccessSettings(context)
    }

    fun showToast(msg: String) {
        viewModelScope.launch(Dispatchers.Main) {
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private val _uiState = MutableStateFlow(
        StudyUiState(
            isStudyActive = StudyPreferences.isStudyActive(context),
            isYouTubeShortsBlocked = StudyPreferences.isBlockYouTubeShortsEnabled(context),
            isStrictUninstallLockEnabled = StudyPreferences.isStrictUninstallLockEnabled(context),
            currentThemeMode = StudyPreferences.getThemeMode(context)
        )
    )
    val uiState: StateFlow<StudyUiState> = _uiState.asStateFlow()

    val allApps: StateFlow<List<BlockedAppEntity>> = repository.allApps
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allSessions: StateFlow<List<StudySession>> = repository.allSessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalDistractionsCount: StateFlow<Int> = repository.totalDistractionsCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val shortsBlockedCount: StateFlow<Int> = repository.shortsBlockedCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val recentDistractions: StateFlow<List<DistractionEvent>> = repository.recentDistractions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allMusicTracks: StateFlow<List<StudyMusicTrack>> = repository.allMusicTracks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allAchievements: StateFlow<List<AchievementEntity>> = repository.allAchievements
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allPlaylists: StateFlow<List<PlaylistEntity>> = repository.allPlaylists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val syncManager = StudySyncManager(context)
    val syncPairingCode: String = syncManager.getSyncPairingCode()
    private val _lastSyncFormatted = MutableStateFlow(syncManager.getLastSyncFormatted())
    val lastSyncFormatted: StateFlow<String> = _lastSyncFormatted.asStateFlow()
    private val _laptopStats = MutableStateFlow(syncManager.getLaptopStats())
    val laptopStats: StateFlow<com.example.util.LaptopStats> = _laptopStats.asStateFlow()

    // Smart Reminders
    val reminderManager = StudyReminderManager(context)
    private val _remindersList = MutableStateFlow(reminderManager.getReminders())
    val remindersList: StateFlow<List<SmartReminderItem>> = _remindersList.asStateFlow()

    // Auto-Lock Schedule Manager (Study-to-Unlock Gate)
    val autoLockManager = com.example.util.ScheduledAutoLockManager(context)
    private val _autoLockRules = MutableStateFlow(autoLockManager.getRules())
    val autoLockRules: StateFlow<List<com.example.util.AutoLockScheduleRule>> = _autoLockRules.asStateFlow()
    private val _activeAutoLockStatus = MutableStateFlow<com.example.util.ActiveAutoLockStatus?>(null)
    val activeAutoLockStatus: StateFlow<com.example.util.ActiveAutoLockStatus?> = _activeAutoLockStatus.asStateFlow()

    // Dark/Light Theme Mode
    private val _themeMode = MutableStateFlow(StudyPreferences.getThemeMode(context))
    val themeMode: StateFlow<StudyPreferences.ThemeMode> = _themeMode.asStateFlow()

    // Daily Deep Work Target
    private val _dailyGoalMinutes = MutableStateFlow(StudyPreferences.getDailyGoalMinutes(context))
    val dailyGoalMinutes: StateFlow<Int> = _dailyGoalMinutes.asStateFlow()

    fun setDailyGoalMinutes(minutes: Int) {
        val target = minutes.coerceIn(15, 1440)
        StudyPreferences.setDailyGoalMinutes(context, target)
        _dailyGoalMinutes.value = target
        val hours = target / 60
        val mins = target % 60
        val label = if (hours > 0 && mins > 0) "${hours}h ${mins}m" else if (hours > 0) "${hours}h" else "${mins}m"
        showToast("🎯 Daily Deep Work Target updated to $label!")
    }

    // AI Study Coach & Multi-turn Chat State
    private val _aiCoachResponse = MutableStateFlow<String?>(null)
    val aiCoachResponse: StateFlow<String?> = _aiCoachResponse.asStateFlow()
    private val _isAiLoading = MutableStateFlow(false)
    val isAiLoading: StateFlow<Boolean> = _isAiLoading.asStateFlow()
    private val _isLiveGemini = MutableStateFlow(false)
    val isLiveGemini: StateFlow<Boolean> = _isLiveGemini.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(
        listOf(
            ChatMessage(
                sender = MessageSender.AI,
                text = "👋 Hello! I'm your StudyGuard AI Coach. Ask me to generate a personalized study timetable, provide emergency anti-procrastination tactics, or quiz you on any subject!",
                isLiveApi = true
            )
        )
    )
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _selectedCoachRole = MutableStateFlow(CoachRole.GENERAL)
    val selectedCoachRole: StateFlow<CoachRole> = _selectedCoachRole.asStateFlow()

    // Long Study Hours Marathon Engine
    private val _marathonState = MutableStateFlow(MarathonState())
    val marathonState: StateFlow<MarathonState> = _marathonState.asStateFlow()
    private var marathonJob: Job? = null

    val playerState: StateFlow<AudioPlayerState> = StudyAudioPlayer.playerState

    init {
        viewModelScope.launch {
            repository.syncInstalledAppsIfNeeded()
        }
        if (StudyPreferences.hasActiveSessionExpired(context)) {
            completeCurrentSession()
        }
        viewModelScope.launch {
            notificationBlockReportManager.currentSessionBlockedCount.collect { count ->
                _uiState.value = _uiState.value.copy(currentSessionBlockedNotifications = count)
            }
        }
        viewModelScope.launch {
            notificationBlockReportManager.lastReportFlow.collect { report ->
                _uiState.value = _uiState.value.copy(latestNotificationReport = report)
            }
        }
        startLocalTimerLoop()
        checkPermissions(context)
        refreshUsageStats()
        refreshAutoLockStatus()
    }

    fun setThemeMode(mode: StudyPreferences.ThemeMode) {
        StudyPreferences.setThemeMode(context, mode)
        _themeMode.value = mode
        _uiState.value = _uiState.value.copy(currentThemeMode = mode)
    }

    fun toggleThemeMode() {
        val next = when (_themeMode.value) {
            StudyPreferences.ThemeMode.SYSTEM -> StudyPreferences.ThemeMode.MIDNIGHT_OLED
            StudyPreferences.ThemeMode.MIDNIGHT_OLED -> StudyPreferences.ThemeMode.COZY_LOFI
            StudyPreferences.ThemeMode.COZY_LOFI -> StudyPreferences.ThemeMode.FOREST_ZEN
            StudyPreferences.ThemeMode.FOREST_ZEN -> StudyPreferences.ThemeMode.DARK
            StudyPreferences.ThemeMode.DARK -> StudyPreferences.ThemeMode.LIGHT
            StudyPreferences.ThemeMode.LIGHT -> StudyPreferences.ThemeMode.SYSTEM
        }
        setThemeMode(next)
    }

    fun activateNuclearLock(minutes: Int) {
        emergencyLockManager.activateNuclearLock(minutes)
        startSession("☢️ Nuclear Total Lockout", minutes)
        toggleStrictUninstallLock(true)
        refreshNuclearLock()
    }

    fun activateNuclearMarathon(config: MarathonConfig) {
        emergencyLockManager.activateNuclearMarathon(config)
        toggleStrictUninstallLock(true)
        startMarathon(config, isNuclear = true)
        refreshNuclearLock()
    }

    fun refreshNuclearLock() {
        val status = emergencyLockManager.checkStatus()
        _uiState.value = _uiState.value.copy(
            isNuclearLockActive = status.isActive,
            isNuclearMarathonActive = status.isMarathon,
            isNuclearMarathonBreak = status.isBreakPhase,
            nuclearRemainingSeconds = status.remainingSeconds,
            nuclearFormattedRemaining = status.formattedRemaining
        )
    }

    fun dismissConfetti() {
        _uiState.value = _uiState.value.copy(showCelebrationConfetti = false)
    }

    fun triggerConfetti() {
        _uiState.value = _uiState.value.copy(showCelebrationConfetti = true)
    }

    fun getTodayStudyMinutes(): Int {
        val now = System.currentTimeMillis()
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val startOfDay = cal.timeInMillis

        val completedToday = allSessions.value
            .filter { it.startTime >= startOfDay }
            .sumOf { it.actualMinutes }

        val activeElapsed = if (uiState.value.isStudyActive) {
            val startTime = StudyPreferences.getSessionStartTime(context)
            if (startTime > 0L) {
                ((now - startTime) / (60 * 1000L)).toInt().coerceAtLeast(0)
            } else 0
        } else 0

        return completedToday + activeElapsed
    }

    fun refreshAutoLockStatus() {
        val todayMins = getTodayStudyMinutes()
        _activeAutoLockStatus.value = autoLockManager.getActiveRule(todayMins)
    }

    private fun startLocalTimerLoop() {
        viewModelScope.launch {
            while (true) {
                refreshNuclearLock()
                val active = StudyPreferences.isStudyActive(context)
                if (active) {
                    val endTime = StudyPreferences.getSessionEndTime(context)
                    val now = System.currentTimeMillis()
                    val left = ((endTime - now) / 1000L).coerceAtLeast(0L)
                    val subject = StudyPreferences.getSessionSubject(context)

                    if (endTime > 0L && left <= 0L) {
                        completeCurrentSession()
                    } else {
                        _uiState.value = _uiState.value.copy(
                            isStudyActive = true,
                            remainingSeconds = left,
                            currentSubject = subject
                        )
                    }
                } else {
                    _uiState.value = _uiState.value.copy(
                        isStudyActive = false,
                        remainingSeconds = 0L
                    )
                }

                delay(1000L)
            }
        }
    }

    fun completeCurrentSession() {
        viewModelScope.launch {
            triggerConfetti()
            val sessionId = StudyPreferences.getCurrentSessionId(context)
            val startTime = StudyPreferences.getSessionStartTime(context)
            val plannedMinutes = StudyPreferences.getPlannedMinutes(context)
            val elapsedMinutes = (((System.currentTimeMillis() - startTime) / 60000L).toInt())
            val actualMinutes = elapsedMinutes.coerceAtLeast(plannedMinutes).coerceAtLeast(1)

            repository.completeSession(sessionId, actualMinutes, distractionsIntercepted = 0)
            StudyPreferences.stopStudySession(context)
            StudyFocusService.stop(context)
            val report = notificationBlockReportManager.finishSession()
            _uiState.value = _uiState.value.copy(
                isStudyActive = false,
                remainingSeconds = 0L,
                latestNotificationReport = report,
                showNotificationReportDialog = (report != null && report.totalBlockedCount > 0)
            )
            StudyGuardAppWidget.updateAllWidgets(context)
        }
    }

    fun checkPermissions(ctx: Context) {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(ctx, StudyDeviceAdminReceiver::class.java)
        val isAdminActive = dpm.isAdminActive(adminComponent)

        val accessibilityEnabled = isAccessibilityServiceEnabled(ctx)
        val usageStatsGranted = UsageStatsManagerHelper.hasUsageStatsPermission(ctx)
        val overlayGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(ctx)
        } else true
        val notificationAccessGranted = com.example.util.NotificationBlockReportManager.isNotificationAccessGranted(ctx)

        _uiState.value = _uiState.value.copy(
            isDeviceAdminEnabled = isAdminActive,
            isAccessibilityEnabled = accessibilityEnabled,
            isUsageStatsEnabled = usageStatsGranted,
            isOverlayEnabled = overlayGranted,
            isNotificationAccessEnabled = notificationAccessGranted
        )

        refreshUsageStats()
    }

    fun refreshUsageStats() {
        viewModelScope.launch {
            val usageList = withContext(Dispatchers.IO) {
                UsageStatsManagerHelper.getTodayAppUsageList(context)
            }
            val totalTime = usageList.sumOf { it.totalTimeInForegroundMs }
            val distractionTime = usageList.filter { it.isDistraction }.sumOf { it.totalTimeInForegroundMs }

            _uiState.value = _uiState.value.copy(
                usageStatsList = usageList,
                todayScreenTimeMs = totalTime,
                todayDistractionTimeMs = distractionTime
            )
        }
    }

    private fun isAccessibilityServiceEnabled(ctx: Context): Boolean {
        return try {
            val enabledServices = Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            enabledServices.contains(ctx.packageName)
        } catch (e: Exception) {
            false
        }
    }

    fun toggleStudyTimer(subject: String = "Focused Study", minutes: Int = 25) {
        if (_uiState.value.isNuclearLockActive) {
            return
        }
        if (_uiState.value.isStudyActive) {
            stopSession()
        } else {
            startSession(subject, minutes)
        }
    }

    fun startSession(subject: String, minutes: Int) {
        viewModelScope.launch {
            val sub = subject.ifBlank { "Deep Study Session" }
            repository.startSession(sub, minutes)
            StudyFocusService.start(context)
            notificationBlockReportManager.startSession(sub)
            _uiState.value = _uiState.value.copy(
                isStudyActive = true,
                currentSubject = sub,
                plannedMinutes = minutes,
                remainingSeconds = minutes * 60L
            )
            StudyGuardAppWidget.updateAllWidgets(context)
        }
    }

    fun stopSession() {
        if (_uiState.value.isNuclearLockActive) {
            showToast("☢️ Nuclear Lock is active! You cannot stop or cancel this session.")
            return
        }
        viewModelScope.launch {
            val sessionId = StudyPreferences.getCurrentSessionId(context)
            val startTime = StudyPreferences.getSessionStartTime(context)
            val elapsedMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
            // Even if stopped early, credit actual studied minutes!
            // Minimum 1 minute credited so study effort is never lost even on short sessions/tests
            val actualMinutes = ((elapsedMs + 55_000L) / 60_000L).toInt().coerceAtLeast(1)
            repository.completeSession(sessionId, actualMinutes, 0)
            StudyPreferences.stopStudySession(context)
            StudyFocusService.stop(context)
            val report = notificationBlockReportManager.finishSession()
            _uiState.value = _uiState.value.copy(
                isStudyActive = false,
                remainingSeconds = 0L,
                latestNotificationReport = report,
                showNotificationReportDialog = (report != null && report.totalBlockedCount > 0)
            )
            StudyGuardAppWidget.updateAllWidgets(context)
        }
    }

    fun addManualStudySession(subject: String = "Focused Study", minutes: Int) {
        viewModelScope.launch {
            repository.addManualStudySession(subject, minutes)
        }
    }

    fun deleteSession(session: StudySession) {
        viewModelScope.launch {
            repository.deleteSession(session)
        }
    }

    fun clearAllSessions() {
        viewModelScope.launch {
            repository.clearAllSessions()
        }
    }

    // Music Player & Saver
    fun loadDefaultMusicPresets() {
        viewModelScope.launch(Dispatchers.IO) {
            val presets = StudyAudioPlayer.getDefaultPresets()
            presets.forEach { repository.saveMusicTrack(it) }
        }
    }

    fun saveAudioFileFromUri(uri: Uri, customTitle: String, category: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val savedTrack = StudyAudioPlayer.saveAudioFileFromUri(
                context = context,
                sourceUri = uri,
                customTitle = customTitle,
                category = category
            )
            if (savedTrack != null) {
                repository.saveMusicTrack(savedTrack)
            }
        }
    }

    fun playTrack(track: StudyMusicTrack) {
        StudyAudioPlayer.playTrack(context, track)
        viewModelScope.launch {
            repository.recordMusicPlayedOrSaved()
        }
    }

    fun togglePlayPauseAudio() {
        StudyAudioPlayer.togglePlayPause(context)
    }

    fun stopAudio() {
        StudyAudioPlayer.stop()
    }

    fun toggleLoopAudio() {
        StudyAudioPlayer.toggleLoop()
    }

    fun toggleShuffleAudio() {
        StudyAudioPlayer.toggleShuffle()
    }

    fun toggleMusicFavorite(trackId: Long, isFavorite: Boolean) {
        viewModelScope.launch {
            repository.toggleMusicFavorite(trackId, isFavorite)
        }
    }

    fun deleteMusicTrack(track: StudyMusicTrack) {
        viewModelScope.launch {
            if (playerState.value.currentTrack?.id == track.id) {
                StudyAudioPlayer.stop()
            }
            repository.deleteMusicTrack(track)
        }
    }

    // Playlist Operations
    fun createPlaylist(name: String, description: String = "", colorHex: String = "#6366F1") {
        viewModelScope.launch {
            repository.createPlaylist(name, description, colorHex)
        }
    }

    fun deletePlaylist(playlist: PlaylistEntity) {
        viewModelScope.launch {
            repository.deletePlaylist(playlist)
        }
    }

    fun addTrackToPlaylist(playlistId: Long, trackId: Long) {
        viewModelScope.launch {
            repository.addTrackToPlaylist(playlistId, trackId)
        }
    }

    fun removeTrackFromPlaylist(playlistId: Long, trackId: Long) {
        viewModelScope.launch {
            repository.removeTrackFromPlaylist(playlistId, trackId)
        }
    }

    fun getTracksForPlaylist(playlistId: Long) = repository.getTracksForPlaylist(playlistId)

    fun playPlaylist(tracks: List<StudyMusicTrack>, isShuffle: Boolean = false) {
        if (tracks.isNotEmpty()) {
            StudyAudioPlayer.playPlaylist(context, tracks, isShuffle = isShuffle)
            viewModelScope.launch {
                repository.recordMusicPlayedOrSaved()
            }
        }
    }

    fun playPlaylistById(playlistId: Long, isShuffle: Boolean = false) {
        viewModelScope.launch {
            val tracks = repository.getTracksForPlaylist(playlistId).first()
            if (tracks.isNotEmpty()) {
                StudyAudioPlayer.playPlaylist(context, tracks, isShuffle = isShuffle)
                repository.recordMusicPlayedOrSaved()
            }
        }
    }

    // Sync Operations
    fun triggerSyncNow(onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            delay(500) // Realistic handshake simulation
            syncManager.recordSyncEvent()
            _laptopStats.value = syncManager.getLaptopStats()
            _lastSyncFormatted.value = syncManager.getLastSyncFormatted()
            onComplete(true)
        }
    }

    fun exportSyncData(): String {
        val active = uiState.value.isStudyActive
        val subject = uiState.value.currentSubject
        val rem = (uiState.value.remainingSeconds / 60).toInt()
        val totalMins = allSessions.value.sumOf { it.actualMinutes }.toLong()
        val distractions = totalDistractionsCount.value
        val sessionsCount = allSessions.value.count { it.actualMinutes > 0 || it.isCompleted }
        val domains = listOf("youtube.com/shorts", "instagram.com", "tiktok.com", "reddit.com", "twitter.com", "netflix.com", "twitch.tv")

        return syncManager.exportSyncJson(
            isStudyActive = active,
            activeSubject = subject,
            remainingMinutes = rem,
            blockedDomains = domains,
            phoneMinutes = totalMins,
            phoneDistractions = distractions,
            phoneSessions = sessionsCount
        )
    }

    fun importSyncData(json: String): Boolean {
        val success = syncManager.importSyncData(json)
        if (success) {
            _laptopStats.value = syncManager.getLaptopStats()
            _lastSyncFormatted.value = syncManager.getLastSyncFormatted()
        }
        return success
    }

    fun updateLaptopStats(minutes: Long, sessions: Int, distractions: Int, deviceName: String = "Google Chrome (Laptop)") {
        syncManager.updateLaptopStats(minutes, sessions, distractions, deviceName)
        _laptopStats.value = syncManager.getLaptopStats()
        _lastSyncFormatted.value = syncManager.getLastSyncFormatted()
    }

    fun quickAddLaptopStudy(minutes: Long) {
        syncManager.addLaptopStudyMinutes(minutes)
        _laptopStats.value = syncManager.getLaptopStats()
        _lastSyncFormatted.value = syncManager.getLastSyncFormatted()
    }

    // Smart Reminders Operations
    fun toggleReminder(id: String, isEnabled: Boolean) {
        reminderManager.toggleReminder(id, isEnabled)
        _remindersList.value = reminderManager.getReminders()
    }

    fun updateReminderTime(id: String, hour: Int, minute: Int) {
        reminderManager.updateReminderTime(id, hour, minute)
        _remindersList.value = reminderManager.getReminders()
    }

    fun addCustomReminder(title: String, hour: Int, minute: Int, message: String, iconTag: String) {
        reminderManager.addReminder(title, hour, minute, message, iconTag)
        _remindersList.value = reminderManager.getReminders()
    }

    fun deleteReminder(id: String) {
        reminderManager.deleteReminder(id)
        _remindersList.value = reminderManager.getReminders()
    }

    fun sendTestReminder(reminder: SmartReminderItem) {
        reminderManager.sendTestReminder(reminder)
    }

    fun isRuleLockedForToday(rule: com.example.util.AutoLockScheduleRule): Boolean {
        return autoLockManager.isRuleLockedForToday(rule, getTodayStudyMinutes())
    }

    fun canModifyAutoLockRule(rule: com.example.util.AutoLockScheduleRule): Pair<Boolean, String> {
        return autoLockManager.canModifyRule(rule, getTodayStudyMinutes())
    }

    // Auto-Lock Gate (Study-to-Unlock) Operations
    fun addAutoLockRule(
        name: String,
        days: List<Int>,
        startH: Int,
        startM: Int,
        endH: Int,
        endM: Int,
        studyMins: Int
    ) {
        autoLockManager.addRule(name, days, startH, startM, endH, endM, studyMins)
        _autoLockRules.value = autoLockManager.getRules()
        refreshAutoLockStatus()
    }

    fun toggleAutoLockRule(id: String, isEnabled: Boolean) {
        val rule = autoLockRules.value.find { it.id == id }
        val todayMins = getTodayStudyMinutes()
        if (rule != null && !isEnabled) {
            val (canModify, reason) = autoLockManager.canModifyRule(rule, todayMins)
            if (!canModify) {
                showToast(reason)
                return
            }
        }
        val success = autoLockManager.toggleRule(id, isEnabled, todayMins)
        if (!success) {
            showToast("🔒 Custom lockdown is set or active for today and cannot be turned down until tomorrow!")
        }
        _autoLockRules.value = autoLockManager.getRules()
        refreshAutoLockStatus()
    }

    fun deleteAutoLockRule(id: String) {
        val rule = autoLockRules.value.find { it.id == id }
        val todayMins = getTodayStudyMinutes()
        if (rule != null) {
            val (canModify, reason) = autoLockManager.canModifyRule(rule, todayMins)
            if (!canModify) {
                showToast(reason)
                return
            }
        }
        val success = autoLockManager.deleteRule(id, todayMins)
        if (!success) {
            showToast("🔒 Cannot delete a custom lockdown schedule set or running for today!")
        }
        _autoLockRules.value = autoLockManager.getRules()
        refreshAutoLockStatus()
    }

    fun updateAutoLockRule(rule: com.example.util.AutoLockScheduleRule) {
        val todayMins = getTodayStudyMinutes()
        val (canModify, reason) = autoLockManager.canModifyRule(rule, todayMins)
        if (!canModify) {
            showToast(reason)
            return
        }
        val success = autoLockManager.updateRule(rule, todayMins)
        if (!success) {
            showToast("🔒 Custom lockdown scheduled for today cannot be edited to bypass today's lock!")
        }
        _autoLockRules.value = autoLockManager.getRules()
        refreshAutoLockStatus()
    }

    // AI Study Coach Multi-turn Chat
    fun setCoachRole(role: CoachRole) {
        _selectedCoachRole.value = role
    }

    fun sendChatMessage(text: String) {
        val prompt = text.trim()
        if (prompt.isBlank()) return

        val userMsg = ChatMessage(sender = MessageSender.USER, text = prompt)
        _chatMessages.value = _chatMessages.value + userMsg

        viewModelScope.launch {
            _isAiLoading.value = true
            val role = _selectedCoachRole.value
            val history = _chatMessages.value.dropLast(1)
            when (val result = GeminiStudyCoach.sendChatMessage(history, prompt, role)) {
                is AiCoachResult.Success -> {
                    val aiMsg = ChatMessage(
                        sender = MessageSender.AI,
                        text = result.responseText,
                        isLiveApi = result.isLiveApi
                    )
                    _chatMessages.value = _chatMessages.value + aiMsg
                    _aiCoachResponse.value = result.responseText
                    _isLiveGemini.value = result.isLiveApi
                }
                is AiCoachResult.Error -> {
                    val errorMsg = ChatMessage(
                        sender = MessageSender.AI,
                        text = "AI Note: ${result.message}",
                        isLiveApi = false
                    )
                    _chatMessages.value = _chatMessages.value + errorMsg
                    _aiCoachResponse.value = errorMsg.text
                    _isLiveGemini.value = false
                }
            }
            _isAiLoading.value = false
        }
    }

    fun clearChat() {
        _chatMessages.value = listOf(
            ChatMessage(
                sender = MessageSender.AI,
                text = "✨ Chat reset! Ready to crush your next study session.",
                isLiveApi = true
            )
        )
    }

    fun askAiCoach(prompt: String) {
        sendChatMessage(prompt)
    }

    // Custom App Blocker & Lock Simulator
    fun addCustomBlockedApp(packageName: String, appName: String, category: String) {
        if (emergencyLockManager.isNuclearLockActive()) {
            showToast("☢️ Nuclear Strict Focus Lock is active! Cannot add custom apps during lockdown.")
            return
        }
        viewModelScope.launch {
            repository.addCustomApp(packageName, appName, category, isDistraction = true)
        }
    }

    fun simulateDistractionLock(appName: String) {
        val todayMins = getTodayStudyMinutes()
        val activeSchedule = autoLockManager.getActiveRule(todayMins)
        val isScheduled = activeSchedule != null && !uiState.value.isStudyActive

        val intent = android.content.Intent(context, com.example.ui.lock.BlockedOverlayActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(com.example.ui.lock.BlockedOverlayActivity.EXTRA_BLOCKED_APP_NAME, appName)
            putExtra(com.example.ui.lock.BlockedOverlayActivity.EXTRA_IS_SCHEDULED_LOCK, isScheduled)
            putExtra(com.example.ui.lock.BlockedOverlayActivity.EXTRA_RULE_NAME, activeSchedule?.rule?.name ?: "Study-to-Unlock Gate")
            putExtra(com.example.ui.lock.BlockedOverlayActivity.EXTRA_REMAINING_STUDY_MINS, activeSchedule?.remainingStudyMinutes ?: 60)
            putExtra(com.example.ui.lock.BlockedOverlayActivity.EXTRA_REQUIRED_STUDY_MINS, activeSchedule?.rule?.requiredStudyMinutes ?: 120)
            putExtra(com.example.ui.lock.BlockedOverlayActivity.EXTRA_STUDIED_MINS, todayMins)
        }
        context.startActivity(intent)
    }

    // Long Study Hours Marathon Engine (e.g. 45 min study / 15 min break x 4 cycles)
    fun startMarathon(config: MarathonConfig, isNuclear: Boolean = false) {
        marathonJob?.cancel()
        _marathonState.value = MarathonState(
            isActive = true,
            isPaused = false,
            isNuclear = isNuclear,
            config = config,
            currentCycle = 1,
            currentPhase = MarathonPhase.STUDY,
            phaseRemainingSeconds = config.studyDurationMinutes * 60L,
            totalRemainingSeconds = config.totalMinutes * 60L,
            completedStudyMinutes = 0
        )

        if (isNuclear) {
            emergencyLockManager.activateNuclearMarathon(config)
            _uiState.value = _uiState.value.copy(
                isNuclearLockActive = true,
                isNuclearMarathonActive = true,
                isNuclearMarathonBreak = false
            )
        }

        // Arm shields and start focus service for study cycle 1
        startSession("${config.title} (Cycle 1)", config.studyDurationMinutes)

        marathonJob = viewModelScope.launch {
            while (true) {
                delay(1000L)
                val current = _marathonState.value
                if (!current.isActive) break
                if (current.isPaused) continue

                val newPhaseSeconds = current.phaseRemainingSeconds - 1
                val newTotalSeconds = (current.totalRemainingSeconds - 1).coerceAtLeast(0)

                if (newPhaseSeconds <= 0) {
                    when (current.currentPhase) {
                        MarathonPhase.STUDY -> {
                            val newCompleted = current.completedStudyMinutes + current.config.studyDurationMinutes
                            completeCurrentSession()
                            if (current.currentCycle >= current.config.totalCycles) {
                                // Marathon complete!
                                if (current.isNuclear) {
                                    emergencyLockManager.completeNuclearMarathon()
                                    _uiState.value = _uiState.value.copy(
                                        isNuclearLockActive = false,
                                        isNuclearMarathonActive = false,
                                        isNuclearMarathonBreak = false
                                    )
                                }
                                _marathonState.value = current.copy(
                                    isActive = false,
                                    currentPhase = MarathonPhase.COMPLETED,
                                    phaseRemainingSeconds = 0,
                                    totalRemainingSeconds = 0,
                                    completedStudyMinutes = newCompleted
                                )
                                showToast("🏆 Focus Marathon Complete! Amazing perseverance!")
                                break
                            } else {
                                // Transition to break
                                if (current.isNuclear) {
                                    emergencyLockManager.setMarathonPhase(
                                        isBreak = true,
                                        cycle = current.currentCycle,
                                        phaseDurationMins = current.config.breakDurationMinutes
                                    )
                                    _uiState.value = _uiState.value.copy(
                                        isNuclearMarathonBreak = true
                                    )
                                }
                                _marathonState.value = current.copy(
                                    currentPhase = MarathonPhase.BREAK,
                                    phaseRemainingSeconds = current.config.breakDurationMinutes * 60L,
                                    totalRemainingSeconds = newTotalSeconds,
                                    completedStudyMinutes = newCompleted
                                )
                                // Temporarily relax study lock flag during break
                                StudyPreferences.setStudyActive(context, false)
                                _uiState.value = _uiState.value.copy(isStudyActive = false)
                                StudyGuardAppWidget.updateAllWidgets(context)
                                showToast("☕ Focus Cycle ${current.currentCycle} Done! Enjoy your break. Settings & blocked apps remain frozen.")
                            }
                        }
                        MarathonPhase.BREAK -> {
                            val nextCycle = current.currentCycle + 1
                            if (current.isNuclear) {
                                emergencyLockManager.setMarathonPhase(
                                    isBreak = false,
                                    cycle = nextCycle,
                                    phaseDurationMins = current.config.studyDurationMinutes
                                )
                                _uiState.value = _uiState.value.copy(
                                    isNuclearMarathonBreak = false
                                )
                            }
                            _marathonState.value = current.copy(
                                currentCycle = nextCycle,
                                currentPhase = MarathonPhase.STUDY,
                                phaseRemainingSeconds = current.config.studyDurationMinutes * 60L,
                                totalRemainingSeconds = newTotalSeconds
                            )
                            // Re-arm locks for next cycle
                            startSession("${current.config.title} (Cycle $nextCycle)", current.config.studyDurationMinutes)
                            showToast("🔔 Break Over! Cycle $nextCycle focus starting now.")
                        }
                        else -> break
                    }
                } else {
                    _marathonState.value = current.copy(
                        phaseRemainingSeconds = newPhaseSeconds,
                        totalRemainingSeconds = newTotalSeconds
                    )
                }
            }
        }
    }

    fun pauseMarathon() {
        if (_marathonState.value.isNuclear || emergencyLockManager.isNuclearLockActive()) {
            showToast("☢️ Nuclear Marathon is strictly locked! Pausing is prohibited.")
            return
        }
        _marathonState.value = _marathonState.value.copy(isPaused = true)
    }

    fun resumeMarathon() {
        _marathonState.value = _marathonState.value.copy(isPaused = false)
    }

    fun skipMarathonBreak() {
        val current = _marathonState.value
        if (current.isActive && current.currentPhase == MarathonPhase.BREAK) {
            val nextCycle = current.currentCycle + 1
            if (current.isNuclear) {
                emergencyLockManager.setMarathonPhase(
                    isBreak = false,
                    cycle = nextCycle,
                    phaseDurationMins = current.config.studyDurationMinutes
                )
                _uiState.value = _uiState.value.copy(
                    isNuclearMarathonBreak = false
                )
            }
            _marathonState.value = current.copy(
                currentCycle = nextCycle,
                currentPhase = MarathonPhase.STUDY,
                phaseRemainingSeconds = current.config.studyDurationMinutes * 60L
            )
            startSession("${current.config.title} (Cycle $nextCycle)", current.config.studyDurationMinutes)
            showToast("🚀 Skipped break! Resumed Cycle $nextCycle.")
        }
    }

    fun stopMarathon() {
        if (_marathonState.value.isNuclear || emergencyLockManager.isNuclearLockActive()) {
            showToast("☢️ Nuclear Marathon is strictly locked! Cannot stop until all cycles are completed.")
            return
        }
        marathonJob?.cancel()
        marathonJob = null
        _marathonState.value = _marathonState.value.copy(isActive = false, currentPhase = MarathonPhase.IDLE)
        stopSession()
    }

    fun toggleFloatingWidget(enable: Boolean) {
        if (enable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                return
            }
            FloatingFocusWidgetService.start(context)
            _uiState.value = _uiState.value.copy(isFloatingWidgetActive = true)
        } else {
            FloatingFocusWidgetService.stop(context)
            _uiState.value = _uiState.value.copy(isFloatingWidgetActive = false)
        }
    }

    fun toggleAppDistraction(packageName: String, isDistraction: Boolean) {
        if (emergencyLockManager.isNuclearLockActive()) {
            showToast("☢️ Nuclear Strict Focus Lock is active! App locklist is frozen.")
            return
        }
        viewModelScope.launch {
            repository.setAppDistraction(packageName, isDistraction)
        }
    }

    fun toggleYouTubeShortsBlocking(enabled: Boolean) {
        if (emergencyLockManager.isNuclearLockActive() && !enabled) {
            showToast("☢️ Nuclear Strict Focus Lock is active! Cannot disable shields.")
            return
        }
        StudyPreferences.setBlockYouTubeShorts(context, enabled)
        _uiState.value = _uiState.value.copy(isYouTubeShortsBlocked = enabled)
        StudyGuardAppWidget.updateAllWidgets(context)
    }

    fun toggleStrictUninstallLock(enabled: Boolean) {
        StudyPreferences.setStrictUninstallLock(context, enabled)
        _uiState.value = _uiState.value.copy(isStrictUninstallLockEnabled = enabled)
    }

    fun setSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    fun setCategoryFilter(category: String) {
        _uiState.value = _uiState.value.copy(selectedCategoryFilter = category)
    }

    fun blockAllSocialAndGames() {
        if (emergencyLockManager.isNuclearLockActive()) {
            showToast("☢️ Nuclear Strict Focus Lock is active! App locklist is frozen.")
            return
        }
        viewModelScope.launch {
            val distractions = InstalledAppsHelper.KNOWN_DISTRACTIONS
            val currentApps = allApps.value
            for (app in currentApps) {
                if (distractions.contains(app.packageName) ||
                    app.category.equals("Social", ignoreCase = true) ||
                    app.category.equals("Games", ignoreCase = true)
                ) {
                    repository.setAppDistraction(app.packageName, true)
                }
            }
        }
    }

    fun allowAllApps() {
        if (emergencyLockManager.isNuclearLockActive()) {
            showToast("☢️ Nuclear Strict Focus Lock is active! App locklist is frozen.")
            return
        }
        viewModelScope.launch {
            val currentApps = allApps.value
            for (app in currentApps) {
                repository.setAppDistraction(app.packageName, false)
            }
        }
    }

    fun setAppLimit(packageName: String, limitMinutes: Int): Boolean {
        val success = appLimitManager.setAppLimit(packageName, limitMinutes)
        if (!success) {
            showToast("🔒 Limit is locked until tomorrow morning (6:00 AM) and cannot be adjusted today!")
        } else {
            if (limitMinutes > 0) {
                showToast("⏱️ Limit set to ${limitMinutes}m! Locked until tomorrow 6:00 AM.")
            } else {
                showToast("Daily limit removed.")
            }
        }
        return success
    }

    fun removeAppLimit(packageName: String): Boolean {
        val success = appLimitManager.removeAppLimit(packageName)
        if (!success) {
            showToast("🔒 Limit is locked until tomorrow morning (6:00 AM) and cannot be removed today!")
        } else {
            showToast("Daily limit removed.")
        }
        return success
    }

    fun canModifyAppLimit(packageName: String): Boolean {
        return appLimitManager.canModifyLimit(packageName)
    }

    fun refreshAppLimits() {
        appLimitManager.refreshAll()
    }

    override fun onCleared() {
        super.onCleared()
        StudyAudioPlayer.stop()
    }

    class Factory(
        private val repository: StudyRepository,
        private val context: Context
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return StudyViewModel(repository, context) as T
        }
    }
}
