package com.michalkrsik.internetrestrictor.data

import com.michalkrsik.internetrestrictor.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/** JSON shared by the network bridge and the appended v15 state section. */
object ConfigurationJson {
    fun parseConfiguration(envelope: JSONObject): AppConfiguration {
        val config = envelope.optJSONObject("config") ?: envelope
        val settings = config.getJSONObject("settings")
        val parsed = AppConfiguration(
            revision = envelope.optLong("revision", 0),
            settings = ConfigurationSettings(
                waitSeconds = settings.getLong("waitSeconds"), minutesPerCredit = settings.getLong("minutesPerCredit"),
                sessionMinutes = settings.getJSONArray("sessionMinutes").let { array -> List(array.length()) { array.getLong(it) } },
                dailyBudgetMinutes = if (settings.has("dailyBudgetMinutes")) BigDecimal(settings.get("dailyBudgetMinutes").toString()).toBigIntegerExact().toString().toLong() else 0, dayReset = settings.getString("dayReset"),
                youtubeCredits = settings.getLong("youtubeCredits"), emergencyMinutes = settings.getLong("emergencyMinutes"),
                creditName = settings.getString("creditName"), unlockLabel = settings.getString("unlockLabel"),
                youtubeUnlockLabel = settings.getString("youtubeUnlockLabel"), emergencyUnlockLabel = settings.getString("emergencyUnlockLabel"),
            ),
            tasks = config.getJSONArray("tasks").let { array -> List(array.length()) { parseTask(array.getJSONObject(it)) } },
        )
        require(parsed.validationError() == null) { parsed.validationError().orEmpty() }
        return parsed
    }

    fun configurationJson(configuration: AppConfiguration): JSONObject = JSONObject()
        .put("settings", settingsJson(configuration.settings)).put("tasks", JSONArray(configuration.tasks.map(::taskJson)))

    fun settingsJson(settings: ConfigurationSettings): JSONObject = with(settings) { JSONObject()
        .put("waitSeconds", waitSeconds).put("minutesPerCredit", minutesPerCredit)
        .put("dailyBudgetMinutes", dailyBudgetMinutes).put("sessionMinutes", JSONArray(sessionMinutes)).put("dayReset", dayReset)
        .put("youtubeCredits", youtubeCredits).put("emergencyMinutes", emergencyMinutes).put("creditName", creditName)
        .put("unlockLabel", unlockLabel).put("youtubeUnlockLabel", youtubeUnlockLabel).put("emergencyUnlockLabel", emergencyUnlockLabel)
    }

    fun parseTask(task: JSONObject): TaskDefinition = TaskDefinition(
        id = task.getString("id"), name = task.getString("name"), verifier = task.getString("verifier"), metric = task.getString("metric"),
        unitsPerCredit = task.getLong("unitsPerCredit"), correctionMinimum = task.getLong("correctionMinimum"),
        instructions = task.getString("instructions"), paused = task.optBoolean("paused", false),
        gpsMode = if (task.isNull("gpsMode")) null else task.optString("gpsMode").takeIf(String::isNotBlank),
    )

    fun taskJson(task: TaskDefinition): JSONObject = with(task) { JSONObject()
        .put("id", id).put("name", name).put("verifier", verifier).put("metric", metric)
        .put("unitsPerCredit", unitsPerCredit).put("correctionMinimum", correctionMinimum)
        .put("instructions", instructions).put("paused", paused).put("gpsMode", gpsMode ?: JSONObject.NULL)
    }

    fun parseTaskReceipt(json: JSONObject): TaskMeasurementReceipt = TaskMeasurementReceipt(
        id = json.getString("id"), attemptId = json.optString("attemptId", json.getString("id")),
        taskId = json.getString("taskId"), configRevision = json.getLong("configRevision"), task = parseTask(json.getJSONObject("task")),
        value = json.getLong("measuredUnits"), startedAtWallMs = json.getLong("startedAt"), finishedAtWallMs = json.getLong("endedAt"),
        minutesPerCredit = json.getLong("minutesPerCredit"), dayReset = json.getString("dayReset"),
        correctionEligible = json.getBoolean("correctionEligible"), rewardEligible = json.getBoolean("rewardEligible"), timeZone = json.getString("timeZone"),
        fallbackEligible = json.optBoolean("fallbackEligible", false), manualCompletion = json.optBoolean("manualCompletion", false),
        correctedUnits = if (json.isNull("correctedUnits")) null else json.getLong("correctedUnits"),
        measuredUnitsText = decimalText(json.get("measuredUnits")),
        correctedUnitsText = if (json.isNull("correctedUnits")) null else decimalText(json.get("correctedUnits")),
        maximumCorrectableUnits = if (json.isNull("maximumCorrectableUnits")) null else json.getDouble("maximumCorrectableUnits"),
        minutesRewardEligible = json.optBoolean("minutesRewardEligible", json.getBoolean("rewardEligible")),
    ).also { require(it.validationError() == null) { it.validationError().orEmpty() } }

