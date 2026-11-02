package com.example.ui.lock

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.MainActivity
import com.example.R
import com.example.service.StudyFocusService
import com.example.ui.theme.PrimaryIndigo
import com.example.ui.theme.PrimaryIndigoLight
import com.example.ui.theme.SecondaryTeal
import com.example.ui.theme.StudyGuardTheme
import com.example.ui.theme.TertiaryAmber
import com.example.util.StudyPreferences
import kotlinx.coroutines.delay

class BlockedOverlayActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        @Suppress("DEPRECATION")
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        // Strict Enforcement: Pressing Back must NEVER reveal the distraction app!
        // Direct the user safely to the Home Launcher.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(homeIntent)
                finish()
            }
        })

        renderBlockedUi()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        renderBlockedUi()
    }

    private fun renderBlockedUi() {
        val appName = intent.getStringExtra(EXTRA_BLOCKED_APP_NAME)
            ?: StudyPreferences.getLastBlockedApp(this)
        val reason = StudyPreferences.getLastBlockedReason(this)
        val isScheduled = intent.getBooleanExtra(EXTRA_IS_SCHEDULED_LOCK, false)
        val ruleName = intent.getStringExtra(EXTRA_RULE_NAME) ?: "Scheduled Study Gate"
        val remainingStudyMins = intent.getIntExtra(EXTRA_REMAINING_STUDY_MINS, 60)
        val requiredStudyMins = intent.getIntExtra(EXTRA_REQUIRED_STUDY_MINS, 120)
        val studiedMins = intent.getIntExtra(EXTRA_STUDIED_MINS, 0)
        val isNuclear = intent.getBooleanExtra(EXTRA_IS_NUCLEAR_LOCK, false) ||
                com.example.util.EmergencyLockManager.getInstance(this).isNuclearLockActive()
        val isAppLimit = intent.getBooleanExtra(EXTRA_IS_APP_LIMIT, false)
        val isCooldown = intent.getBooleanExtra(EXTRA_IS_COOLDOWN, false)
        val limitMins = intent.getIntExtra(EXTRA_LIMIT_MINS, 0)
        val usedMins = intent.getIntExtra(EXTRA_USED_MINS, 0)
        val cooldownRemainingSec = intent.getLongExtra(EXTRA_COOLDOWN_REMAINING_SEC, 0L)

        val themeMode = StudyPreferences.getThemeMode(this)

        setContent {
            StudyGuardTheme(themeMode = themeMode) {
                BlockedScreenContent(
                    blockedAppName = appName,
                    blockedReason = reason,
                    isScheduledLock = isScheduled,
                    isNuclearLock = isNuclear,
                    isAppLimitLock = isAppLimit,
                    isCooldownLock = isCooldown,
                    appLimitMins = limitMins,
                    appUsedMins = usedMins,
                    initialCooldownRemainingSec = cooldownRemainingSec,
                    ruleName = ruleName,
                    remainingStudyMins = remainingStudyMins,
                    requiredStudyMins = requiredStudyMins,
                    studiedMins = studiedMins,
                    onReturnToStudy = {
                        val mainIntent = Intent(this, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        startActivity(mainIntent)
                        finish()
                    },
                    onOpenYouTubeStudy = {
                        val ytIntent = packageManager.getLaunchIntentForPackage("com.google.android.youtube")
                        if (ytIntent != null) {
                            startActivity(ytIntent)
                        }
                        finish()
                    },
                    onEmergencyUnlock = {
                        StudyFocusService.stop(this)
                        StudyPreferences.stopStudySession(this)
                        finish()
                    }
                )
            }
        }
    }

    companion object {
        const val EXTRA_BLOCKED_APP_NAME = "extra_blocked_app_name"
        const val EXTRA_IS_SCHEDULED_LOCK = "extra_is_scheduled_lock"
        const val EXTRA_IS_NUCLEAR_LOCK = "extra_is_nuclear_lock"
        const val EXTRA_RULE_NAME = "extra_rule_name"
        const val EXTRA_REMAINING_STUDY_MINS = "extra_remaining_study_mins"
        const val EXTRA_REQUIRED_STUDY_MINS = "extra_required_study_mins"
        const val EXTRA_STUDIED_MINS = "extra_studied_mins"
        const val EXTRA_IS_APP_LIMIT = "extra_is_app_limit"
        const val EXTRA_IS_COOLDOWN = "extra_is_cooldown"
        const val EXTRA_LIMIT_MINS = "extra_limit_mins"
        const val EXTRA_USED_MINS = "extra_used_mins"
        const val EXTRA_COOLDOWN_REMAINING_SEC = "extra_cooldown_remaining_sec"
    }
}

