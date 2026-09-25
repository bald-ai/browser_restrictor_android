package com.michalkrsik.internetrestrictor.ui.sprint

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.michalkrsik.internetrestrictor.AppContainer
import com.michalkrsik.internetrestrictor.RestrictorApplication
import com.michalkrsik.internetrestrictor.domain.EXERCISE_SET_REWARD_MINUTES
import com.michalkrsik.internetrestrictor.domain.SPRINT_INTENDED_METERS
import com.michalkrsik.internetrestrictor.domain.SPRINT_SET_QUALIFYING_METERS
import com.michalkrsik.internetrestrictor.domain.SprintLiveState
import com.michalkrsik.internetrestrictor.domain.YOUTUBE_PACKAGE_NAME
import com.michalkrsik.internetrestrictor.domain.YOUTUBE_UNLOCK_SETS
import com.michalkrsik.internetrestrictor.ui.challenge.openChallengeOrigin
import com.michalkrsik.internetrestrictor.ui.challenge.shouldReturnAfterChallenge
import com.michalkrsik.internetrestrictor.ui.theme.EditorialTheme
import com.michalkrsik.internetrestrictor.ui.theme.PillShape
import com.michalkrsik.internetrestrictor.ui.theme.PulseActionBorder
import com.michalkrsik.internetrestrictor.ui.theme.PulseText
import com.michalkrsik.internetrestrictor.ui.theme.PulseYellow
import com.michalkrsik.internetrestrictor.ui.theme.RestrictorTheme
import com.michalkrsik.internetrestrictor.ui.theme.pulseButtonColors
import java.util.Locale
import kotlinx.coroutines.launch