    fun taskReceiptJson(receipt: TaskMeasurementReceipt): JSONObject = with(receipt) { JSONObject()
        .put("id", id).put("attemptId", attemptId).put("taskId", taskId).put("configRevision", configRevision).put("task", taskJson(task))
        .put("measuredUnits", BigDecimal(measuredUnitsText)).put("startedAt", startedAtWallMs).put("endedAt", finishedAtWallMs)
        .put("minutesPerCredit", minutesPerCredit).put("dayReset", dayReset)
        .put("correctionEligible", correctionEligible).put("rewardEligible", rewardEligible).put("timeZone", timeZone)
        .put("fallbackEligible", fallbackEligible).put("manualCompletion", manualCompletion).put("correctedUnits", correctedUnitsText?.let(::BigDecimal) ?: JSONObject.NULL)
        .put("maximumCorrectableUnits", maximumCorrectableUnits ?: JSONObject.NULL)
        .put("minutesRewardEligible", minutesRewardEligible)
    }

    internal fun stateJson(state: PersistedState): JSONObject = JSONObject()
        .put("config", configurationJson(state.configuration)).put("revision", state.configuration.revision)
        .put("hasAppliedServerConfiguration", state.hasAppliedServerConfiguration)
        .put("grantedBudgetMinutes", state.dailyCounter.grantedBudgetMinutes ?: JSONObject.NULL)
        .put("taskBudgetKey", state.taskBudgetKey).put("lastRewardResetWallMs", state.lastRewardResetWallMs)
        .put("lastDailyRewardResetWallMs", state.lastDailyRewardResetWallMs)
        .put("progress", fractionJson(state.taskCreditProgress)).put("base", fractionJson(state.taskProgressBase))
        .put("receipts", JSONArray(state.taskReceipts.map { saved -> JSONObject()
            .put("receipt", taskReceiptJson(saved.measurement)).put("value", saved.value).put("dayKey", saved.dayKey)
            .put("budgetKey", saved.budgetKey).put("rewardEligible", saved.rewardEligible)
            .put("minutesRewardEligible", saved.minutesRewardEligible)
            .put("awardedCredits", saved.awardedCredits).put("awardedMinutes", saved.awardedMinutes)
            .put("corrected", saved.corrected).put("flagged", saved.flagged)
            .put("valueText", saved.valueText)
        }))

    internal fun withStateJson(state: PersistedState, json: JSONObject): PersistedState = state.copy(
        configuration = parseConfiguration(json),
        hasAppliedServerConfiguration = json.optBoolean("hasAppliedServerConfiguration", false),
        dailyCounter = state.dailyCounter.copy(grantedBudgetMinutes = if (json.isNull("grantedBudgetMinutes")) null else json.getLong("grantedBudgetMinutes")),
        taskBudgetKey = json.getString("taskBudgetKey"), lastRewardResetWallMs = json.getLong("lastRewardResetWallMs"),
        lastDailyRewardResetWallMs = json.optLong("lastDailyRewardResetWallMs", 0),
        taskCreditProgress = parseFraction(json.getJSONObject("progress")), taskProgressBase = parseFraction(json.getJSONObject("base")),
        taskReceipts = json.getJSONArray("receipts").let { array ->
            require(array.length() <= 10_000)
            List(array.length()) { index -> with(array.getJSONObject(index)) {
                SavedTaskReceipt(parseTaskReceipt(getJSONObject("receipt")), getLong("value"), getString("dayKey"), getString("budgetKey"),
                    getBoolean("rewardEligible"), getLong("awardedCredits"), getLong("awardedMinutes"), getBoolean("corrected"), getBoolean("flagged"),
                    optString("valueText", getLong("value").toString()), optBoolean("minutesRewardEligible", getBoolean("rewardEligible")))
            } }
        },
    )

    private fun fractionJson(value: CreditFraction) = JSONObject().put("numerator", value.numerator).put("denominator", value.denominator)
    private fun parseFraction(value: JSONObject) = CreditFraction(value.getString("numerator"), value.getString("denominator"))
    private fun decimalText(value: Any): String = BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
}
