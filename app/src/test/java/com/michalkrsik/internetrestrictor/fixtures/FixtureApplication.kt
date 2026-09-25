package com.michalkrsik.internetrestrictor.fixtures

import androidx.datastore.core.DataStore
import com.michalkrsik.internetrestrictor.*
import com.michalkrsik.internetrestrictor.data.RestrictorRepository
import com.michalkrsik.internetrestrictor.domain.*
import com.michalkrsik.internetrestrictor.v1.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

/** Never starts the production application, background sync, or real platform services. */
class FixtureApplication : RestrictorApplication() {
    val clock = FakeTimeSource(1_790_250_000_000L)
    val fixtureScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val store = object : DataStore<PersistedState> {
        override val data = MutableStateFlow(PersistedState(settings = RestrictorSettings(onboardingCompleted = true)))
        override suspend fun updateData(transform: suspend (PersistedState) -> PersistedState): PersistedState =
            transform(data.value).also { data.value = it }
    }
    override fun onCreate() {
        getSharedPreferences("v1_installation", MODE_PRIVATE).edit().clear().commit()
        val repository = RestrictorRepository(timeSource = clock, applicationScope = fixtureScope, dataStoreOverride = store)
        // An empty URL fails before connecting. No test using this fixture can reach the deployed backend.
        val coordinator = V1Coordinator(this, repository, fixtureScope, V1Api(this, ""), clock::wallTimeMs)
        container = AppContainer(this, fixtureScope, clock, repository = repository, v1 = coordinator)
    }
    override fun onTerminate() { fixtureScope.cancel() }
}
