package com.michalkrsik.internetrestrictor.ui.challenge

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.michalkrsik.internetrestrictor.MainActivity
import org.junit.Assert.*
import org.junit.Test

class ExerciseTaskRoutingTest {
    @Test fun oldCameraEntryOpensTaskDashboardWithoutRecording() {

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var destination: String? = null
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.component?.className != MainActivity::class.java.name) return null
                destination = intent.getStringExtra(MainActivity.EXTRA_DESTINATION)
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            instrumentation.startActivitySync(Intent(instrumentation.targetContext, ExerciseChallengeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.waitForIdleSync()
            assertEquals(MainActivity.DESTINATION_DASHBOARD, destination)
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }
}
