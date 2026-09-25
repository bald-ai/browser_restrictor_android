package com.michalkrsik.internetrestrictor.ui

import android.Manifest
import android.content.*
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.michalkrsik.internetrestrictor.domain.*
import com.michalkrsik.internetrestrictor.fixtures.FixtureApplication
import com.michalkrsik.internetrestrictor.ui.sprint.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.*
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 35], application = FixtureApplication::class, qualifiers = "w411dp-h891dp")
class SprintChallengeActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app = RuntimeEnvironment.getApplication() as FixtureApplication
    private val service = SprintLocationService { _, _, _ -> RewardSaveResult.StorageFailure }
    private val serviceController = ServiceController.of(service, Intent()).create()
    private lateinit var controller: ActivityController<SprintChallengeActivity>
    private lateinit var activity: SprintChallengeActivity
    private val location get() = app.getSystemService(LocationManager::class.java)
    @Before fun setup() {
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, SprintLocationService::class.java), service.onBind(null))
        shadowOf(location).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        controller = ActivityController.of(SprintChallengeActivity(configurableTasks = false), Intent(app, SprintChallengeActivity::class.java)).setup()
        activity = controller.get()
        tick(1)
    }
    @After fun close() { controller.pause().stop().destroy(); serviceController.destroy() }
    private fun tick(ms: Long = 1) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) }
    private fun call(name: String) { ReflectionHelpers.callInstanceMethod<Unit>(activity, name) }
    private fun start(remainder: Long = 0) { ReflectionHelpers.callInstanceMethod<Unit>(activity, "onStartSprint", from(Long::class.javaPrimitiveType, remainder)); tick() }
    private fun exit(action: () -> Unit) { ReflectionHelpers.callInstanceMethod<Unit>(activity, "requestExit", from(kotlin.jvm.functions.Function0::class.java, action as kotlin.jvm.functions.Function0<Unit>)) }
    private fun fix(meters: Double = 0.0) = Location("gps").apply {
        latitude = 50 + meters / 111195.0; longitude = 14.0; time = 1_790_250_000_000L + SystemClock.elapsedRealtime()
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(); accuracy = 5f; speed = 5f
    }
    private fun grant() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
        controller.pause().resume(); tick()
    }

    @Test fun retryableLocationDenialRequestsPermissionInsteadOfOpeningAppSettings() {
        activity.getPreferences(Context.MODE_PRIVATE).edit().putBoolean("locationRequested", true).commit()
        shadowOf(activity.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION, true)
        while (shadowOf(activity).nextStartedActivity != null) { }
        call("checkPermissions")
        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in shadowOf(activity).lastRequestedPermission.requestedPermissions)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun permissionCallbackUsesCurrentFineGrantAndProviderAvailability() {
        val callbacks = ReflectionHelpers.getField<Map<String, Any>>(activity.activityResultRegistry, "keyToCallback")
        val callback = ReflectionHelpers.getField<androidx.activity.result.ActivityResultCallback<Map<String, Boolean>>>(callbacks.values.single(), "callback")
        callback.onActivityResult(emptyMap())
        assertFalse(ReflectionHelpers.callInstanceMethod<Boolean>(activity, "getHasFineLocation"))
        shadowOf(location).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        callback.onActivityResult(mapOf(Manifest.permission.ACCESS_FINE_LOCATION to true))
        assertTrue(ReflectionHelpers.callInstanceMethod<Boolean>(activity, "getHasFineLocation"))
        assertFalse(ReflectionHelpers.callInstanceMethod<Boolean>(activity, "getGpsProviderEnabled"))
        grant()
        shadowOf(location).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        callback.onActivityResult(emptyMap())
        assertTrue(ReflectionHelpers.callInstanceMethod<Boolean>(activity, "getHasFineLocation"))
        assertTrue(ReflectionHelpers.callInstanceMethod<Boolean>(activity, "getGpsProviderEnabled"))
    }

    @Test fun permissionsRequestFineAndCoarseTogetherAndPermanentDenialLinksToAppSettings() {
        call("checkPermissions")
        val request = shadowOf(activity).lastRequestedPermission
        assertTrue(request.requestedPermissions.toList().containsAll(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)))
        assertEquals(android.os.Build.VERSION.SDK_INT >= 33, Manifest.permission.POST_NOTIFICATIONS in request.requestedPermissions)
        activity.activityResultRegistry.dispatchResult(request.requestCode, mapOf(Manifest.permission.ACCESS_FINE_LOCATION to false))
        call("checkPermissions")
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, shadowOf(activity).nextStartedActivity.action)
        grant()
        activity.activityResultRegistry.dispatchResult(request.requestCode, mapOf(Manifest.permission.ACCESS_FINE_LOCATION to true))
        val lastPermission = shadowOf(activity).lastRequestedPermission
        call("checkPermissions")
        assertSame(lastPermission, shadowOf(activity).lastRequestedPermission)
        val connection = ReflectionHelpers.getField<ServiceConnection>(activity, "serviceConnection")
        connection.onServiceDisconnected(null)
        connection.onServiceConnected(null, android.os.Binder())
        connection.onServiceConnected(null, service.onBind(null))
        assertTrue(ReflectionHelpers.getField<Boolean>(activity, "serviceBound"))
    }

    @Test fun recordingRequiresPermissionProviderAndFreshUsableFixAndFailedSaveRequiresDiscard() {
        start(); assertFalse(service.isCurrentlyRecording())
        grant()
        shadowOf(location).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        start(); assertFalse(service.isCurrentlyRecording())
        shadowOf(location).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        start(); assertFalse(service.isCurrentlyRecording())
        service.onLocationChanged(fix()); tick(5001)
        start(); assertFalse(service.isCurrentlyRecording())
        service.onLocationChanged(fix()); start(100)
        assertTrue(service.isCurrentlyRecording())
        var exits = 0
        exit { exits++ }
        compose.onNodeWithText("Discard sprint progress?").assertExists()
        compose.onNodeWithText("Keep sprint").performClick()
        assertEquals(0, exits)
        for (n in 0..4) { tick(1000); service.onLocationChanged(fix(n * 5.0)) }
        call("onStopSprint"); tick()
        assertFalse(service.isCurrentlyRecording())
        assertTrue(service.saveState!!.result.qualifyingDistanceMeters > 0)
        assertFalse(service.saveState!!.saveSucceeded)
        call("onConfirmManualCompletion"); tick()
        exit { exits++ }
        compose.onNodeWithText("Discard").performClick()
        assertEquals(1, exits)
        assertNull(ReflectionHelpers.getField<Any?>(activity, "afterDiscard"))
    }

    @Test fun inFlightSaveBlocksExitWhileAnEmptyResultCanCloseImmediately() {
        var exits = 0
        exit { exits++ }; assertEquals(1, exits)
        val result = SprintEvaluationResult(0, 0.0, 0, 0, true, 0.0, 0)
        ReflectionHelpers.callInstanceMethod<Unit>(service, "setSaveState", from(SprintSaveState::class.java, SprintSaveState("pending", result, isSaving = true)))
        exit { exits++ }; assertEquals(1, exits)
        ReflectionHelpers.callInstanceMethod<Unit>(service, "setSaveState", from(SprintSaveState::class.java, SprintSaveState("empty", result)))
        exit { exits++ }; assertEquals(2, exits)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onContinue", from(Boolean::class.javaPrimitiveType, false), from(Boolean::class.javaPrimitiveType, false), from(Long::class.javaPrimitiveType, 0L))
        assertTrue(activity.isFinishing)
    }
}
