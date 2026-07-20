package com.michalkrsik.internetrestrictor

import android.content.Intent
import android.Manifest
import android.app.AlarmManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.michalkrsik.internetrestrictor.domain.HOUR_MS
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.michalkrsik.internetrestrictor.domain.CHROME_PACKAGE
import com.michalkrsik.internetrestrictor.domain.ProtectionHealth
import com.michalkrsik.internetrestrictor.domain.StateSnapshot
import com.michalkrsik.internetrestrictor.domain.ThemePreference
import com.michalkrsik.internetrestrictor.ui.dashboard.DashboardScreen
import com.michalkrsik.internetrestrictor.ui.onboarding.OnboardingScreen
import com.michalkrsik.internetrestrictor.ui.settings.SettingsScreen
import com.michalkrsik.internetrestrictor.ui.theme.RestrictorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val requestedDestination = mutableStateOf<String?>(null)
    private var notificationPermissionRequested = false
    private var exactAlarmPermissionRequested = false

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    private val diagnosticsExporter = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val text = appContainer.eventLog.exportText()
            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        requestedDestination.value = intent.getStringExtra(EXTRA_DESTINATION)
        setContent {
            val snapshot by appContainer.repository.snapshot.collectAsState()
            RestrictorTheme(snapshot.settings.theme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) {
                    ConfigureSystemBars(snapshot.settings.theme)
                    if (!snapshot.isLoaded) {
                        LoadingScreen()
                    } else {
                        RestrictorApp(snapshot)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshDeviceAccess()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedDestination.value = intent.getStringExtra(EXTRA_DESTINATION)
    }

    @Composable
    private fun RestrictorApp(snapshot: StateSnapshot) {
        var destination by rememberSaveable {
            mutableStateOf(
                if (!snapshot.settings.onboardingCompleted) DESTINATION_ONBOARDING
                else requestedDestination.value ?: DESTINATION_DASHBOARD,
            )
        }
        LaunchedEffect(requestedDestination.value, snapshot.settings.onboardingCompleted) {
            destination = if (!snapshot.settings.onboardingCompleted) {
                DESTINATION_ONBOARDING
            } else {
                requestedDestination.value ?: if (destination == DESTINATION_ONBOARDING) {
                    DESTINATION_DASHBOARD
                } else destination
            }
            requestedDestination.value = null
            requestNotificationPermissionIfNeeded(snapshot.settings.onboardingCompleted)
        }

        when (destination) {
            DESTINATION_ONBOARDING -> OnboardingScreen(
                snapshot = snapshot,
                onOpenAccessibility = ::openAccessibilitySettings,
                onOpenUsageAccess = ::openUsageAccessSettings,
                onOpenAppInfo = ::openAppInfo,
                onTestChrome = ::launchChrome,
                onComplete = {
                    lifecycleScope.launch {
                        if (appContainer.repository.completeOnboarding()) {
                            destination = DESTINATION_DASHBOARD
                        }
                    }
                },
            )
            DESTINATION_SETTINGS -> SettingsScreen(
                snapshot = snapshot,
                onBack = { destination = DESTINATION_DASHBOARD },
                onThemeChange = { theme ->
                    lifecycleScope.launch { appContainer.repository.setTheme(theme) }
                },
                onPauseProtection = { hours ->
                    requestExactAlarmPermissionIfNeeded()
                    lifecycleScope.launch {
                        appContainer.pauseProtection(hours?.let { it * HOUR_MS })
                    }
                },
                onResumeProtection = {
                    lifecycleScope.launch { appContainer.resumeProtection() }
                },
                onSave = { settings ->
                    lifecycleScope.launch {
                        appContainer.repository.saveSettings(snapshot.stateRevision, settings)
                    }
                },
                onResetUsage = {
                    lifecycleScope.launch { appContainer.repository.resetUsage() }
                },
                onClearReports = {
                    lifecycleScope.launch { appContainer.repository.clearReportHistory() }
                },
                onHealthAction = { handleHealthAction(snapshot.enforcementHealth) },
                onExportDiagnostics = {
                    diagnosticsExporter.launch("internet-restrictor-diagnostics.txt")
                },
            )
            else -> DashboardScreen(
                snapshot = snapshot,
                onOpenSettings = { destination = DESTINATION_SETTINGS },
                onTestChrome = ::launchChrome,
                onHealthAction = { handleHealthAction(snapshot.enforcementHealth) },
            )
        }
    }

    private fun refreshDeviceAccess() {
        val access = appContainer.accessChecker.read()
        lifecycleScope.launch {
            appContainer.repository.updateDeviceAccess(
                accessibilityEnabled = access.accessibilityEnabled,
                usageAccessGranted = access.usageAccessGranted,
                chromeInstalled = access.chromeInstalled,
                advancedProtectionEnabled = access.advancedProtectionEnabled,
            )
            appContainer.reconcileProtectionSchedule()
        }
    }

    private fun handleHealthAction(health: ProtectionHealth) {
        when (health) {
            ProtectionHealth.READY -> Unit
            ProtectionHealth.READY_LIMITED_RECOVERY,
            ProtectionHealth.USAGE_ACCESS_RECOMMENDED,
            -> openUsageAccessSettings()
            ProtectionHealth.ENFORCEMENT_OFF -> lifecycleScope.launch {
                appContainer.resumeProtection()
            }
            ProtectionHealth.ACCESSIBILITY_REQUIRED,
            ProtectionHealth.SERVICE_DISCONNECTED,
            ProtectionHealth.RESTRICTED_SETTINGS_BLOCKED,
            -> openAccessibilitySettings()
            ProtectionHealth.CHROME_NOT_FOUND -> openChromeListing()
            ProtectionHealth.STORAGE_ERROR -> openAppInfo()
            ProtectionHealth.ADVANCED_PROTECTION_BLOCKED -> startActivity(
                Intent(Intent.ACTION_VIEW, "https://support.google.com/android/answer/16339980".toUri()),
            )
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun requestNotificationPermissionIfNeeded(onboardingCompleted: Boolean) {
        if (!onboardingCompleted || notificationPermissionRequested) return
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionRequested = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestExactAlarmPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || exactAlarmPermissionRequested) return
        val alarmManager = getSystemService(AlarmManager::class.java)
        if (alarmManager.canScheduleExactAlarms()) return
        exactAlarmPermissionRequested = true
        startActivity(
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = "package:$packageName".toUri()
            },
        )
    }

    private fun openUsageAccessSettings() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            data = "package:$packageName".toUri()
        }
        startActivity(intent)
    }

    private fun openAppInfo() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = "package:$packageName".toUri()
            },
        )
    }

    private fun launchChrome() {
        val launch = packageManager.getLaunchIntentForPackage(CHROME_PACKAGE)
        if (launch != null) {
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            openChromeListing()
        }
    }

    private fun openChromeListing() {
        val market = Intent(Intent.ACTION_VIEW, "market://details?id=$CHROME_PACKAGE".toUri())
        val web = Intent(
            Intent.ACTION_VIEW,
            "https://play.google.com/store/apps/details?id=$CHROME_PACKAGE".toUri(),
        )
        try {
            startActivity(market)
        } catch (_: Exception) {
            startActivity(web)
        }
    }

    @Composable
    private fun ConfigureSystemBars(theme: ThemePreference) {
        val light = theme == ThemePreference.LIGHT
        SideEffect {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
        }
    }

    companion object {
        const val EXTRA_DESTINATION = "destination"
        const val DESTINATION_ONBOARDING = "onboarding"
        const val DESTINATION_DASHBOARD = "dashboard"
        const val DESTINATION_SETTINGS = "settings"
    }
}

@Composable
private fun LoadingScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}
