package com.michalkrsik.internetrestrictor.ui.sprint

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.michalkrsik.internetrestrictor.MainActivity

/** Compatibility entry point for old intents; recordings use the selected task. */
class SprintChallengeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_DESTINATION, MainActivity.DESTINATION_DASHBOARD))
        finish()
    }
}
