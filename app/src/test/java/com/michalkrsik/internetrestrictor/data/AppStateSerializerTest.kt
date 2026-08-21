package com.michalkrsik.internetrestrictor.data

import androidx.datastore.core.CorruptionException
import com.michalkrsik.internetrestrictor.domain.ActivityEvent
import com.michalkrsik.internetrestrictor.domain.ActivityEventType
import com.michalkrsik.internetrestrictor.domain.BudgetPeriod
import com.michalkrsik.internetrestrictor.domain.DailyCounter
import com.michalkrsik.internetrestrictor.domain.DailyUsageReport
import com.michalkrsik.internetrestrictor.domain.PendingWait
import com.michalkrsik.internetrestrictor.domain.PersistedState
import com.michalkrsik.internetrestrictor.domain.RestrictorSettings
import com.michalkrsik.internetrestrictor.domain.RestrictionEngine
import com.michalkrsik.internetrestrictor.domain.ThemePreference
import com.michalkrsik.internetrestrictor.domain.UnlockSession
import com.michalkrsik.internetrestrictor.domain.UnlockSource
import com.michalkrsik.internetrestrictor.domain.UsagePeriodState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.time.ZoneId
import java.time.ZonedDateTime
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
                sessionMinutes = listOf(2L, 7L, 90L),
            ),
            dailyUsage = UsagePeriodState(BudgetPeriod.DAILY, "2026-07-10", 12_345),
            activeUnlock = UnlockSession("unlock", UnlockSource.NORMAL, 100, 200),
            dailyCounter = DailyCounter("2026-07-10", 2, 1),
            usageReports = listOf(
                DailyUsageReport("2026-07-10", protectedUsageMs = 5_000, pushupSessions = 2),
            ),
            activityEvents = listOf(
                ActivityEvent(
                    id = "browser-1",
                    localDayKey = "2026-07-10",
                    type = ActivityEventType.BROWSER_SESSION,
                    startedAtWallMs = 1_000,
                    endedAtWallMs = 6_000,
                    valueMs = 5_000,
                ),
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

        assertEquals(6, migrated.schemaVersion)
        assertEquals(77, migrated.stateRevision)
        assertEquals(true, migrated.settings.enforcementEnabled)
        assertEquals(null, migrated.settings.pauseUntilWallMs)
        assertEquals(true, migrated.settings.delayEnabled)
        assertEquals(true, migrated.settings.emergencySkipsEnabled)
        assertEquals(1, migrated.settings.emergencySkipDailyLimit)
        assertEquals(listOf(3L, 5L, 10L), migrated.settings.sessionMinutes)
        assertEquals(12_345, migrated.dailyUsage.usedMs)
        assertEquals(0, migrated.dailyCounter.earnedBudgetRounds)
        assertEquals(null, migrated.pendingWait)
        assertEquals("legacy-epoch", migrated.runtimeMetadata.recoveryEpochId)
        assertEquals(emptyList<DailyUsageReport>(), migrated.usageReports)
        assertEquals(false, migrated.currentVisitGraceActive)
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
        assertEquals(listOf(0L, 0L), restored.usageReports.map { it.offUsageMs })
        assertEquals(listOf(0L, 0L), restored.usageReports.map { it.pushupSessions })
        assertEquals(null, restored.activeUnlock)
        assertEquals(false, restored.currentVisitGraceActive)
    }

    @Test(expected = CorruptionException::class)
    fun truncatedSchemaV5StateIsCorruption() = runTest {
        val output = ByteArrayOutputStream()
        AppStateSerializer.writeTo(PersistedState(), output)
        val truncated = output.toByteArray().copyOf(output.size() - 1)
        AppStateSerializer.readFrom(ByteArrayInputStream(truncated))
    }

    @Test
    fun schemaV3BytesMigrateToNewPresetAndEmergencyDefaults() = runTest {
        val bytes = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { target ->
                target.writeInt(0x49525354)
                target.writeInt(3)
                target.writeLong(91)
                target.writeBoolean(true)
                target.writeBoolean(false)
                target.writeBoolean(true)
                target.writeBoolean(true)
                target.writeLong(3)
                target.writeInt(ThemePreference.DARK.ordinal)
                target.writeBoolean(true)
                target.writeUTF("2026-08-18")
                target.writeLong(123_000)
                target.writeBoolean(false)
                target.writeBoolean(false)
                target.writeUTF("2026-08-18")
                target.writeLong(2)
                target.writeLong(1)
                target.writeLong(120_000)
                target.writeUTF("epoch")
                target.writeLong(100_000)
                target.writeInt(0)
                target.writeInt(0)
                repeat(2) { target.writeLong(0) }
                target.writeBoolean(false)
                repeat(2) { target.writeLong(0) }
                target.writeBoolean(false)
            }
        }.toByteArray()

        val migrated = AppStateSerializer.readFrom(ByteArrayInputStream(bytes))

        assertEquals(6, migrated.schemaVersion)
        assertEquals(123_000, migrated.dailyUsage.usedMs)
        assertEquals(2, migrated.dailyCounter.earnedBudgetRounds)
        assertEquals(1, migrated.dailyCounter.emergencySkipsUsed)
        assertEquals(1, migrated.settings.emergencySkipDailyLimit)
        assertEquals(listOf(3L, 5L, 10L), migrated.settings.sessionMinutes)
    }

    @Test
    fun schemaV4PhoneStateForcesProtectionAndSkipsOnAndDiscardsOffHistory() = runTest {
        val bytes = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { target ->
                target.writeInt(0x49525354)
                target.writeInt(4)
                target.writeLong(92)
                target.writeBoolean(false)
                target.writeBoolean(true)
                target.writeLong(999_999)
                target.writeBoolean(true)
                target.writeBoolean(false)
                target.writeLong(1)
                target.writeInt(ThemePreference.DARK.ordinal)
                target.writeBoolean(true)
                target.writeInt(3)
                listOf(3L, 5L, 10L).forEach(target::writeLong)
                target.writeUTF("2026-08-18")
                target.writeLong(120_000)
                target.writeBoolean(false)
                target.writeBoolean(false)
                target.writeUTF("2026-08-18")
                target.writeLong(2)
                target.writeLong(0)
                target.writeLong(120_000)
                target.writeUTF("epoch")
                target.writeLong(100_000)
                target.writeInt(0)
                target.writeInt(1)
                target.writeUTF("2026-08-18")
                target.writeLong(120_000)
                target.writeLong(90_000)
                target.writeLong(100_000)
                target.writeLong(90_000)
                target.writeBoolean(true)
                target.writeLong(95_000)
                target.writeLong(90_000)
                target.writeBoolean(true)
            }
        }.toByteArray()

        val migrated = AppStateSerializer.readFrom(ByteArrayInputStream(bytes))
        val zone = ZoneId.of("Europe/Prague")
        val now = ZonedDateTime.of(2026, 8, 18, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val normalized = RestrictionEngine().normalize(migrated, now, zone)

        assertEquals(6, normalized.schemaVersion)
        assertEquals(true, normalized.settings.enforcementEnabled)
        assertEquals(null, normalized.settings.pauseUntilWallMs)
        assertEquals(true, normalized.settings.emergencySkipsEnabled)
        assertEquals(0, normalized.usageReports.single().offUsageMs)
        assertEquals(2, normalized.usageReports.single().pushupSessions)
        assertEquals(false, normalized.currentVisitGraceActive)
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