@Composable
fun BlockedScreenContent(
    blockedAppName: String,
    blockedReason: String,
    isScheduledLock: Boolean,
    isNuclearLock: Boolean = false,
    isAppLimitLock: Boolean = false,
    isCooldownLock: Boolean = false,
    appLimitMins: Int = 0,
    appUsedMins: Int = 0,
    initialCooldownRemainingSec: Long = 0L,
    ruleName: String,
    remainingStudyMins: Int,
    requiredStudyMins: Int,
    studiedMins: Int,
    onReturnToStudy: () -> Unit,
    onOpenYouTubeStudy: () -> Unit,
    onEmergencyUnlock: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var remainingSeconds by remember { mutableStateOf(0L) }
    var cooldownSec by remember(initialCooldownRemainingSec) { mutableStateOf(initialCooldownRemainingSec) }
    val subject = remember { StudyPreferences.getSessionSubject(context) }
    var showEmergencyDialog by remember { mutableStateOf(false) }

    // Live countdown updater for active timer session or cooldown
    LaunchedEffect(isScheduledLock, isNuclearLock, isCooldownLock) {
        while (true) {
            if (isCooldownLock) {
                if (cooldownSec > 0) {
                    cooldownSec--
                }
            } else {
                val left = if (isNuclearLock) {
                    com.example.util.EmergencyLockManager.getInstance(context).checkStatus().remainingSeconds
                } else if (!isScheduledLock && !isAppLimitLock) {
                    val endTime = StudyPreferences.getSessionEndTime(context)
                    val now = System.currentTimeMillis()
                    (endTime - now) / 1000L
                } else 0L

                remainingSeconds = left.coerceAtLeast(0L)
                if (!isScheduledLock && !isNuclearLock && !isAppLimitLock && left <= 0) break
            }
            delay(1000L)
        }
    }

    val minutes = remainingSeconds / 60
    val seconds = remainingSeconds % 60
    val formattedTime = String.format("%02d:%02d", minutes, seconds)

    val cdH = cooldownSec / 3600
    val cdM = (cooldownSec % 3600) / 60
    val cdS = cooldownSec % 60
    val formattedCooldown = if (cdH > 0) {
        String.format("%02d:%02d:%02d", cdH, cdM, cdS)
    } else {
        String.format("%02d:%02d", cdM, cdS)
    }

    val quotes = remember {
        listOf(
            "“The secret of getting ahead is getting started.” — Mark Twain",
            "“Discipline is choosing between what you want now and what you want most.”",
            "“Small daily efforts add up to massive results. Protect your focus!”",
            "“Distraction is the thief of ambition. Stay locked in!”"
        )
    }
    val randomQuote = remember { quotes.random() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF0F172A),
                        Color(0xFF1E1B4B),
                        Color(0xFF0F172A)
                    )
                )
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Mascot & Lock Badge
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .padding(2.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.luffy_study_image_1790402150695),
                    contentDescription = "Focus Mascot",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .align(Alignment.BottomEnd)
                        .clip(CircleShape)
                        .background(Color(0xFFEF4444)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Locked",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "$blockedAppName is Locked",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (isScheduledLock) {
                    "Auto-Lock Rule: $ruleName"
                } else {
                    blockedReason
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF94A3B8),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (isCooldownLock) {
                // 2-Hour Half-Time Cooldown Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF1E1B4B)
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, TertiaryAmber.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            color = TertiaryAmber.copy(alpha = 0.2f),
                            shape = CircleShape
                        ) {
                            Text(
                                text = "⏸️ HALF-TIME FOCUS COOLDOWN",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Black,
                                color = TertiaryAmber,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                                letterSpacing = 1.1.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = formattedCooldown,
                            fontSize = 44.sp,
                            fontWeight = FontWeight.Black,
                            color = TertiaryAmber
                        )

                        Text(
                            text = "Cooldown time remaining",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8)
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "You reached ${appUsedMins}m of your ${appLimitMins}m daily limit.\nTo prevent compulsive scrolling, this app is locked for a 2-hour cooldown before you can use the remaining ${appLimitMins - appUsedMins} minutes.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                }
            } else if (isAppLimitLock) {
                // Daily App Limit Exceeded Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF450A0A).copy(alpha = 0.8f)
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.6f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            color = Color(0xFFEF4444).copy(alpha = 0.25f),
                            shape = CircleShape
                        ) {
                            Text(
                                text = "⏳ DAILY APP LIMIT EXHAUSTED",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFFFCA5A5),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                                letterSpacing = 1.1.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = "${appUsedMins}m / ${appLimitMins}m",
                            fontSize = 40.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFFFCA5A5)
                        )

                        Text(
                            text = "All daily allowance used today",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFCBD5E1)
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "Locked until tomorrow morning (6:00 AM).\nTo protect your discipline, timer limits cannot be bypassed or modified until the next day.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                }
            } else if (isScheduledLock) {
                // Scheduled Auto-Lock Card: "First study for X hours/minutes, then app will unlock"
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF1E293B)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            color = TertiaryAmber.copy(alpha = 0.2f),
                            shape = CircleShape
                        ) {
                            Text(
                                text = "STUDY-TO-UNLOCK GATE",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Black,
                                color = TertiaryAmber,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                letterSpacing = 1.1.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        val displayHours = remainingStudyMins / 60
                        val displayMins = remainingStudyMins % 60
                        val timeStr = when {
                            displayHours > 0 && displayMins > 0 -> "${displayHours}h ${displayMins}m"
                            displayHours > 0 -> "${displayHours} Hours"
                            else -> "$displayMins Minutes"
                        }

                        Text(
                            text = timeStr,
                            fontSize = 38.sp,
                            fontWeight = FontWeight.Black,
                            color = TertiaryAmber
                        )

                        Text(
                            text = "First study for $timeStr to unlock this app!",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        val progress = if (requiredStudyMins > 0) {
                            (studiedMins.toFloat() / requiredStudyMins.toFloat()).coerceIn(0f, 1f)
                        } else 0f

                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = SecondaryTeal,
                            trackColor = Color(0xFF334155)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Studied today: $studiedMins mins / $requiredStudyMins mins required",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }
            } else {
                // Active Study Session Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF1E293B)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "ACTIVE STUDY SESSION",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = PrimaryIndigoLight,
                            letterSpacing = 1.2.sp
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = subject,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = formattedTime,
                            fontSize = 44.sp,
                            fontWeight = FontWeight.Black,
                            color = TertiaryAmber
                        )

                        Text(
                            text = "Time remaining until distraction unlocks",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Motivational quote card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF1E293B).copy(alpha = 0.6f)
                )
            ) {
                Text(
                    text = randomQuote,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFCBD5E1),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Primary Action Button: Start Study Session Now
            Button(
                onClick = onReturnToStudy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("start_study_unlock_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryIndigo
                )
            ) {
                Icon(Icons.Default.MenuBook, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isScheduledLock) "Start Study Session Now 🚀" else "Return to StudyGuard",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // YouTube Educational video alternative
            OutlinedButton(
                onClick = onOpenYouTubeStudy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("open_youtube_study_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = SecondaryTeal
                )
            ) {
                Icon(Icons.Default.OndemandVideo, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Open YouTube Study Videos (Shorts Blocked)",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Emergency Unlock option (Disabled in Nuclear, Cooldown, or Daily App Limit Lockout)
            if (isNuclearLock || isCooldownLock || isAppLimitLock) {
                val lockNotice = when {
                    isNuclearLock -> "☢️ Nuclear Lockout: Emergency pass disabled"
                    isCooldownLock -> "⏸️ Focus Cooldown Active: Locked until break period finishes"
                    else -> "⏳ Daily Limit Reached: Locked until tomorrow morning (6:00 AM)"
                }
                Surface(
                    color = Color(0xFF450A0A),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFDC2626)),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFFFCA5A5), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = lockNotice,
                            color = Color(0xFFFCA5A5),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                TextButton(
                    onClick = { showEmergencyDialog = true },
                    modifier = Modifier.testTag("emergency_unlock_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Emergency Unlock Pass",
                        color = Color(0xFF94A3B8),
                        fontSize = 13.sp
                    )
                }
            }
        }
    }

    if (showEmergencyDialog) {
        AlertDialog(
            onDismissRequest = { showEmergencyDialog = false },
            title = {
                Text(
                    text = "Emergency Unlock?",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Breaking your study commitment resets your daily streak and logs a distraction event. Are you sure you want to unlock early?"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showEmergencyDialog = false
                        onEmergencyUnlock()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                ) {
                    Text("Unlock Anyway")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showEmergencyDialog = false }) {
                    Text("Keep Studying")
                }
            }
        )
    }
}
