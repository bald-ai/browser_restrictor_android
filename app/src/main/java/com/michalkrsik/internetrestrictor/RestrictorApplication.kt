package com.michalkrsik.internetrestrictor

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import com.michalkrsik.internetrestrictor.monitoring.DeviceAccessMonitor

class RestrictorApplication : Application() {
    lateinit var container: AppContainer
        private set

    private lateinit var accessMonitor: DeviceAccessMonitor

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        accessMonitor = DeviceAccessMonitor(this, container)
        ProcessLifecycleOwner.get().lifecycle.addObserver(accessMonitor)
    }
}

val android.content.Context.appContainer: AppContainer
    get() = (applicationContext as RestrictorApplication).container

