package com.michalkrsik.internetrestrictor

import android.content.Context
import com.michalkrsik.internetrestrictor.data.RestrictorRepository
import com.michalkrsik.internetrestrictor.diagnostics.LocalEventLog
import com.michalkrsik.internetrestrictor.domain.AndroidTimeSource
import com.michalkrsik.internetrestrictor.monitoring.DeviceAccessChecker
import com.michalkrsik.internetrestrictor.notifications.ProtectionNotificationHelper
import com.michalkrsik.internetrestrictor.receivers.ProtectionScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val timeSource = AndroidTimeSource()
    val eventLog = LocalEventLog(context)
    val notificationHelper = ProtectionNotificationHelper(context)
    val protectionScheduler = ProtectionScheduler(context)
    val repository = RestrictorRepository(
        context = context,
        timeSource = timeSource,
        applicationScope = applicationScope,
        diagnosticSink = eventLog::record,
        offUsageNotificationSink = notificationHelper::showOffUsageReminder,
    )
    val accessChecker = DeviceAccessChecker(context)

    init {
        applicationScope.launch {
            repository.awaitLoaded()
            reconcileProtectionSchedule()
        }
    }

    suspend fun pauseProtection(durationMs: Long?) {
        if (repository.pauseProtection(durationMs)) {
            protectionScheduler.schedule(repository.snapshot.value.settings.pauseUntilWallMs)
        }
    }

    suspend fun resumeProtection() {
        repository.setEnforcementEnabled(true, allowCurrentVisit = true)
        protectionScheduler.schedule(null)
    }

    fun syncProtectionSchedule() {
        protectionScheduler.schedule(repository.snapshot.value.settings.pauseUntilWallMs)
    }

    suspend fun reconcileProtectionSchedule() {
        repository.reconcileProtectionSchedule()
        syncProtectionSchedule()
    }
}
