package com.michalkrsik.internetrestrictor.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.michalkrsik.internetrestrictor.appContainer
import com.michalkrsik.internetrestrictor.domain.*
import com.michalkrsik.internetrestrictor.ui.components.LastExerciseCard
import com.michalkrsik.internetrestrictor.ui.theme.RestrictorTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Emulator only: creates test receipts in the emulator's local store. */
class ExerciseCorrectionUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val repo get() = InstrumentationRegistry.getInstrumentation().targetContext.appContainer.repository

    @Test fun positiveResultCanBeEditedAndSavedFromLastSet() {
        runBlocking { repo.awaitLoaded(); repo.recordVerifiedExercise(UUID.randomUUID().toString(), ExerciseId.PUSH_UP, 1) }
        rule.setContent { val snapshot by repo.snapshot.collectAsState(); RestrictorTheme(ThemePreference.DARK) { LastExerciseCard(snapshot) } }
        rule.onNodeWithText("Wrong result?").performClick()
        rule.onNodeWithText("+3").performClick()
        rule.onNodeWithText("+1").performClick()
        rule.onNodeWithText("5 repetitions").assertIsDisplayed()
        rule.onNodeWithText("Save correction").performClick()
        rule.waitUntil(5_000) { repo.snapshot.value.lastExercise?.value == 5L }
        rule.onNodeWithText("5 repetitions").assertIsDisplayed()
        assertEquals(1L, repo.snapshot.value.lastExercise!!.originalValue)
        assertTrue(repo.snapshot.value.lastExercise!!.corrected)
        rule.onNodeWithText("Wrong result?").performClick()
        rule.onNodeWithText("Enter exact count").performScrollTo().performClick()
        rule.onNodeWithText("Exact count").performTextReplacement("-1")
        rule.onNodeWithText("Save correction").assertIsNotEnabled()
        rule.onNodeWithText("Exact count").performTextReplacement("0")
        rule.onNodeWithText("Save correction").performClick()
        rule.waitUntil(5_000) { repo.snapshot.value.lastExercise?.value == 0L }
        assertEquals(1L, repo.snapshot.value.lastExercise!!.originalValue)
        rule.onNodeWithText("Wrong result?").assertIsDisplayed()
    }

    @Test fun flagLeavesRewardsUnchanged() {
        runBlocking { repo.awaitLoaded(); repo.recordVerifiedExercise(UUID.randomUUID().toString(), ExerciseId.PLANK, 12) }
        val before = repo.snapshot.value.earnedBudgetRoundsToday
        rule.setContent { val snapshot by repo.snapshot.collectAsState(); RestrictorTheme(ThemePreference.DARK) { LastExerciseCard(snapshot) } }
        rule.onNodeWithText("Wrong result?").performClick()
        rule.onNodeWithText("Only flag as incorrect").performScrollTo().performClick()
        rule.waitUntil(5_000) { repo.snapshot.value.lastExercise?.flagged == true }
        assertEquals(before, repo.snapshot.value.earnedBudgetRoundsToday)
        assertEquals(12L, repo.snapshot.value.lastExercise!!.value)
        rule.onNodeWithText("Flagged as incorrect").assertIsDisplayed()
    }

    @Test fun noPositiveResultHasNoCorrectionEntry() {
        val engine = RestrictionEngine()
        val snapshot = engine.snapshot(PersistedState(), RuntimeSignals(), System.currentTimeMillis(), java.time.ZoneId.systemDefault())
        rule.setContent { RestrictorTheme(ThemePreference.DARK) { LastExerciseCard(snapshot) } }
        rule.onNodeWithText("Wrong result?").assertDoesNotExist()
    }
}
