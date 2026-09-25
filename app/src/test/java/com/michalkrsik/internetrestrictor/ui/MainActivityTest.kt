package com.michalkrsik.internetrestrictor.ui

import android.content.Intent
import android.os.Looper
import android.provider.Settings
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.michalkrsik.internetrestrictor.MainActivity
import com.michalkrsik.internetrestrictor.domain.*
import com.michalkrsik.internetrestrictor.fixtures.FixtureApplication
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp", application = FixtureApplication::class)
class MainActivityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = RuntimeEnvironment.getApplication() as FixtureApplication
    private fun click(text: String) { compose.onNodeWithText(text).performScrollTo().performClick() }
    private fun call(name: String) = compose.runOnIdle { ReflectionHelpers.callInstanceMethod<Unit>(compose.activity, name) }

    @Test fun dashboardSettingsThemeAndCustomizeUseSavedStateAndIntendedDestinations() {
        compose.onNodeWithText("Your day, on purpose.").assertIsDisplayed()
        click("Settings")
        compose.onNodeWithText("Dark appearance").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Dark appearance").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ThemePreference.LIGHT, app.container.repository.snapshot.value.settings.theme) }
        compose.onNodeWithContentDescription("Dark appearance").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ThemePreference.DARK, app.container.repository.snapshot.value.settings.theme) }
        compose.onNodeWithText("CUSTOMIZE TASKS & RULES").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("com.michalkrsik.internetrestrictor.v1.V1ConversationActivity", shadowOf(compose.activity).nextStartedActivity.component!!.className)
        }
        compose.onNodeWithText("Dashboard").performScrollTo().performClick()
        click("Change your app by voice")
        compose.runOnIdle { assertTrue(shadowOf(compose.activity).nextStartedActivity.component!!.className.endsWith("V1ConversationActivity")) }
    }

    @Test fun healthActionsProduceOnlyTheExpectedIntentsInTheShadowActivity() {
        val expected = mapOf(
            ProtectionHealth.READY to null,
            ProtectionHealth.ENFORCEMENT_OFF to null,
            ProtectionHealth.READY_LIMITED_RECOVERY to Settings.ACTION_USAGE_ACCESS_SETTINGS,
            ProtectionHealth.USAGE_ACCESS_RECOMMENDED to Settings.ACTION_USAGE_ACCESS_SETTINGS,
            ProtectionHealth.ACCESSIBILITY_REQUIRED to Settings.ACTION_ACCESSIBILITY_SETTINGS,
            ProtectionHealth.SERVICE_DISCONNECTED to Settings.ACTION_ACCESSIBILITY_SETTINGS,
            ProtectionHealth.RESTRICTED_SETTINGS_BLOCKED to Settings.ACTION_ACCESSIBILITY_SETTINGS,
            ProtectionHealth.STORAGE_ERROR to Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            ProtectionHealth.BROWSERS_NOT_FOUND to Intent.ACTION_VIEW,
            ProtectionHealth.ADVANCED_PROTECTION_BLOCKED to Intent.ACTION_VIEW,
        )
        compose.runOnIdle {
            for ((health, action) in expected) {
                ReflectionHelpers.callInstanceMethod<Unit>(compose.activity, "handleHealthAction", from(ProtectionHealth::class.java, health))
                val intent = shadowOf(compose.activity).nextStartedActivity
                assertEquals(health.name, action, intent?.action)
                if (health == ProtectionHealth.STORAGE_ERROR) assertEquals("package:${compose.activity.packageName}", intent.dataString)
                if (health == ProtectionHealth.ADVANCED_PROTECTION_BLOCKED) assertEquals("https://support.google.com/android/answer/16339980", intent.dataString)
            }
        }
        call("launchExerciseChallenge")
        compose.runOnIdle { assertTrue(shadowOf(compose.activity).nextStartedActivity.component!!.className.endsWith("ExerciseChallengeActivity")) }
        call("launchSprintChallenge")
        compose.runOnIdle { assertTrue(shadowOf(compose.activity).nextStartedActivity.component!!.className.endsWith("SprintChallengeActivity")) }
        call("launchBrowser")
        compose.runOnIdle { assertTrue(shadowOf(compose.activity).nextStartedActivity.dataString!!.startsWith("market://")) }
    }

    @Test fun configuredTaskCardsSelectGpsOrVideoAndUnconfiguredCardsRequestSync() {
        val task = app.container.repository.snapshot.value.configuration.tasks.first()
        click(task.name)
        compose.runOnIdle { assertNull(shadowOf(compose.activity).nextStartedActivity) }
        compose.runOnIdle { runBlocking { assertTrue(app.container.repository.applyConfiguration(AppConfiguration(revision = 1))) } }
        val tasks = app.container.repository.snapshot.value.configuration.tasks
        for (task in listOf(tasks.first { it.verifier == "video" }, tasks.first { it.verifier == "gps" })) {
            click(task.name)
            compose.runOnIdle {
                val intent = shadowOf(compose.activity).nextStartedActivity
                assertEquals(task.id, intent.getStringExtra("taskId"))
                assertTrue(intent.component!!.className.endsWith(if (task.verifier == "gps") "V1GpsActivity" else "V1VideoActivity"))
            }
        }
    }
}
