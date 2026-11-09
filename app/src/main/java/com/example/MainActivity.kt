package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.StudyViewModel
import com.example.ui.screens.AchievementsScreen
import com.example.ui.screens.AiStudyCoachScreen
import com.example.ui.screens.AppShieldsScreen
import com.example.ui.screens.ChromeExtensionHelpScreen
import com.example.ui.screens.FlashcardsScreen
import com.example.ui.screens.FocusDashboardScreen
import com.example.ui.screens.FocusHistoryScreen
import com.example.ui.screens.MusicSaverScreen
import com.example.ui.screens.SecurityScreen
import com.example.ui.screens.SmartRemindersScreen
import com.example.ui.screens.StatsScreen
import com.example.ui.screens.StudyRoomScreen
import com.example.ui.screens.SyncHubScreen
import com.example.ui.theme.PrimaryIndigo
import com.example.ui.theme.PrimaryIndigoLight
import com.example.ui.theme.SecondaryTeal
import com.example.ui.theme.StudyGuardTheme
import com.example.ui.theme.TertiaryAmber
import com.example.util.StudyPreferences

enum class MainTab(
    val title: String,
    val selectedIcon: androidx.compose.ui.graphics.vector.ImageVector,
    val unselectedIcon: androidx.compose.ui.graphics.vector.ImageVector
) {
    DASHBOARD("Dashboard", Icons.Filled.Dashboard, Icons.Outlined.Dashboard),
    STUDY("Timer", Icons.Filled.Timer, Icons.Outlined.Timer),
    MUSIC("Music", Icons.Filled.MusicNote, Icons.Outlined.MusicNote),
    LOCKS("Custom Locks", Icons.Filled.Lock, Icons.Outlined.Lock),
    SYNC("Sync", Icons.Filled.SyncAlt, Icons.Outlined.SyncAlt)
}

class MainActivity : ComponentActivity() {

