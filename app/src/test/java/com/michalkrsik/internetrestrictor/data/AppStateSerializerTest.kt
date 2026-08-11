package com.michalkrsik.internetrestrictor.data

import androidx.datastore.core.CorruptionException
import com.michalkrsik.internetrestrictor.domain.BudgetPeriod
import com.michalkrsik.internetrestrictor.domain.DailyCounter
import com.michalkrsik.internetrestrictor.domain.DailyUsageReport
import com.michalkrsik.internetrestrictor.domain.OffUsageReminderState
import com.michalkrsik.internetrestrictor.domain.PendingWait
import com.michalkrsik.internetrestrictor.domain.PersistedState
import com.michalkrsik.internetrestrictor.domain.RestrictorSettings
import com.michalkrsik.internetrestrictor.domain.ThemePreference
import com.michalkrsik.internetrestrictor.domain.UnlockSession
import com.michalkrsik.internetrestrictor.domain.UnlockSource
import com.michalkrsik.internetrestrictor.domain.UsagePeriodState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AppStateSerializerTest {
    @Test
    fun stateRoundTripsWithoutDroppingRuntimeRules() = runTest {
        val state = PersistedState(
            stateRevision = 42,
            settings = RestrictorSettings(
                theme = ThemePreference.LIGHT,
                delayEnabled = false,
                enforcementEnabled = false,
                pauseUntilWallMs = 999_999,
            ),
            dailyUsage = UsagePeriodState(BudgetPeriod.DAILY, "2026-07-10", 12_345),
            activeUnlock = UnlockSession("unlock", UnlockSource.NORMAL, 100, 200),
            dailyCounter = DailyCounter("2026-07-10", 2, 1),
            usageReports = listOf(DailyUsageReport("2026-07-10", 5_000, 6_000)),
            offUsageReminder = OffUsageReminderState(
                sessionStartedAtWallMs = 100,
                qualifyingUsageMs = 6_000,
            ),
        )
        val output = ByteArrayOutputStream()
        AppStateSerializer.writeTo(state, output)
        val restored = AppStateSerializer.readFrom(ByteArrayInputStream(output.toByteArray()))
        assertEquals(state, restored)
    }

    @Test
    fun schemaV1BytesMigrateWithoutLosingPersistedPhoneState() = runTest {
        val bytes = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { target ->
                target.writeInt(0x49525354)
                target.writeInt(1)
                target.writeLong(77)
                target.writeBoolean(false)
                target.writeBoolean(false)
                target.writeBoolean(true)
                target.writeLong(17)
                target.writeBoolean(true)
                target.writeLong(170)
                target.writeBoolean(true)
                target.writeLong(1_700)
                target.writeBoolean(true)
                target.writeLong(2)
                target.writeInt(ThemePreference.LIGHT.ordinal)
                target.writeBoolean(true)
                target.writeUTF("2026-07-19")
                target.writeLong(12_345)
                target.writeUTF("2026-07-13")
                target.writeLong(23_456)
                target.writeUTF("2026-07")
                target.writeLong(34_567)
                target.writeBoolean(false)
                target.writeBoolean(true)
                target.writeUTF("legacy-wait")
                target.writeLong(30 * 60_000L)
                target.writeLong(15_000)
                target.writeLong(3_000)
                target.writeLong(1_000)
                target.writeLong(2_000)
                target.writeUTF("2026-07-19")
                target.writeLong(4)
                target.writeLong(1)
                target.writeLong(99_000)
                target.writeUTF("legacy-epoch")
                target.writeLong(98_000)
                target.writeInt(0)
            }
        }.toByteArray()

        val migrated = AppStateSerializer.readFrom(ByteArrayInputStream(bytes))

        assertEquals(3, migrated.schemaVersion)
        assertEquals(77, migrated.stateRevision)
        assertEquals(false, migrated.settings.enforcementEnabled)
        assertEquals(false, migrated.settings.delayEnabled)
        assertEquals(12_345, migrated.dailyUsage.usedMs)
        assertEquals(0, migrated.dailyCounter.earnedBudgetRounds)
        assertEquals(null, migrated.pendingWait)
        assertEquals("legacy-epoch", migrated.runtimeMetadata.recoveryEpochId)
        assertEquals(emptyList<DailyUsageReport>(), migrated.usageReports)
        assertEquals(false, migrated.currentVisitGraceActive)
    }

    @Test
    fun currentVisitGraceSurvivesV3PersistenceRoundTrip() = runTest {
        val state = PersistedState(currentVisitGraceActive = true)
        val output = ByteArrayOutputStream()
        AppStateSerializer.writeTo(state, output)
        val restored = AppStateSerializer.readFrom(ByteArrayInputStream(output.toByteArray()))
        assertEquals(true, restored.currentVisitGraceActive)
    }

    @Test
    fun earlySchemaV2BytesWithoutGraceFieldKeepMeasuredUsage() = runTest {
        val bytes = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { target ->
                target.writeInt(0x49525354)
                target.writeInt(2)
                target.writeLong(12)
                target.writeBoolean(true)
                target.writeBoolean(false)
                target.writeBoolean(true)
                repeat(3) {
                    target.writeBoolean(true)
                    target.writeLong(listOf(60L, 600L, 2_400L)[it])
                }
                target.writeBoolean(true)
                target.writeLong(3)
                target.writeInt(ThemePreference.DARK.ordinal)
                target.writeBoolean(false)
                target.writeUTF("2026-07-20")
                target.writeLong(1_494_393)
                target.writeUTF("2026-07-20")
                target.writeLong(1_494_393)
                target.writeUTF("2026-07")
                target.writeLong(3_663_000)
                target.writeBoolean(true)
                target.writeUTF("legacy-pushup")
                target.writeInt(2)
                target.writeLong(100)
                target.writeLong(200)
                target.writeBoolean(false)
                target.writeUTF("2026-07-20")
                target.writeLong(0)
                target.writeLong(0)
                target.writeLong(0)
                target.writeUTF("")
                target.writeLong(0)
                target.writeInt(2)
                target.writeInt(2)
                target.writeUTF("2026-07-19")
                target.writeLong(0)
                target.writeLong(22_935)
                target.writeUTF("2026-07-20")
                target.writeLong(1_494_393)
                target.writeLong(0)
                repeat(2) { target.writeLong(0) }
                target.writeBoolean(false)
                repeat(2) { target.writeLong(0) }
                // Early schema-v2 state ended here, before currentVisitGraceActive.
            }
        }.toByteArray()

        val restored = AppStateSerializer.readFrom(ByteArrayInputStream(bytes))

        assertEquals(1_494_393, restored.dailyUsage.usedMs)
        assertEquals(2, restored.usageReports.size)
        assertEquals(null, restored.activeUnlock)
        assertEquals(false, restored.currentVisitGraceActive)
    }

    @Test(expected = CorruptionException::class)
    fun truncatedSchemaV3StateIsCorruption() = runTest {
        val output = ByteArrayOutputStream()
        AppStateSerializer.writeTo(PersistedState(currentVisitGraceActive = true), output)
        val truncated = output.toByteArray().copyOf(output.size() - 1)
        AppStateSerializer.readFrom(ByteArrayInputStream(truncated))
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
