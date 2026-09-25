package com.michalkrsik.internetrestrictor.ui

import android.app.Application
import com.michalkrsik.internetrestrictor.MainActivity
import com.michalkrsik.internetrestrictor.ui.challenge.ExerciseChallengeActivity
import com.michalkrsik.internetrestrictor.ui.sprint.SprintChallengeActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LegacyTaskRoutingTest {
    @Test fun oldExerciseAndSprintEntriesRedirectToTaskPicker() {
        for (type in listOf(ExerciseChallengeActivity::class.java, SprintChallengeActivity::class.java)) {
            val controller = Robolectric.buildActivity(type).create()
            try {
                val activity = controller.get()
                val intent = shadowOf(activity).nextStartedActivity
                assertEquals(MainActivity::class.java.name, intent.component!!.className)
                assertEquals(MainActivity.DESTINATION_DASHBOARD, intent.getStringExtra(MainActivity.EXTRA_DESTINATION))
                assertTrue(activity.isFinishing)
                assertNull(shadowOf(activity).nextStartedActivity)
                assertNull(shadowOf(activity).nextStartedService)
            } finally { controller.destroy() }
        }
    }
}
