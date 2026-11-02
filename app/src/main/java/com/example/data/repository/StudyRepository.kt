package com.example.data.repository

import android.content.Context
import com.example.data.local.StudyDao
import com.example.data.model.AchievementEntity
import com.example.data.model.BlockedAppEntity
import com.example.data.model.DistractionEvent
import com.example.data.model.PlaylistEntity
import com.example.data.model.PlaylistTrackCrossRef
import com.example.data.model.StudyMusicTrack
import com.example.data.model.StudySession
import com.example.util.AchievementsManager
import com.example.util.InstalledAppsHelper
import com.example.util.StudyAudioPlayer
import com.example.util.StudyPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StudyRepository(
    private val studyDao: StudyDao,
    private val context: Context
) {
    companion object {
        val distractionCache: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        fun isPackageDistraction(packageName: String): Boolean {
            return distractionCache.contains(packageName)
        }
    }

    val allSessions: Flow<List<StudySession>> = studyDao.getAllSessions()
    val allApps: Flow<List<BlockedAppEntity>> = studyDao.getAllAppConfigs()
    val distractionApps: Flow<List<BlockedAppEntity>> = studyDao.getDistractionApps()
    val recentDistractions: Flow<List<DistractionEvent>> = studyDao.getRecentDistractions()
    val totalDistractionsCount: Flow<Int> = studyDao.getTotalDistractionsBlockedCount()
    val shortsBlockedCount: Flow<Int> = studyDao.getShortsBlockedCount()
    val allMusicTracks: Flow<List<StudyMusicTrack>> = studyDao.getAllMusicTracks()
    val allAchievements: Flow<List<AchievementEntity>> = studyDao.getAllAchievements()
    val allPlaylists: Flow<List<PlaylistEntity>> = studyDao.getAllPlaylists()

    init {
        CoroutineScope(Dispatchers.IO).launch {
            distractionApps.collect { list ->
                distractionCache.clear()
                distractionCache.addAll(list.map { it.packageName })
            }
        }
    }

    suspend fun syncInstalledAppsIfNeeded() = withContext(Dispatchers.IO) {
        val existing = studyDao.getAllAppConfigs().first()
        val installed = InstalledAppsHelper.getInstalledLauncherApps(context)
        val installedPackages = installed.map { it.packageName }.toSet()

        // 1. Remove non-installed apps from database (cleans up any phantom seeded apps)
        for (app in existing) {
            if (!installedPackages.contains(app.packageName)) {
                studyDao.deleteAppByPackage(app.packageName)
            }
        }

        // 2. Insert installed launcher apps that aren't yet in the database
        val existingMap = existing.filter { installedPackages.contains(it.packageName) }.associateBy { it.packageName }
        val toInsert = mutableListOf<BlockedAppEntity>()
        for (app in installed) {
            if (!existingMap.containsKey(app.packageName)) {
                toInsert.add(app)
            }
        }
        if (toInsert.isNotEmpty()) {
            studyDao.insertAllApps(toInsert)
        }

        // Initialize Achievements if empty
        val existingAchievements = studyDao.getAllAchievements().first()
        if (existingAchievements.isEmpty()) {
            studyDao.insertAllAchievements(AchievementsManager.INITIAL_ACHIEVEMENTS)
        }

        // Initialize Default Music Presets & Playlists if empty
        val existingMusic = studyDao.getAllMusicTracks().first()
        if (existingMusic.isEmpty()) {
            val insertedIds = mutableListOf<Long>()
            for (preset in StudyAudioPlayer.DEFAULT_PRESETS) {
                val id = studyDao.insertMusicTrack(preset.copy(id = 0))
                insertedIds.add(id)
            }

            // Create initial default study playlists
            val existingPlaylists = studyDao.getAllPlaylists().first()
            if (existingPlaylists.isEmpty()) {
                val p1 = studyDao.insertPlaylist(
                    PlaylistEntity(
                        name = "Deep Focus Lo-Fi",
                        description = "Smooth downtempo beats to trigger alpha wave flow state.",
                        colorHex = "#6366F1"
                    )
                )
                val p2 = studyDao.insertPlaylist(
                    PlaylistEntity(
                        name = "Rain & Zen Ambience",
                        description = "Gentle rainfall and peaceful ambient noise for studying.",
                        colorHex = "#10B981"
                    )
                )
                if (insertedIds.isNotEmpty()) {
                    studyDao.insertPlaylistTrack(PlaylistTrackCrossRef(playlistId = p1, trackId = insertedIds[0]))
                    if (insertedIds.size > 1) {
                        studyDao.insertPlaylistTrack(PlaylistTrackCrossRef(playlistId = p2, trackId = insertedIds[1]))
                    }
                }
            }
        }
    }

    suspend fun setAppDistraction(packageName: String, isDistraction: Boolean) = withContext(Dispatchers.IO) {
        if (isDistraction) {
            distractionCache.add(packageName)
        } else {
            distractionCache.remove(packageName)
        }
        studyDao.setAppDistractionState(packageName, isDistraction)
    }

    suspend fun addCustomApp(packageName: String, appName: String, category: String, isDistraction: Boolean) = withContext(Dispatchers.IO) {
        if (isDistraction) {
            distractionCache.add(packageName)
        } else {
            distractionCache.remove(packageName)
        }
        studyDao.insertOrUpdateApp(
            BlockedAppEntity(
                packageName = packageName,
                appName = appName,
                isDistraction = isDistraction,
                category = category,
                isProtectedSystemApp = false
            )
        )
    }

    suspend fun getTodayStudiedMinutes(): Int = withContext(Dispatchers.IO) {
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val completed = studyDao.getSessionsSince(cal.timeInMillis).sumOf { it.actualMinutes }
        val activeElapsed = if (StudyPreferences.isStudyActive(context)) {
            val startTime = StudyPreferences.getSessionStartTime(context)
            if (startTime > 0L) {
                ((System.currentTimeMillis() - startTime) / (60 * 1000L)).toInt().coerceAtLeast(0)
            } else 0
        } else 0
        completed + activeElapsed
    }

    suspend fun setAllDistractions(distractPackageNames: Set<String>) = withContext(Dispatchers.IO) {
        distractionCache.clear()
        distractionCache.addAll(distractPackageNames)
        val all = studyDao.getAllAppConfigs().first()
        for (app in all) {
            val shouldBlock = distractPackageNames.contains(app.packageName)
            if (app.isDistraction != shouldBlock) {
                studyDao.setAppDistractionState(app.packageName, shouldBlock)
            }
        }
    }

    suspend fun logDistractionEvent(
        packageName: String,
        appName: String,
        eventType: String,
        reason: String
    ) = withContext(Dispatchers.IO) {
        studyDao.insertDistractionEvent(
            DistractionEvent(
                packageName = packageName,
                appName = appName,
                eventType = eventType,
                reason = reason
            )
        )
        evaluateAchievements()
    }

    suspend fun startSession(subject: String, minutes: Int): Long = withContext(Dispatchers.IO) {
        val session = StudySession(
            subject = subject.ifBlank { "Focused Study" },
            plannedMinutes = minutes,
            actualMinutes = 0,
            startTime = System.currentTimeMillis(),
            isCompleted = false
        )
        val id = studyDao.insertSession(session)
        StudyPreferences.startStudySession(context, id, session.subject, minutes)
        id
    }

    suspend fun completeSession(sessionId: Long, actualMinutes: Int, distractionsIntercepted: Int) = withContext(Dispatchers.IO) {
        val existing = studyDao.getSessionById(sessionId)
        if (existing != null) {
            studyDao.updateSession(
                existing.copy(
                    actualMinutes = actualMinutes,
                    endTime = System.currentTimeMillis(),
                    isCompleted = true,
                    distractionsIntercepted = distractionsIntercepted
                )
            )
        }
        StudyPreferences.stopStudySession(context)
        evaluateAchievements()
    }

    suspend fun cancelSession(sessionId: Long, actualMinutes: Int) = withContext(Dispatchers.IO) {
        val existing = studyDao.getSessionById(sessionId)
        if (existing != null) {
            studyDao.updateSession(
                existing.copy(
                    actualMinutes = actualMinutes,
                    endTime = System.currentTimeMillis(),
                    isCompleted = false
                )
            )
        }
        StudyPreferences.stopStudySession(context)
        evaluateAchievements()
    }

    suspend fun deleteSession(session: StudySession) = withContext(Dispatchers.IO) {
        studyDao.deleteSession(session)
    }

    suspend fun clearAllSessions() = withContext(Dispatchers.IO) {
        studyDao.clearAllSessions()
    }

    suspend fun addManualStudySession(subject: String, minutes: Int) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val session = StudySession(
            subject = subject.ifBlank { "Deep Study Session" },
            plannedMinutes = minutes,
            actualMinutes = minutes.coerceAtLeast(1),
            startTime = now - (minutes.coerceAtLeast(1) * 60_000L),
            endTime = now,
            isCompleted = true,
            distractionsIntercepted = 0
        )
        val id = studyDao.insertSession(session)
        evaluateAchievements()
        id
    }

    suspend fun getDistractionPackageNames(): List<String> = withContext(Dispatchers.IO) {
        studyDao.getDistractionPackageNames()
    }

    // Music Tracks
    suspend fun saveMusicTrack(track: StudyMusicTrack): Long = withContext(Dispatchers.IO) {
        val id = studyDao.insertMusicTrack(track)
        recordMusicPlayedOrSaved()
        id
    }

    suspend fun deleteMusicTrack(track: StudyMusicTrack) = withContext(Dispatchers.IO) {
        studyDao.deleteMusicTrack(track)
    }

    suspend fun toggleMusicFavorite(trackId: Long, isFavorite: Boolean) = withContext(Dispatchers.IO) {
        studyDao.setTrackFavorite(trackId, isFavorite)
    }

    suspend fun recordMusicPlayedOrSaved() = withContext(Dispatchers.IO) {
        val ach = studyDao.getAchievementById("MUSIC_MAESTRO")
        if (ach != null && !ach.isUnlocked) {
            studyDao.updateAchievement(
                ach.copy(
                    currentValue = 1,
                    isUnlocked = true,
                    unlockedAt = System.currentTimeMillis()
                )
            )
        }
    }

    // Playlist Management
    suspend fun createPlaylist(name: String, description: String = "", colorHex: String = "#6366F1"): Long = withContext(Dispatchers.IO) {
        studyDao.insertPlaylist(
            PlaylistEntity(
                name = name,
                description = description,
                colorHex = colorHex
            )
        )
    }

    suspend fun deletePlaylist(playlist: PlaylistEntity) = withContext(Dispatchers.IO) {
        studyDao.clearPlaylistTracks(playlist.id)
        studyDao.deletePlaylist(playlist)
    }

    suspend fun addTrackToPlaylist(playlistId: Long, trackId: Long) = withContext(Dispatchers.IO) {
        studyDao.insertPlaylistTrack(PlaylistTrackCrossRef(playlistId = playlistId, trackId = trackId))
    }

    suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: Long) = withContext(Dispatchers.IO) {
        studyDao.removeTrackFromPlaylist(playlistId, trackId)
    }

    fun getTracksForPlaylist(playlistId: Long): Flow<List<StudyMusicTrack>> {
        return studyDao.getTracksForPlaylist(playlistId)
    }

    fun getPlaylistTrackCount(playlistId: Long): Flow<Int> {
        return studyDao.getPlaylistTrackCount(playlistId)
    }

    suspend fun getPlaylistsForTrack(trackId: Long): List<Long> = withContext(Dispatchers.IO) {
        studyDao.getPlaylistsForTrack(trackId)
    }

    // Achievements Evaluation
    suspend fun evaluateAchievements() = withContext(Dispatchers.IO) {
        val sessions = studyDao.getAllSessions().first()
        val totalDistractions = studyDao.getTotalDistractionsBlockedCount().first()
        val shortsBlocked = studyDao.getShortsBlockedCount().first()
        val completedSessions = sessions.filter { it.isCompleted }
        val totalMinutes = completedSessions.sumOf { it.actualMinutes }

        // 1. FIRST_SESSION
        checkAndUpdateAchievement("FIRST_SESSION", completedSessions.size)

        // 2. CENTURION (100 min)
        checkAndUpdateAchievement("CENTURION", totalMinutes)

        // 3. DEEP_DIVER (500 min)
        checkAndUpdateAchievement("DEEP_DIVER", totalMinutes)

        // 4. SHORTS_SLAYER (5 shorts)
        checkAndUpdateAchievement("SHORTS_SLAYER", shortsBlocked)

        // 5. DISTRACTION_DEFENDER (10 distractions)
        checkAndUpdateAchievement("DISTRACTION_DEFENDER", totalDistractions)

        // 6. IRON_DISCIPLINE (3 sessions of >= 45m with 0 distractions)
        val cleanLongSessions = completedSessions.count { it.plannedMinutes >= 45 && it.distractionsIntercepted == 0 }
        checkAndUpdateAchievement("IRON_DISCIPLINE", cleanLongSessions)

        // 7. UNSHAKEABLE (Device admin enabled)
        val adminActive = StudyPreferences.isStudyActive(context) && StudyPreferences.isStrictUninstallLockEnabled(context)
        if (adminActive) {
            checkAndUpdateAchievement("UNSHAKEABLE", 1)
        }
    }

    private suspend fun checkAndUpdateAchievement(id: String, currentProgress: Int) {
        val ach = studyDao.getAchievementById(id) ?: return
        val wasUnlocked = ach.isUnlocked
        val isNowUnlocked = currentProgress >= ach.targetValue
        val newCurrent = currentProgress.coerceAtMost(ach.targetValue)

        if (newCurrent != ach.currentValue || (!wasUnlocked && isNowUnlocked)) {
            studyDao.updateAchievement(
                ach.copy(
                    currentValue = newCurrent,
                    isUnlocked = wasUnlocked || isNowUnlocked,
                    unlockedAt = if (!wasUnlocked && isNowUnlocked) System.currentTimeMillis() else ach.unlockedAt
                )
            )
        }
    }
}
