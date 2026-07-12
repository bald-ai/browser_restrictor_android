package com.michalkrsik.internetrestrictor.data

import androidx.datastore.core.CorruptionException
import com.michalkrsik.internetrestrictor.domain.BudgetPeriod
import com.michalkrsik.internetrestrictor.domain.DailyCounter
import com.michalkrsik.internetrestrictor.domain.PendingWait
import com.michalkrsik.internetrestrictor.domain.PersistedState
import com.michalkrsik.internetrestrictor.domain.RestrictorSettings
import com.michalkrsik.internetrestrictor.domain.ThemePreference
import com.michalkrsik.internetrestrictor.domain.UnlockSession
import com.michalkrsik.internetrestrictor.domain.UnlockSource
import com.michalkrsik.internetrestrictor.domain.UsagePeriodState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AppStateSerializerTest {
    @Test
    fun stateRoundTripsWithoutDroppingRuntimeRules() = runTest {
        val state = PersistedState(
            stateRevision = 42,
            settings = RestrictorSettings(theme = ThemePreference.LIGHT, delayEnabled = false),
            dailyUsage = UsagePeriodState(BudgetPeriod.DAILY, "2026-07-10", 12_345),
            weeklyUsage = UsagePeriodState(BudgetPeriod.WEEKLY, "2026-07-06", 23_456),
            monthlyUsage = UsagePeriodState(BudgetPeriod.MONTHLY, "2026-07", 34_567),
            activeUnlock = UnlockSession("unlock", UnlockSource.EMERGENCY, 100, 200),
            dailyCounter = DailyCounter("2026-07-10", 2, 1),
        )
        val output = ByteArrayOutputStream()
        AppStateSerializer.writeTo(state, output)
        val restored = AppStateSerializer.readFrom(ByteArrayInputStream(output.toByteArray()))
        assertEquals(state, restored)
    }

    @Test(expected = CorruptionException::class)
    fun invalidMagicIsReportedAsCorruption() = runTest {
        AppStateSerializer.readFrom(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)))
    }

    @Test(expected = CorruptionException::class)
    fun overlappingUnlockAndWaitCannotBeWritten() = runTest {
        val state = PersistedState(
            activeUnlock = UnlockSession("unlock", UnlockSource.NORMAL, 100, 200),
            pendingWait = PendingWait("wait", 1_000, 100, 0, 100, 100),
        )
        AppStateSerializer.writeTo(state, ByteArrayOutputStream())
    }
}