class SprintChallengeActivity internal constructor(
    private val configurableTasks: Boolean = com.michalkrsik.internetrestrictor.BuildConfig.IS_V1,
) : ComponentActivity() {

    private val appContainer: AppContainer
        get() = (application as RestrictorApplication).container

    private var sprintService by mutableStateOf<SprintLocationService?>(null)
    private var serviceBound = false
    private var bindingRequested = false

    private val phase: SprintPhase get() = sprintService?.phase ?: SprintPhase.ACQUIRING_GPS
    private val currentSaveState: SprintSaveState? get() = sprintService?.saveState
    private var confirmDiscard by mutableStateOf(false)
    private var afterDiscard: (() -> Unit)? = null
    private var hasFineLocation by mutableStateOf(false)
    private var gpsProviderEnabled by mutableStateOf(false)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? SprintLocationService.LocalBinder)?.service
            sprintService = service
            serviceBound = true
            if (hasFineLocation && isGpsEnabled()) {
                service?.startListeningLocation()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            sprintService = null
            serviceBound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        hasFineLocation = fineLocationGranted
        gpsProviderEnabled = isGpsEnabled()
        if (fineLocationGranted && gpsProviderEnabled) {
            sprintService?.startListeningLocation()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (configurableTasks) {
            startActivity(Intent(this, com.michalkrsik.internetrestrictor.MainActivity::class.java).putExtra(com.michalkrsik.internetrestrictor.MainActivity.EXTRA_DESTINATION, com.michalkrsik.internetrestrictor.MainActivity.DESTINATION_DASHBOARD))
            finish()
            return
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)

        hasFineLocation = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        gpsProviderEnabled = isGpsEnabled()

        val serviceIntent = Intent(this, SprintLocationService::class.java)
        bindingRequested = bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        setContent {
            val snapshot by appContainer.repository.snapshot.collectAsState()
            val service = sprintService
            val liveState by (service?.liveState?.collectAsState() ?: remember {
                mutableStateOf(SprintLiveState.INITIAL)
            })

            // Disallow accidental navigation while reward saving is in flight
            BackHandler {
                if (currentSaveState?.isSaving != true) requestExit { finish() }
            }
            if (confirmDiscard) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { confirmDiscard = false },
                    title = { Text("Discard sprint progress?") },
                    text = { Text("This recording has progress that has not been saved.") },
                    confirmButton = { androidx.compose.material3.TextButton(onClick = {
                        confirmDiscard = false
                        afterDiscard?.invoke()
                        afterDiscard = null
                    }) { Text("Discard") } },
                    dismissButton = { androidx.compose.material3.TextButton(onClick = {
                        confirmDiscard = false
                    }) { Text("Keep sprint") } },
                )
            }

            RestrictorTheme(snapshot.settings.theme) {
                SprintChallengeScreen(
                    model = SprintScreenState(phase, currentSaveState, hasFineLocation, gpsProviderEnabled,
                        isFixFresh(liveState), intent.getStringExtra(EXTRA_RETURN_PACKAGE) == YOUTUBE_PACKAGE_NAME),
                    actions = SprintScreenActions(
                        close = { requestExit { finish() } }, permissions = ::checkPermissions,
                        locationSettings = { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                        start = ::onStartSprint, stop = ::onStopSprint,
                        confirmManual = ::onConfirmManualCompletion, retry = { sprintService?.saveReward() },
                        continueAfterResult = ::onContinue,
                        again = { remainder -> requestExit { sprintService?.resetForAcquiringGps(remainder) } },
                    ),
                    snapshotRemainder = snapshot.sprintMetersRemainder,
                    earnedSetsToday = snapshot.earnedBudgetRoundsToday,
                    liveState = liveState,
                )
            }
        }

        if (savedInstanceState == null && !getPreferences(Context.MODE_PRIVATE).getBoolean("locationRequested", false)) checkPermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!intent.hasExtra(EXTRA_RETURN_PACKAGE)) {
            this.intent.getStringExtra(EXTRA_RETURN_PACKAGE)?.let {
                intent.putExtra(EXTRA_RETURN_PACKAGE, it)
            }
        }
        setIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        SprintChallengeVisibility.onActivityStarted()
    }

    override fun onResume() {
        super.onResume()
        hasFineLocation = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        gpsProviderEnabled = isGpsEnabled()

        if (hasFineLocation && gpsProviderEnabled) {
            sprintService?.startListeningLocation()
        } else {
            sprintService?.resetGpsReadiness()
        }
    }

    override fun onStop() {
        SprintChallengeVisibility.onActivityStopped()
        super.onStop()
    }

    override fun onDestroy() {
        if (bindingRequested) {
            unbindService(serviceConnection)
            bindingRequested = false
            serviceBound = false
        }
        if (isFinishing) stopService(Intent(this, SprintLocationService::class.java))
        super.onDestroy()
    }

    private fun requestExit(action: () -> Unit) {
        if (currentSaveState?.isSaving == true) return
        val pending = currentSaveState
        if (phase == SprintPhase.RECORDING || (pending != null && !pending.saveSucceeded &&
                (pending.result.qualifyingDistanceMeters > 0 || !pending.result.isGpsReliable))) {
            afterDiscard = action
            confirmDiscard = true
        } else action()
    }

    private fun checkPermissions() {
        val fineGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

        val preferences = getPreferences(Context.MODE_PRIVATE)
        if (!fineGranted && preferences.getBoolean("locationRequested", false) &&
            !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:$packageName")))
            return
        }
        val permissionsToRequest = mutableListOf<String>()
        if (!fineGranted) {
            // Android 12+ requires requesting FINE and COARSE together, even when upgrading
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!notifGranted) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissionsToRequest.isNotEmpty()) {
            preferences.edit().putBoolean("locationRequested", true).apply()
            permissionLauncher.launch(permissionsToRequest.distinct().toTypedArray())
        }
    }

    private fun isGpsEnabled(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    private fun isFixFresh(liveState: SprintLiveState): Boolean {
        val now = SystemClock.elapsedRealtime()
        return liveState.lastFixElapsedRealtimeMs > 0L &&
            (now - liveState.lastFixElapsedRealtimeMs) <= 5000L
    }

    private fun onStartSprint(startingRemainder: Long) {
        val fineGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val gpsEnabled = isGpsEnabled()
        val currentLive = sprintService?.liveState?.value ?: SprintLiveState.INITIAL
        val fresh = isFixFresh(currentLive)
        val usable = currentLive.isGpsUsable && fresh

        hasFineLocation = fineGranted
        gpsProviderEnabled = gpsEnabled

        if (!fineGranted || !gpsEnabled || !usable) {
            if (!fineGranted) {
                checkPermissions()
            }
            return
        }

        sprintService?.startRecording(startingRemainder)
    }

    private fun onStopSprint() { sprintService?.stopRecording() }

    private fun onConfirmManualCompletion() { sprintService?.saveReward(manual = true) }

    private fun onContinue(returningToYoutube: Boolean, youtubeUnlocked: Boolean, newlyEarned: Long) {
        requestExit {
            if (shouldReturnAfterSprintResult(returningToYoutube, youtubeUnlocked, newlyEarned)) returnToOrigin()
            else finish()
        }
    }

    private fun returnToOrigin() {
        openChallengeOrigin()
        finish()
    }

    companion object {
        const val EXTRA_RETURN_PACKAGE = "return_package"
    }
}

internal fun shouldReturnAfterSprintResult(
    returningToYoutube: Boolean,
    youtubeUnlocked: Boolean,
    newlyEarnedRounds: Long,
): Boolean = shouldReturnAfterChallenge(returningToYoutube, youtubeUnlocked, newlyEarnedRounds)
