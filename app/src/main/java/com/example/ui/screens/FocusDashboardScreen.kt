package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.DistractionEvent
import com.example.data.model.StudySession
import com.example.ui.StudyUiState
import com.example.ui.theme.PrimaryIndigo
import com.example.ui.theme.PrimaryIndigoLight
import com.example.ui.theme.SecondaryTeal
import com.example.ui.theme.TertiaryAmber
import com.example.util.AudioPlayerState
import com.example.util.MarathonConfig
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class FriendStudyEntry(
    val name: String,
    val focusHours: Float,
    val streakDays: Int,
    val focusScore: Int,
    val isUser: Boolean = false
)

@Composable
fun FocusDashboardScreen(
    uiState: StudyUiState,
    sessions: List<StudySession>,
    totalDistractions: Int,
    shortsBlocked: Int,
    recentDistractions: List<DistractionEvent>,
    playerState: AudioPlayerState,
    lastSyncFormatted: String,
    laptopStats: com.example.util.LaptopStats = com.example.util.LaptopStats(),
    autoLockRules: List<com.example.util.AutoLockScheduleRule> = emptyList(),
    activeAutoLockStatus: com.example.util.ActiveAutoLockStatus? = null,
    onStartSprint: (minutes: Int, subject: String) -> Unit,
    onStartMarathon: (MarathonConfig) -> Unit = {},
    onToggleStudyTimer: () -> Unit = {},
    onOpenChromeHelp: () -> Unit = {},
    onOpenReminders: () -> Unit = {},
    onNavigateToLocks: () -> Unit = {},
    onNavigateToShields: () -> Unit = onNavigateToLocks,
    onNavigateToSync: () -> Unit = {},
    onNavigateToMusic: () -> Unit = {},
    onActivateNuclearLock: (Int) -> Unit = {},
    dailyGoalMinutes: Int = 180,
    onUpdateDailyGoalMinutes: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showNuclearDashboardDialog by remember { mutableStateOf(false) }
    var nuclearMinutes by remember { mutableStateOf(30) }
    var showNuclearWarningDialog by remember { mutableStateOf(false) }
    var showEditDailyTargetDialog by remember { mutableStateOf(false) }

    // Live phone study minutes: sum all saved sessions + currently active session elapsed time
    val activeElapsedMinutes = if (uiState.isStudyActive) {
        val start = com.example.util.StudyPreferences.getSessionStartTime(context)
        val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(0L)
        ((elapsed + 55_000L) / 60_000L).coerceAtLeast(1L)
    } else 0L

    val phoneFocusMinutes = remember(sessions, activeElapsedMinutes) {
        sessions.sumOf { it.actualMinutes } + activeElapsedMinutes
    }
    val phoneSessions = remember(sessions) { sessions.filter { it.actualMinutes > 0 || it.isCompleted } }
    val laptopFocusMinutes = laptopStats.focusMinutes
    val combinedFocusMinutes = phoneFocusMinutes + laptopFocusMinutes
    val totalHours = String.format(Locale.getDefault(), "%.1f", combinedFocusMinutes / 60.0)

    var showFocusScoreDialog by remember { mutableStateOf(false) }
    var showFriendsDialog by remember { mutableStateOf(false) }
    var showAddFriendDialog by remember { mutableStateOf(false) }

    val prefs = remember { context.getSharedPreferences("study_guard_friends_prefs", Context.MODE_PRIVATE) }
    var friendsList by remember {
        mutableStateOf(loadFriends(prefs))
    }

    // Calculate Streak
    val streakDays = remember(phoneSessions, laptopFocusMinutes) {
        val daysSet = phoneSessions.map {
            val sdf = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
            val d = Date(it.startTime)
            sdf.format(d)
        }.toSet()
        daysSet.size.coerceAtLeast(if (phoneSessions.isNotEmpty() || laptopFocusMinutes > 0) 1 else 0)
    }

    // Focus Score out of 100
    val focusScore = remember(phoneSessions, laptopFocusMinutes, totalDistractions) {
        val totalSessionsCount = phoneSessions.size + (laptopFocusMinutes / 30L).toInt()
        if (totalSessionsCount == 0 && phoneFocusMinutes == 0L) 85
        else {
            val base = 88
            val bonus = (totalSessionsCount * 2).coerceAtMost(10)
            val penalty = (totalDistractions / 5).coerceAtMost(8)
            (base + bonus - penalty).coerceIn(60, 100)
        }
    }

    fun shareScorecard() {
        val shareText = """
            🛡️ StudyGuard Focus Report
            👤 Cadet Status: Active Deep Work
            🔥 Study Streak: $streakDays Days
            ⚡ Focus Score: $focusScore/100
            ⏱️ Total Study Time: ${totalHours}h (Phone: ${phoneFocusMinutes}m • Laptop: ${laptopFocusMinutes}m)
            🚫 Distractions Deflected: $totalDistractions
            
            Can you beat my focus score today? Join my study marathon with StudyGuard! 🚀
        """.trimIndent()
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, shareText)
            putExtra(Intent.EXTRA_TITLE, "My StudyGuard Focus Scorecard")
            type = "text/plain"
        }
        context.startActivity(Intent.createChooser(sendIntent, "Share Study Scorecard with Friends"))
    }

    // Daily Goal
    val todayCompletedMinutes = remember(sessions, laptopFocusMinutes) {
        val todayStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        val phoneToday = sessions.filter {
            SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(it.startTime)) == todayStr
        }.sumOf { it.actualMinutes }
        phoneToday + laptopFocusMinutes.toInt()
    }
    val goalProgress = (todayCompletedMinutes.toFloat() / dailyGoalMinutes.toFloat()).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(targetValue = goalProgress, label = "goal_anim")

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Active Nuclear Total Lockout Alert Banner
        if (uiState.isNuclearLockActive) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("dashboard_nuclear_active_banner"),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF7F1D1D)),
                border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFFEF4444)),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFDC2626)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Lock, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "☢️ NUCLEAR TOTAL LOCKOUT ACTIVE",
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Phone completely locked • ${uiState.nuclearFormattedRemaining} remaining • Unbreakable",
                            color = Color(0xFFFCA5A5),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // Hero Focus Mode Status Banner
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (uiState.isStudyActive) Color(0xFF064E3B) else Color(0xFF1E1B4B)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(if (uiState.isStudyActive) Color(0xFF10B981) else TertiaryAmber)
                        )
                        Text(
                            text = if (uiState.isStudyActive) "FOCUS SHIELD ARMED" else "FOCUS MODE STANDBY",
                            color = if (uiState.isStudyActive) Color(0xFF34D399) else TertiaryAmber,
                            fontWeight = FontWeight.Black,
                            fontSize = 12.sp,
                            letterSpacing = 0.5.sp
                        )
                    }

                    FilterChip(
                        selected = uiState.isStudyActive,
                        onClick = onToggleStudyTimer,
                        label = {
                            Text(
                                text = if (uiState.isStudyActive) "Active" else "Arm Shield",
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF10B981),
                            selectedLabelColor = Color.White
                        )
                    )
                }

                if (uiState.isStudyActive) {
                    val mins = uiState.remainingSeconds / 60
                    val secs = uiState.remainingSeconds % 60
                    Text(
                        text = String.format(Locale.getDefault(), "%02d:%02d", mins, secs),
                        fontWeight = FontWeight.Black,
                        fontSize = 44.sp,
                        color = Color.White
                    )
                    Text(
                        text = "Current Subject: ${uiState.currentSubject}",
                        color = Color(0xFFD1FAE5),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    Text(
                        text = "Locked-In Focus Dashboard",
                        fontWeight = FontWeight.Black,
                        fontSize = 22.sp,
                        color = Color.White
                    )
                    Text(
                        text = "Distraction apps and YouTube Shorts locked. Study videos and focus tools active.",
                        color = Color(0xFFCBD5E1),
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }
        }

        // Daily Study Goal Progress Card (Customizable)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showEditDailyTargetDialog = true }
                .testTag("daily_target_progress_card"),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Filled.Flag, contentDescription = null, tint = PrimaryIndigo)
                        Text(
                            text = "Daily Deep Work Target",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "$todayCompletedMinutes / $dailyGoalMinutes min",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = PrimaryIndigo
                        )
                        IconButton(
                            onClick = { showEditDailyTargetDialog = true },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Edit Daily Target",
                                tint = PrimaryIndigo,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(CircleShape),
                    color = PrimaryIndigo,
                    trackColor = PrimaryIndigo.copy(alpha = 0.15f)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (goalProgress >= 1f) {
                            val surplus = todayCompletedMinutes - dailyGoalMinutes
                            if (surplus > 0) "🎉 Daily target achieved! +${surplus}m bonus focus." else "🎉 Daily target achieved! Fantastic discipline."
                        } else {
                            "${dailyGoalMinutes - todayCompletedMinutes}m remaining to reach target."
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "Customize ⚙️",
                        fontSize = 11.sp,
                        color = PrimaryIndigo,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 🔒 Custom App Locks & Auto-Lock Gates Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNavigateToLocks() }
                .testTag("dashboard_custom_locks_card"),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (activeAutoLockStatus != null) Color(0xFF78350F) else Color(0xFF1E1B4B)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(TertiaryAmber.copy(alpha = 0.25f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.LockClock,
                                contentDescription = null,
                                tint = TertiaryAmber,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Custom App Locks",
                                fontWeight = FontWeight.Black,
                                fontSize = 16.sp,
                                color = Color.White
                            )
                            Text(
                                text = "Auto-lock apps until study goal is met",
                                fontSize = 11.sp,
                                color = Color(0xFFCBD5E1)
                            )
                        }
                    }

                    Surface(
                        color = if (activeAutoLockStatus != null) Color(0xFFEF4444) else Color(0xFF10B981).copy(alpha = 0.25f),
                        shape = CircleShape
                    ) {
                        Text(
                            text = if (activeAutoLockStatus != null) "🔒 ACTIVE NOW" else "⏰ ${autoLockRules.count { it.isEnabled }} ACTIVE",
                            color = if (activeAutoLockStatus != null) Color.White else Color(0xFF34D399),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                if (activeAutoLockStatus != null) {
                    Surface(
                        color = Color(0xFF451A03),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Filled.Lock, contentDescription = null, tint = TertiaryAmber, modifier = Modifier.size(16.dp))
                            Text(
                                text = "Apps locked by '${activeAutoLockStatus.rule.name}'! Study ${activeAutoLockStatus.remainingStudyMinutes}m more today to unlock.",
                                color = Color(0xFFFEF3C7),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                } else {
                    val enabledRules = autoLockRules.filter { it.isEnabled }
                    if (enabledRules.isNotEmpty()) {
                        Text(
                            text = "Next schedule: ${enabledRules.first().name} (${enabledRules.first().daysFormatted} at ${enabledRules.first().formattedStartTime}, study ${enabledRules.first().formattedRequiredStudy} to unlock)",
                            color = Color(0xFFE2E8F0),
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    } else {
                        Text(
                            text = "Schedule auto-locks (e.g. Mon-Fri 8:30 AM with a 2-hour study requirement before distraction apps open).",
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }

                Button(
                    onClick = onNavigateToLocks,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dashboard_manage_locks_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = TertiaryAmber),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Open & Set Custom Locks 🔒", color = Color.Black, fontWeight = FontWeight.Black, fontSize = 13.sp)
                }
            }
        }

        // 4 Key Metrics Grid
        Text(
            text = "PERFORMANCE METRICS",
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.8.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DashboardMetricCard(
                icon = Icons.Filled.LocalFireDepartment,
                iconColor = Color(0xFFF97316),
                title = "Study Streak",
                value = "$streakDays Days",
                subtitle = "Consistency",
                modifier = Modifier.weight(1f)
            )
            DashboardMetricCard(
                icon = Icons.Filled.Bolt,
                iconColor = TertiaryAmber,
                title = "Focus Score",
                value = "$focusScore/100",
                subtitle = if (phoneSessions.isEmpty() && laptopFocusMinutes == 0L) "Base 85 • Tap info ℹ️" else "Tap for breakdown ℹ️",
                modifier = Modifier.weight(1f),
                onClick = { showFocusScoreDialog = true }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DashboardMetricCard(
                icon = Icons.Filled.Shield,
                iconColor = Color(0xFF10B981),
                title = "Distractions Blocked",
                value = "$totalDistractions",
                subtitle = "$shortsBlocked Shorts locked",
                modifier = Modifier.weight(1f)
            )
            DashboardMetricCard(
                icon = Icons.Filled.AccessTime,
                iconColor = PrimaryIndigo,
                title = "Total Focus",
                value = "${totalHours}h",
                subtitle = "📱 ${phoneFocusMinutes}m + 💻 ${laptopFocusMinutes}m",
                modifier = Modifier.weight(1f)
            )
        }

        // Friends Study League & Accountability Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF1E1B4B)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF6366F1).copy(alpha = 0.25f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Group, contentDescription = null, tint = Color(0xFFA5B4FC), modifier = Modifier.size(20.dp))
                        }
                        Column {
                            Text(
                                text = "Friends Study League",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = Color.White
                            )
                            Text(
                                text = "Track & compete with your study buddies",
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }
                    Surface(
                        color = Color(0xFF10B981).copy(alpha = 0.2f),
                        shape = CircleShape
                    ) {
                        Text(
                            text = "${friendsList.size + 1} Friends",
                            color = Color(0xFF34D399),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                Text(
                    text = "Compete with friends on daily study hours, compare streaks, and share your scorecards to stay accountable together!",
                    fontSize = 12.sp,
                    color = Color(0xFFCBD5E1),
                    lineHeight = 16.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { showFriendsDialog = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(vertical = 10.dp)
                    ) {
                        Icon(Icons.Filled.Leaderboard, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Leaderboard", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = { shareScorecard() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(vertical = 10.dp)
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Share Score", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Quick Focus Launchers
        Text(
            text = "QUICK FOCUS LAUNCHERS",
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.8.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            QuickLaunchChip(
                label = "25m Sprint",
                sub = "Pomodoro",
                icon = Icons.Filled.Timer,
                color = PrimaryIndigo,
                onClick = { onStartSprint(25, "Pomodoro Sprint") },
                modifier = Modifier.weight(1f)
            )
            QuickLaunchChip(
                label = "50m Block",
                sub = "Deep Work",
                icon = Icons.Filled.MenuBook,
                color = SecondaryTeal,
                onClick = { onStartSprint(50, "Deep Work Block") },
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            QuickLaunchChip(
                label = "4h Marathon",
                sub = "45m/15m × 4",
                icon = Icons.Filled.HourglassTop,
                color = TertiaryAmber,
                onClick = {
                    onStartMarathon(
                        MarathonConfig(
                            title = "4-Hour Focus Marathon",
                            studyDurationMinutes = 45,
                            breakDurationMinutes = 15,
                            totalCycles = 4
                        )
                    )
                },
                modifier = Modifier.weight(1f)
            )
            QuickLaunchChip(
                label = "Total Lockout ☢️",
                sub = "Unbreakable",
                icon = Icons.Filled.Lock,
                color = Color(0xFFDC2626),
                onClick = { showNuclearDashboardDialog = true },
                modifier = Modifier.weight(1f)
            )
        }

        // Active Protections & Shortcuts
        Text(
            text = "PROTECTIONS & SYNC STATUS",
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.8.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ProtectionRow(
                    icon = Icons.Filled.Alarm,
                    iconColor = TertiaryAmber,
                    title = "Smart Study Reminders",
                    status = "Scheduled morning, afternoon & stretch nudges",
                    actionLabel = "Nudges",
                    onAction = onOpenReminders
                )
                HorizontalDivider()
                ProtectionRow(
                    icon = Icons.Filled.Extension,
                    iconColor = Color(0xFF4285F4),
                    title = "Chrome Laptop Extension",
                    status = "Synced ($lastSyncFormatted)",
                    actionLabel = "Setup Guide",
                    onAction = onOpenChromeHelp
                )
                HorizontalDivider()
                ProtectionRow(
                    icon = Icons.Filled.LockClock,
                    iconColor = TertiaryAmber,
                    title = "Custom App Locks (Study-to-Unlock)",
                    status = if (activeAutoLockStatus != null) "🔒 Gate Active: Apps locked until studied ${activeAutoLockStatus.remainingStudyMinutes}m"
                             else "${autoLockRules.count { it.isEnabled }} active schedules (auto-locks apps on set days/times)",
                    actionLabel = "Open Locks",
                    onAction = onNavigateToLocks
                )
                HorizontalDivider()
                ProtectionRow(
                    icon = Icons.Filled.Smartphone,
                    iconColor = Color(0xFF10B981),
                    title = "App Shields & YouTube Shorts",
                    status = if (uiState.isYouTubeShortsBlocked) "Shorts Blocked 🛑" else "Shorts Allowed",
                    actionLabel = "Configure",
                    onAction = onNavigateToLocks
                )
                HorizontalDivider()
                ProtectionRow(
                    icon = Icons.Filled.SyncAlt,
                    iconColor = SecondaryTeal,
                    title = "Device Sync Hub",
                    status = "Pairing Code Ready",
                    actionLabel = "Open Sync",
                    onAction = onNavigateToSync
                )
            }
        }

        // Mini Audio Player Card if playing
        if (playerState.isPlaying || playerState.currentTrack != null) {
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onNavigateToMusic() },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = PrimaryIndigo.copy(alpha = 0.1f))
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Filled.MusicNote, contentDescription = null, tint = PrimaryIndigo)
                        Column {
                            Text(
                                text = playerState.currentTrack?.title ?: "Study Audio",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Text(
                                text = "Background focus audio playing",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(
                        text = "Music 🎵",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryIndigo
                    )
                }
            }
        }

        // Dialog: Custom Daily Target Editor
        if (showEditDailyTargetDialog) {
            EditDailyTargetDialog(
                initialMinutes = dailyGoalMinutes,
                onDismiss = { showEditDailyTargetDialog = false },
                onSave = { newMins ->
                    showEditDailyTargetDialog = false
                    onUpdateDailyGoalMinutes(newMins)
                }
            )
        }

        // Dialog: Nuclear Total Lockout Duration Selector
        if (showNuclearDashboardDialog) {
            val durations = listOf(15, 30, 45, 60, 90, 120)
            AlertDialog(
                onDismissRequest = { showNuclearDashboardDialog = false },
                icon = {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = Color(0xFFDC2626), modifier = Modifier.size(32.dp))
                },
                title = {
                    Text("☢️ Nuclear Total Lockout", fontWeight = FontWeight.Black, fontSize = 18.sp, color = Color(0xFFDC2626))
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Select lockout duration. All distracted apps will be sealed with NO early stop.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            durations.take(3).forEach { d ->
                                FilterChip(
                                    selected = nuclearMinutes == d,
                                    onClick = { nuclearMinutes = d },
                                    label = { Text("${d}m") },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            durations.drop(3).forEach { d ->
                                FilterChip(
                                    selected = nuclearMinutes == d,
                                    onClick = { nuclearMinutes = d },
                                    label = { Text("${d}m") },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showNuclearDashboardDialog = false
                            showNuclearWarningDialog = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Start Lockout ($nuclearMinutes min)", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showNuclearDashboardDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Dialog: Warning Dialogue Box for Total Lockout
        if (showNuclearWarningDialog) {
            AlertDialog(
                onDismissRequest = { showNuclearWarningDialog = false },
                icon = {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = Color(0xFFDC2626), modifier = Modifier.size(36.dp))
                },
                title = {
                    Text(
                        text = "TOTAL PHONE LOCKOUT WARNING",
                        fontWeight = FontWeight.Black,
                        fontSize = 17.sp,
                        color = Color(0xFFDC2626),
                        textAlign = TextAlign.Center
                    )
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "For the next $nuclearMinutes minutes, your phone is TOTALLY LOCKED to study mode.",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "• All distracted apps (YouTube Shorts, Social Media, Games) are 100% blocked.\n• System Settings & app uninstallation are locked.\n• You will NOT be able to close, cancel, or stop this session at all until the timer finishes!",
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Surface(
                            color = Color(0xFF450A0A),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "⛔ Once you click Continue, there is NO WAY to stop it.",
                                color = Color(0xFFFCA5A5),
                                fontWeight = FontWeight.Black,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(10.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showNuclearWarningDialog = false
                            onActivateNuclearLock(nuclearMinutes)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("confirm_nuclear_lock_button")
                    ) {
                        Text("Continue — Lock Me Out! ☢️", fontWeight = FontWeight.Black)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showNuclearWarningDialog = false },
                        modifier = Modifier.testTag("cancel_nuclear_lock_button")
                    ) {
                        Text("Cancel / Go Back", fontWeight = FontWeight.Bold)
                    }
                }
            )
        }

        // Dialog: Focus Score Explanation
        if (showFocusScoreDialog) {
            FocusScoreExplanationDialog(
                focusScore = focusScore,
                completedSessionsCount = phoneSessions.size + (laptopFocusMinutes / 30L).toInt(),
                totalDistractions = totalDistractions,
                onDismiss = { showFocusScoreDialog = false },
                onShare = {
                    showFocusScoreDialog = false
                    shareScorecard()
                }
            )
        }

        // Dialog: Friends Study League
        if (showFriendsDialog) {
            FriendsLeagueDialog(
                userEntry = FriendStudyEntry("You (Current Cadet)", (combinedFocusMinutes / 60f), streakDays, focusScore, isUser = true),
                friends = friendsList,
                onAddFriendClick = { showAddFriendDialog = true },
                onShareScore = { shareScorecard() },
                onDismiss = { showFriendsDialog = false }
            )
        }

        // Dialog: Add Study Buddy
        if (showAddFriendDialog) {
            AddFriendDialog(
                onDismiss = { showAddFriendDialog = false },
                onSave = { newFriend ->
                    friendsList = (friendsList + newFriend).distinctBy { it.name }
                    saveFriends(prefs, friendsList)
                    showAddFriendDialog = false
                    Toast.makeText(context, "Added ${newFriend.name} to Study League! 🏆", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}

@Composable
private fun DashboardMetricCard(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Card(
        modifier = if (onClick != null) modifier.clickable { onClick() } else modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(iconColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(18.dp))
            }
            Text(
                text = value,
                fontWeight = FontWeight.Black,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun QuickLaunchChip(
    label: String,
    sub: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.12f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Text(text = label, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = color)
            Text(text = sub, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProtectionRow(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    status: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(22.dp))
            Column {
                Text(text = title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(text = status, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(onClick = onAction) {
            Text(text = actionLabel, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun FocusScoreExplanationDialog(
    focusScore: Int,
    completedSessionsCount: Int,
    totalDistractions: Int,
    onDismiss: () -> Unit,
    onShare: () -> Unit
) {
    val bonus = (completedSessionsCount * 2).coerceAtMost(10)
    val penalty = (totalDistractions / 5).coerceAtMost(8)

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(Icons.Filled.Bolt, contentDescription = null, tint = TertiaryAmber, modifier = Modifier.size(32.dp))
        },
        title = {
            Text(
                text = "Focus Score: $focusScore / 100",
                fontWeight = FontWeight.Black,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    color = TertiaryAmber.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = if (completedSessionsCount == 0) "🔰 Cadet Starting Benchmark: 85/100" else "⚡ Active Flow State Score",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = TertiaryAmber
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (completedSessionsCount == 0) {
                                "New cadets start at 85/100 as an encouraging initial benchmark. As you complete study sessions, your score increases up to 100!"
                            } else {
                                "Your score reflects completed study marathons vs. distraction attempts deflected."
                            },
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp
                        )
                    }
                }

                Text(
                    text = "SCORE BREAKDOWN FORMULA",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.8.sp,
                    color = MaterialTheme.colorScheme.outline
                )

                ScoreRow(label = "Base Score", value = "88 pts", color = MaterialTheme.colorScheme.onSurface)
                ScoreRow(label = "Sessions Completed ($completedSessionsCount)", value = "+$bonus pts (+2/session)", color = Color(0xFF10B981))
                ScoreRow(label = "Distraction Triggers ($totalDistractions)", value = "-$penalty pts (-1/5 attempts)", color = Color(0xFFEF4444))
                HorizontalDivider()
                ScoreRow(label = "Calculated Focus Score", value = "$focusScore / 100", color = PrimaryIndigo, isBold = true)

                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "💡 Pro Tip: Complete 5 study sprints with zero distraction block triggers to reach a perfect 100/100 Grandmaster rank!",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp),
                        lineHeight = 15.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onShare,
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo)
            ) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Share Scorecard")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun ScoreRow(
    label: String,
    value: String,
    color: Color,
    isBold: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = 12.5.sp,
            fontWeight = if (isBold) FontWeight.Black else FontWeight.Bold,
            color = color
        )
    }
}

@Composable
private fun FriendsLeagueDialog(
    userEntry: FriendStudyEntry,
    friends: List<FriendStudyEntry>,
    onAddFriendClick: () -> Unit,
    onShareScore: () -> Unit,
    onDismiss: () -> Unit
) {
    val allEntries = remember(userEntry, friends) {
        (friends + userEntry).sortedByDescending { it.focusHours }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(Icons.Filled.Leaderboard, contentDescription = null, tint = PrimaryIndigo, modifier = Modifier.size(32.dp))
        },
        title = {
            Text(
                text = "Friends Study League",
                fontWeight = FontWeight.Black,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Compare study hours & streaks with your squad:",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                allEntries.forEachIndexed { index, entry ->
                    val rankMedal = when (index) {
                        0 -> "🥇"
                        1 -> "🥈"
                        2 -> "🥉"
                        else -> "#${index + 1}"
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (entry.isUser) PrimaryIndigo.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
                        ),
                        border = if (entry.isUser) androidx.compose.foundation.BorderStroke(1.5.dp, PrimaryIndigo) else null
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = rankMedal,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            text = entry.name,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (entry.isUser) {
                                            Surface(
                                                color = PrimaryIndigo,
                                                shape = CircleShape
                                            ) {
                                                Text("YOU", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                                            }
                                        }
                                    }
                                    Text(
                                        text = "${entry.streakDays}d streak • Score: ${entry.focusScore}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Text(
                                text = String.format(Locale.getDefault(), "%.1fh", entry.focusHours),
                                fontWeight = FontWeight.Black,
                                fontSize = 15.sp,
                                color = if (entry.isUser) PrimaryIndigo else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onAddFriendClick,
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo)
            ) {
                Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Add Buddy")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onShareScore) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Share", fontSize = 12.sp)
                }
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        }
    )
}

@Composable
private fun AddFriendDialog(
    onDismiss: () -> Unit,
    onSave: (FriendStudyEntry) -> Unit
) {
    var friendName by remember { mutableStateOf("") }
    var hoursInput by remember { mutableStateOf("3.0") }
    var streakInput by remember { mutableStateOf("3") }
    var scoreInput by remember { mutableStateOf("88") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Study Buddy 👤", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = friendName,
                    onValueChange = { friendName = it },
                    label = { Text("Friend's Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = hoursInput,
                    onValueChange = { hoursInput = it },
                    label = { Text("Weekly Study Hours (e.g. 4.5)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = streakInput,
                        onValueChange = { streakInput = it },
                        label = { Text("Streak (Days)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = scoreInput,
                        onValueChange = { scoreInput = it },
                        label = { Text("Score (0-100)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (friendName.isNotBlank()) {
                        val hours = hoursInput.toFloatOrNull() ?: 2.0f
                        val streak = streakInput.toIntOrNull() ?: 1
                        val score = (scoreInput.toIntOrNull() ?: 85).coerceIn(50, 100)
                        onSave(FriendStudyEntry(friendName.trim(), hours, streak, score))
                    }
                },
                enabled = friendName.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo)
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun loadFriends(prefs: android.content.SharedPreferences): List<FriendStudyEntry> {
    val jsonStr = prefs.getString("friends_json", null)
    if (jsonStr.isNullOrBlank()) {
        return listOf(
            FriendStudyEntry("Alex (Study Buddy)", 3.2f, 4, 88),
            FriendStudyEntry("Priyah (Exam Prep)", 4.5f, 7, 94),
            FriendStudyEntry("Jordan (Code Sprint)", 2.0f, 2, 80)
        )
    }
    return try {
        val arr = JSONArray(jsonStr)
        val list = mutableListOf<FriendStudyEntry>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(
                FriendStudyEntry(
                    name = obj.getString("name"),
                    focusHours = obj.getDouble("focusHours").toFloat(),
                    streakDays = obj.getInt("streakDays"),
                    focusScore = obj.getInt("focusScore")
                )
            )
        }
        list
    } catch (e: Exception) {
        listOf(
            FriendStudyEntry("Alex (Study Buddy)", 3.2f, 4, 88),
            FriendStudyEntry("Priyah (Exam Prep)", 4.5f, 7, 94),
            FriendStudyEntry("Jordan (Code Sprint)", 2.0f, 2, 80)
        )
    }
}

private fun saveFriends(prefs: android.content.SharedPreferences, list: List<FriendStudyEntry>) {
    try {
        val arr = JSONArray()
        list.forEach { f ->
            val obj = JSONObject()
            obj.put("name", f.name)
            obj.put("focusHours", f.focusHours.toDouble())
            obj.put("streakDays", f.streakDays)
            obj.put("focusScore", f.focusScore)
            arr.put(obj)
        }
        prefs.edit().putString("friends_json", arr.toString()).apply()
    } catch (e: Exception) {
        // ignore
    }
}

@Composable
private fun EditDailyTargetDialog(
    initialMinutes: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit
) {
    var selectedMinutes by remember { mutableIntStateOf(initialMinutes.coerceIn(15, 720)) }
    var customText by remember { mutableStateOf(selectedMinutes.toString()) }

    val presetGoals = listOf(90, 120, 180, 240, 300, 360, 480)

    val hours = selectedMinutes / 60
    val mins = selectedMinutes % 60
    val formattedTarget = when {
        hours > 0 && mins > 0 -> "${hours}h ${mins}m"
        hours > 0 -> "${hours} hrs"
        else -> "${mins} mins"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(PrimaryIndigo.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Flag,
                    contentDescription = null,
                    tint = PrimaryIndigo,
                    modifier = Modifier.size(26.dp)
                )
            }
        },
        title = {
            Text(
                text = "Daily Deep Work Target",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Personalize your daily deep work study goal. Track daily streaks and bonus focus time when you surpass it!",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )

                // Current Target Display Card
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = PrimaryIndigo.copy(alpha = 0.08f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, PrimaryIndigo.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = formattedTarget,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Black,
                            color = PrimaryIndigo
                        )
                        Text(
                            text = "($selectedMinutes total minutes per day)",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Quick Presets
                Text(
                    text = "Quick Presets:",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(presetGoals) { preset ->
                        val isSelected = preset == selectedMinutes
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                selectedMinutes = preset
                                customText = preset.toString()
                            },
                            label = {
                                val pHours = preset / 60
                                val pMins = preset % 60
                                val label = if (pMins == 0) "${pHours}h" else "${pHours}h ${pMins}m"
                                Text(
                                    text = "$label (${preset}m)",
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = PrimaryIndigo,
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }

                // Slider Control
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Fine Tuning", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${selectedMinutes} min", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = PrimaryIndigo)
                    }
                    Slider(
                        value = selectedMinutes.toFloat(),
                        onValueChange = {
                            val rounded = ((it / 15).toInt() * 15).coerceIn(15, 720)
                            selectedMinutes = rounded
                            customText = rounded.toString()
                        },
                        valueRange = 15f..720f,
                        steps = 46, // 15-min increments from 15 to 720
                        colors = SliderDefaults.colors(
                            thumbColor = PrimaryIndigo,
                            activeTrackColor = PrimaryIndigo
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // +/- Buttons & Manual Entry
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            selectedMinutes = (selectedMinutes - 15).coerceIn(15, 720)
                            customText = selectedMinutes.toString()
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("-15m", fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            selectedMinutes = (selectedMinutes + 15).coerceIn(15, 720)
                            customText = selectedMinutes.toString()
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("+15m", fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            selectedMinutes = (selectedMinutes + 30).coerceIn(15, 720)
                            customText = selectedMinutes.toString()
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("+30m", fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(selectedMinutes) },
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo)
            ) {
                Text("Save Target")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

