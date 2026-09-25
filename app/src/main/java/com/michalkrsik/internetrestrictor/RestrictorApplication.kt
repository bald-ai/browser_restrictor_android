package com.michalkrsik.internetrestrictor

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import com.michalkrsik.internetrestrictor.monitoring.DeviceAccessMonitor

open class RestrictorApplication : Application() {
    lateinit var container: AppContainer
        internal set

    private lateinit var accessMonitor: DeviceAccessMonitor

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        if (BuildConfig.IS_V1) container.v1.start()
        accessMonitor = DeviceAccessMonitor(this, container)
        ProcessLifecycleOwner.get().lifecycle.addObserver(accessMonitor)
    }
}

val android.content.Context.appContainer: AppContainer
    get() = (applicationContext as RestrictorApplication).container