    private val viewModel: StudyViewModel by viewModels {
        StudyViewModel.Factory(
            repository = StudyGuardApp.instance.repository,
            context = applicationContext
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val navigateTo = intent.getStringExtra("navigate_to")

        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            StudyGuardTheme(themeMode = themeMode) {
                MainAppContent(
                    viewModel = viewModel,
                    initialShowReminders = navigateTo == "reminders"
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkPermissions(this)
        viewModel.refreshAutoLockStatus()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppContent(viewModel: StudyViewModel, initialShowReminders: Boolean = false) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var currentTab by remember { mutableStateOf(MainTab.DASHBOARD) }
    var showSecurityOverlay by remember { mutableStateOf(false) }
    var showChromeHelpOverlay by remember { mutableStateOf(false) }
    var showBadgesOverlay by remember { mutableStateOf(false) }
    var showHistoryOverlay by remember { mutableStateOf(false) }
    var showRemindersOverlay by remember { mutableStateOf(initialShowReminders) }
    var showAiCoachOverlay by remember { mutableStateOf(false) }
    var showFlashcardsOverlay by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var badgeSubTab by remember { mutableIntStateOf(0) } // 0: Achievements, 1: Usage Stats
    var locksSelectedSubTab by remember { mutableIntStateOf(0) } // 0: Custom Locks, 1: Blocked Apps, 2: Laptop

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val allApps by viewModel.allApps.collectAsStateWithLifecycle()
    val allSessions by viewModel.allSessions.collectAsStateWithLifecycle()
    val totalDistractions by viewModel.totalDistractionsCount.collectAsStateWithLifecycle()
    val shortsBlocked by viewModel.shortsBlockedCount.collectAsStateWithLifecycle()
    val recentDistractions by viewModel.recentDistractions.collectAsStateWithLifecycle()
    val musicTracks by viewModel.allMusicTracks.collectAsStateWithLifecycle()
    val allPlaylists by viewModel.allPlaylists.collectAsStateWithLifecycle()
    val achievements by viewModel.allAchievements.collectAsStateWithLifecycle()
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
    val lastSyncFormatted by viewModel.lastSyncFormatted.collectAsStateWithLifecycle()
    val laptopStats by viewModel.laptopStats.collectAsStateWithLifecycle()
    val remindersList by viewModel.remindersList.collectAsStateWithLifecycle()
    val marathonState by viewModel.marathonState.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val appLimitsMap by viewModel.appLimitsMap.collectAsStateWithLifecycle()
    val autoLockRules by viewModel.autoLockRules.collectAsStateWithLifecycle()
    val activeAutoLockStatus by viewModel.activeAutoLockStatus.collectAsStateWithLifecycle()
    val aiCoachResponse by viewModel.aiCoachResponse.collectAsStateWithLifecycle()
    val isAiLoading by viewModel.isAiLoading.collectAsStateWithLifecycle()
    val isLiveGemini by viewModel.isLiveGemini.collectAsStateWithLifecycle()
    val chatMessages by viewModel.chatMessages.collectAsStateWithLifecycle()
    val selectedCoachRole by viewModel.selectedCoachRole.collectAsStateWithLifecycle()
    val dailyGoalMinutes by viewModel.dailyGoalMinutes.collectAsStateWithLifecycle()

    val hasOverlay = showSecurityOverlay || showChromeHelpOverlay || showBadgesOverlay ||
            showHistoryOverlay || showRemindersOverlay || showAiCoachOverlay || showFlashcardsOverlay

    // BackHandler: handle overlay dismissals and sub-screens
    BackHandler(enabled = hasOverlay || currentTab != MainTab.DASHBOARD) {
        when {
            showAiCoachOverlay -> showAiCoachOverlay = false
            showRemindersOverlay -> showRemindersOverlay = false
            showFlashcardsOverlay -> showFlashcardsOverlay = false
            showSecurityOverlay -> showSecurityOverlay = false
            showChromeHelpOverlay -> showChromeHelpOverlay = false
            showBadgesOverlay -> showBadgesOverlay = false
            showHistoryOverlay -> showHistoryOverlay = false
            else -> currentTab = MainTab.DASHBOARD
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Shield,
                            contentDescription = null,
                            tint = PrimaryIndigo,
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            text = "StudyGuard",
                            fontWeight = FontWeight.Black,
                            fontSize = 19.sp,
                            color = PrimaryIndigo,
                            maxLines = 1,
                            softWrap = false
                        )
                        if (uiState.isStudyActive) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF10B981))
                            )
                        } else if (activeAutoLockStatus != null) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(TertiaryAmber)
                            )
                        }
                    }
                },
                actions = {
                    // Dark / Light / System Mode Quick Toggle Button
                    IconButton(
                        onClick = { viewModel.toggleThemeMode() },
                        modifier = Modifier.testTag("top_bar_theme_toggle")
                    ) {
                        val themeIcon = when (themeMode) {
                            StudyPreferences.ThemeMode.DARK -> Icons.Default.DarkMode
                            StudyPreferences.ThemeMode.LIGHT -> Icons.Default.LightMode
                            StudyPreferences.ThemeMode.SYSTEM -> Icons.Default.BrightnessAuto
                            else -> Icons.Default.Palette
                        }
                        Icon(
                            imageVector = themeIcon,
                            contentDescription = "Toggle Theme",
                            tint = if (themeMode == StudyPreferences.ThemeMode.DARK) TertiaryAmber else PrimaryIndigo
                        )
                    }

                    // AI Study Coach Button (Gemini AI)
                    IconButton(
                        onClick = {
                            showAiCoachOverlay = true
                            showSecurityOverlay = false
                            showChromeHelpOverlay = false
                            showBadgesOverlay = false
                            showHistoryOverlay = false
                            showRemindersOverlay = false
                        },
                        modifier = Modifier.testTag("top_bar_ai_coach_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "AI Study Coach",
                            tint = SecondaryTeal
                        )
                    }

                    // Smart Reminders Button
                    IconButton(
                        onClick = {
                            showRemindersOverlay = true
                            showAiCoachOverlay = false
                            showSecurityOverlay = false
                            showChromeHelpOverlay = false
                            showBadgesOverlay = false
                            showHistoryOverlay = false
                        },
                        modifier = Modifier.testTag("top_bar_reminders_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Alarm,
                            contentDescription = "Smart Reminders",
                            tint = TertiaryAmber
                        )
                    }

                    // More Options Dropdown Menu (Clean, prevents title from wrapping!)
                    Box {
                        IconButton(
                            onClick = { showMoreMenu = true },
                            modifier = Modifier.testTag("top_bar_more_menu_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More Options"
                            )
                        }

                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Anti-Uninstall & Permissions") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (uiState.isDeviceAdminEnabled) Icons.Filled.Shield else Icons.Outlined.Shield,
                                        contentDescription = null,
                                        tint = if (uiState.isDeviceAdminEnabled) Color(0xFF10B981) else PrimaryIndigo
                                    )
                                },
                                onClick = {
                                    showMoreMenu = false
                                    showSecurityOverlay = true
                                    showAiCoachOverlay = false
                                    showRemindersOverlay = false
                                }
                            )

                            DropdownMenuItem(
                                text = { Text("Achievements & Screen Stats") },
                                leadingIcon = {
                                    Icon(Icons.Filled.EmojiEvents, contentDescription = null, tint = TertiaryAmber)
                                },
                                onClick = {
                                    showMoreMenu = false
                                    showBadgesOverlay = true
                                    showAiCoachOverlay = false
                                    showRemindersOverlay = false
                                }
                            )

                            DropdownMenuItem(
                                text = { Text("Focus Session History") },
                                leadingIcon = {
                                    Icon(Icons.Filled.History, contentDescription = null, tint = PrimaryIndigo)
                                },
                                onClick = {
                                    showMoreMenu = false
                                    showHistoryOverlay = true
                                    showAiCoachOverlay = false
                                    showRemindersOverlay = false
                                }
                            )

                            DropdownMenuItem(
                                text = { Text("Chrome Laptop Extension") },
                                leadingIcon = {
                                    Icon(Icons.Filled.Extension, contentDescription = null, tint = Color(0xFF4285F4))
                                },
                                onClick = {
                                    showMoreMenu = false
                                    showChromeHelpOverlay = true
                                    showAiCoachOverlay = false
                                    showRemindersOverlay = false
                                }
                            )

                            DropdownMenuItem(
                                text = { Text("Study Flashcards 🎴") },
                                leadingIcon = {
                                    Icon(Icons.Filled.Style, contentDescription = null, tint = SecondaryTeal)
                                },
                                onClick = {
                                    showMoreMenu = false
                                    showFlashcardsOverlay = true
                                    showAiCoachOverlay = false
                                    showRemindersOverlay = false
                                    showSecurityOverlay = false
                                    showChromeHelpOverlay = false
                                    showBadgesOverlay = false
                                    showHistoryOverlay = false
                                }
                            )

                            DropdownMenuItem(
                                text = { Text("Custom App Locks (Study-to-Unlock)") },
                                leadingIcon = {
                                    Icon(Icons.Filled.LockClock, contentDescription = null, tint = TertiaryAmber)
                                },
                                onClick = {
                                    showMoreMenu = false
                                    locksSelectedSubTab = 0
                                    currentTab = MainTab.LOCKS
                                    showAiCoachOverlay = false
                                    showRemindersOverlay = false
                                    showSecurityOverlay = false
                                    showChromeHelpOverlay = false
                                    showBadgesOverlay = false
                                    showHistoryOverlay = false
                                }
                            )

                            HorizontalDivider()

                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (uiState.isStudyActive) "Stop Study Timer" else "Start Study Timer",
                                        fontWeight = FontWeight.Bold
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.Timer,
                                        contentDescription = null,
                                        tint = if (uiState.isStudyActive) Color(0xFFEF4444) else Color(0xFF10B981)
                                    )
                                },
                                onClick = {
                                    showMoreMenu = false
                                    viewModel.toggleStudyTimer()
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            if (!hasOverlay) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp
                ) {
                    MainTab.entries.forEach { tab ->
                        val selected = currentTab == tab
                        NavigationBarItem(
                            selected = selected,
                            onClick = { currentTab = tab },
                            icon = {
                                Icon(
                                    imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                    contentDescription = tab.title
                                )
                            },
                            label = {
                                Text(
                                    text = tab.title,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 11.sp
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = PrimaryIndigo,
                                selectedTextColor = PrimaryIndigo,
                                indicatorColor = PrimaryIndigoLight.copy(alpha = 0.2f)
                            ),
                            modifier = Modifier.testTag("nav_tab_${tab.name.lowercase()}")
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                showAiCoachOverlay -> {
                    AiStudyCoachScreen(
                        chatMessages = chatMessages,
                        isLoading = isAiLoading,
                        selectedRole = selectedCoachRole,
                        isLiveApi = isLiveGemini,
                        onSendMessage = { prompt -> viewModel.sendChatMessage(prompt) },
                        onSelectRole = { role -> viewModel.setCoachRole(role) },
                        onClearChat = { viewModel.clearChat() },
                        onStartStudyWithSubject = { subject ->
                            viewModel.startSession(subject, 45)
                            currentTab = MainTab.STUDY
                        },
                        onDismiss = { showAiCoachOverlay = false }
                    )
                }

                showFlashcardsOverlay -> {
                    FlashcardsScreen(
                        onDismiss = { showFlashcardsOverlay = false }
                    )
                }

                showSecurityOverlay -> {
                    SecurityScreen(
                        uiState = uiState,
                        onToggleStrictLock = { enabled ->
                            viewModel.toggleStrictUninstallLock(enabled)
                        },
                        onRefreshPermissions = {
                            viewModel.checkPermissions(context)
                        },
                        onActivateNuclearLock = { minutes ->
                            viewModel.activateNuclearLock(minutes)
                        }
                    )
                }

                showChromeHelpOverlay -> {
                    ChromeExtensionHelpScreen(
                        onDismiss = { showChromeHelpOverlay = false }
                    )
                }

                showBadgesOverlay -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { showBadgesOverlay = false }) {
                                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                            }
                            Text(
                                text = "Analytics & Achievements",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = PrimaryIndigo,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        TabRow(
                            selectedTabIndex = badgeSubTab,
                            containerColor = MaterialTheme.colorScheme.surface
                        ) {
                            Tab(
                                selected = badgeSubTab == 0,
                                onClick = { badgeSubTab = 0 },
                                text = { Text("Achievements 🏆", fontWeight = FontWeight.Bold) }
                            )
                            Tab(
                                selected = badgeSubTab == 1,
                                onClick = { badgeSubTab = 1 },
                                text = { Text("Screen Time Stats 📊", fontWeight = FontWeight.Bold) }
                            )
                        }

                        if (badgeSubTab == 0) {
                            AchievementsScreen(achievements = achievements)
                        } else {
                            StatsScreen(
                                uiState = uiState,
                                sessions = allSessions,
                                totalDistractions = totalDistractions,
                                shortsBlocked = shortsBlocked,
                                recentDistractions = recentDistractions,
                                onRefreshUsageStats = {
                                    viewModel.refreshUsageStats()
                                },
                                laptopStats = laptopStats,
                                onQuickSyncLaptop = {
                                    viewModel.triggerSyncNow()
                                },
                                onUpdateLaptopStats = { mins, sessions, distractions ->
                                    viewModel.updateLaptopStats(mins, sessions, distractions)
                                },
                                onAddStudyMinutes = { mins, subject ->
                                    viewModel.addManualStudySession(subject, mins)
                                }
                            )
                        }
                    }
                }

                showHistoryOverlay -> {
                    FocusHistoryScreen(
                        sessions = allSessions,
                        onDeleteSession = { session ->
                            viewModel.deleteSession(session)
                        },
                        onClearAllHistory = {
                            viewModel.clearAllSessions()
                        }
                    )
                }

                showRemindersOverlay -> {
                    SmartRemindersScreen(
                        reminders = remindersList,
                        onToggleReminder = { id, enabled ->
                            viewModel.toggleReminder(id, enabled)
                        },
                        onUpdateTime = { id, hour, minute ->
                            viewModel.updateReminderTime(id, hour, minute)
                        },
                        onAddCustomReminder = { title, hour, min, msg, icon ->
                            viewModel.addCustomReminder(title, hour, min, msg, icon)
                        },
                        onDeleteReminder = { id ->
                            viewModel.deleteReminder(id)
                        },
                        onSendTestReminder = { reminder ->
                            viewModel.sendTestReminder(reminder)
                        },
                        onDismiss = { showRemindersOverlay = false }
                    )
                }

                else -> {
                    Crossfade(targetState = currentTab, label = "tab_fade") { tab ->
                        when (tab) {
                            MainTab.DASHBOARD -> FocusDashboardScreen(
                                uiState = uiState,
                                sessions = allSessions,
                                totalDistractions = totalDistractions,
                                shortsBlocked = shortsBlocked,
                                recentDistractions = recentDistractions,
                                playerState = playerState,
                                lastSyncFormatted = lastSyncFormatted,
                                laptopStats = laptopStats,
                                autoLockRules = autoLockRules,
                                activeAutoLockStatus = activeAutoLockStatus,
                                dailyGoalMinutes = dailyGoalMinutes,
                                onUpdateDailyGoalMinutes = { mins ->
                                    viewModel.setDailyGoalMinutes(mins)
                                },
                                onStartSprint = { mins, subject ->
                                    viewModel.startSession(subject, mins)
                                    currentTab = MainTab.STUDY
                                },
                                onNavigateToLocks = {
                                    locksSelectedSubTab = 0
                                    currentTab = MainTab.LOCKS
                                },
                                onNavigateToShields = {
                                    locksSelectedSubTab = 0
                                    currentTab = MainTab.LOCKS
                                },
                                onNavigateToMusic = {
                                    currentTab = MainTab.MUSIC
                                },
                                onOpenChromeHelp = {
                                    showChromeHelpOverlay = true
                                },
                                onOpenReminders = {
                                    showRemindersOverlay = true
                                },
                                onToggleStudyTimer = {
                                    viewModel.toggleStudyTimer()
                                },
                                onActivateNuclearLock = { mins ->
                                    viewModel.activateNuclearLock(mins)
                                }
                            )

                            MainTab.STUDY -> StudyRoomScreen(
                                uiState = uiState,
                                marathonState = marathonState,
                                onStartSession = { subject, mins ->
                                    viewModel.startSession(subject, mins)
                                },
                                onStopSession = {
                                    viewModel.stopSession()
                                },
                                onToggleStudyTimer = {
                                    viewModel.toggleStudyTimer()
                                },
                                onActivateNuclearLock = { mins ->
                                    viewModel.activateNuclearLock(mins)
                                },
                                onActivateNuclearMarathon = { config ->
                                    viewModel.activateNuclearMarathon(config)
                                },
                                onOpenNotificationSettings = {
                                    viewModel.openNotificationSettings()
                                },
                                onViewNotificationReport = {
                                    viewModel.showNotificationReportDialog()
                                },
                                onDismissNotificationReport = {
                                    viewModel.dismissNotificationReportDialog()
                                },
                                onStartMarathon = { config ->
                                    viewModel.startMarathon(config)
                                },
                                onPauseMarathon = {
                                    viewModel.pauseMarathon()
                                },
                                onResumeMarathon = {
                                    viewModel.resumeMarathon()
                                },
                                onSkipMarathonBreak = {
                                    viewModel.skipMarathonBreak()
                                },
                                onStopMarathon = {
                                    viewModel.stopMarathon()
                                },
                                onOpenReminders = {
                                    showRemindersOverlay = true
                                },
                                onToggleFloatingWidget = { enable ->
                                    viewModel.toggleFloatingWidget(enable)
                                },
                                onNavigateToPermissions = {
                                    showSecurityOverlay = true
                                }
                            )

                            MainTab.MUSIC -> MusicSaverScreen(
                                musicTracks = musicTracks,
                                playlists = allPlaylists,
                                playerState = playerState,
                                onSaveAudioUri = { uri, title, cat ->
                                    viewModel.saveAudioFileFromUri(uri, title, cat)
                                },
                                onPlayTrack = { track ->
                                    viewModel.playTrack(track)
                                },
                                onTogglePlayPause = {
                                    viewModel.togglePlayPauseAudio()
                                },
                                onStopAudio = {
                                    viewModel.stopAudio()
                                },
                                onToggleLoop = {
                                    viewModel.toggleLoopAudio()
                                },
                                onToggleShuffle = {
                                    viewModel.toggleShuffleAudio()
                                },
                                onToggleFavorite = { trId, fav ->
                                    viewModel.toggleMusicFavorite(trId, fav)
                                },
                                onDeleteTrack = { track ->
                                    viewModel.deleteMusicTrack(track)
                                },
                                onCreatePlaylist = { name, desc, color ->
                                    viewModel.createPlaylist(name, desc, color)
                                },
                                onDeletePlaylist = { playlist ->
                                    viewModel.deletePlaylist(playlist)
                                },
                                onAddTrackToPlaylist = { plId, trId ->
                                    viewModel.addTrackToPlaylist(plId, trId)
                                },
                                onPlayPlaylist = { playlist, isShuffle ->
                                    viewModel.playPlaylistById(playlist.id, isShuffle)
                                },
                                onLoadPresets = {
                                    viewModel.loadDefaultMusicPresets()
                                }
                            )

                            MainTab.LOCKS -> AppShieldsScreen(
                                uiState = uiState,
                                allApps = allApps,
                                autoLockRules = autoLockRules,
                                activeAutoLockStatus = activeAutoLockStatus,
                                appLimitsMap = appLimitsMap,
                                onSetAppLimit = { pkg, mins ->
                                    viewModel.setAppLimit(pkg, mins)
                                },
                                onRemoveAppLimit = { pkg ->
                                    viewModel.removeAppLimit(pkg)
                                },
                                canModifyAppLimit = { pkg ->
                                    viewModel.canModifyAppLimit(pkg)
                                },
                                initialSubTab = locksSelectedSubTab,
                                onToggleYouTubeShorts = { enabled ->
                                    viewModel.toggleYouTubeShortsBlocking(enabled)
                                },
                                onToggleAppDistraction = { pkg, isDistraction ->
                                    viewModel.toggleAppDistraction(pkg, isDistraction)
                                },
                                onBlockAllSocialAndGames = {
                                    viewModel.blockAllSocialAndGames()
                                },
                                onAllowAllApps = {
                                    viewModel.allowAllApps()
                                },
                                onAddCustomApp = { pkg, name, cat ->
                                    viewModel.addCustomBlockedApp(pkg, name, cat)
                                },
                                onSimulateLockOverlay = { appName ->
                                    viewModel.simulateDistractionLock(appName)
                                },
                                onAddAutoLockRule = { name, days, sH, sM, eH, eM, studyMins ->
                                    viewModel.addAutoLockRule(name, days, sH, sM, eH, eM, studyMins)
                                },
                                onUpdateAutoLockRule = { rule ->
                                    viewModel.updateAutoLockRule(rule)
                                },
                                onToggleAutoLockRule = { id, enabled ->
                                    viewModel.toggleAutoLockRule(id, enabled)
                                },
                                onDeleteAutoLockRule = { id ->
                                    viewModel.deleteAutoLockRule(id)
                                },
                                onSearchQueryChange = { query ->
                                    viewModel.setSearchQuery(query)
                                },
                                onFilterChange = { filter ->
                                    viewModel.setCategoryFilter(filter)
                                }
                            )

                            MainTab.SYNC -> SyncHubScreen(
                                syncCode = viewModel.syncPairingCode,
                                lastSyncFormatted = lastSyncFormatted,
                                devices = viewModel.syncManager.getPairedDevices(),
                                onTriggerSync = { onDone ->
                                    viewModel.triggerSyncNow { onDone() }
                                },
                                onExportSyncData = {
                                    viewModel.exportSyncData()
                                },
                                onImportSyncData = { json ->
                                    viewModel.importSyncData(json)
                                },
                                onOpenChromeHelp = {
                                    showChromeHelpOverlay = true
                                },
                                laptopStats = laptopStats,
                                phoneStudyMinutes = allSessions.sumOf { it.actualMinutes }.toLong() +
                                    if (uiState.isStudyActive) {
                                        val start = StudyPreferences.getSessionStartTime(context)
                                        ((System.currentTimeMillis() - start) / 60000L).coerceAtLeast(0L)
                                    } else 0L,
                                phoneSessionsCount = allSessions.count { it.actualMinutes > 0 || it.isCompleted },
                                phoneDistractionsBlocked = totalDistractions,
                                onUpdateLaptopStats = { mins, sess, dis ->
                                    viewModel.updateLaptopStats(mins, sess, dis)
                                },
                                onQuickAddLaptopStudy = { mins ->
                                    viewModel.quickAddLaptopStudy(mins)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
