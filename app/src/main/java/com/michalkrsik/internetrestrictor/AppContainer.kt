package com.michalkrsik.internetrestrictor

import android.content.Context
import com.michalkrsik.internetrestrictor.data.RestrictorRepository
import com.michalkrsik.internetrestrictor.diagnostics.LocalEventLog
import com.michalkrsik.internetrestrictor.domain.AndroidTimeSource
import com.michalkrsik.internetrestrictor.monitoring.DeviceAccessChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(context: Context) {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val timeSource = AndroidTimeSource()
    val eventLog = LocalEventLog(context)
    val repository = RestrictorRepository(
        context = context,
        timeSource = timeSource,
        applicationScope = applicationScope,
        diagnosticSink = eventLog::record,
    )
    val accessChecker = DeviceAccessChecker(context)

}
