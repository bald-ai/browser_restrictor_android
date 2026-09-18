package com.michalkrsik.internetrestrictor.ui.challenge

import android.app.Activity
import android.content.Intent
import com.michalkrsik.internetrestrictor.domain.SUPPORTED_BROWSERS
import com.michalkrsik.internetrestrictor.domain.isProtectedTargetPackage
import kotlinx.coroutines.flow.MutableStateFlow

internal open class ChallengeVisibility {
    val active = MutableStateFlow(false)
    val activityStarted = MutableStateFlow(false)
    fun prepareLaunch() { activityStarted.value = false; active.value = true }
    fun onActivityStarted() { activityStarted.value = true; active.value = true }
    fun onActivityStopped() { activityStarted.value = false; active.value = false }
    fun cancelLaunch() { activityStarted.value = false; active.value = false }
}

internal fun shouldReturnAfterChallenge(returningToYoutube: Boolean, youtubeUnlocked: Boolean, sets: Long) =
    sets > 0 && (!returningToYoutube || youtubeUnlocked)

internal fun Activity.openChallengeOrigin(): Boolean {
    val preferred = intent.getStringExtra("return_package")?.takeIf(::isProtectedTargetPackage)
    val packages = listOfNotNull(preferred) + SUPPORTED_BROWSERS.map { it.packageName }
    val launch = packages.distinct().firstNotNullOfOrNull(packageManager::getLaunchIntentForPackage)
        ?: return false
    return try {
        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        true
    } catch (_: Exception) { false }
}

/** Keep the browser covered until the dashboard has submitted a visible frame. */
internal object TaskPickerTransition {
    val pending = MutableStateFlow(false)
}
