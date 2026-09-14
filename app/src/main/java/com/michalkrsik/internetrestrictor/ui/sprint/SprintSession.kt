package com.michalkrsik.internetrestrictor.ui.sprint

import com.michalkrsik.internetrestrictor.domain.SprintEvaluationResult

data class SprintSaveState(
    val recordingId: String,
    val result: SprintEvaluationResult,
    val isSaving: Boolean = false,
    val saveSucceeded: Boolean = false,
    val newlyEarnedSets: Long = 0L,
    val manualConfirmed: Boolean = false,
    val errorMessage: String? = null,
    val canRetry: Boolean = false,
    val savedRemainder: Long? = null,
)

enum class SprintPhase { ACQUIRING_GPS, RECORDING, RESULT }

