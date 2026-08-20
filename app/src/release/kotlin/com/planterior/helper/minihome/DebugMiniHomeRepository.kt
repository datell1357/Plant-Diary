package com.planterior.helper.minihome

import android.content.Context
import com.planterior.helper.core.database.PlanteriorDatabase
import com.planterior.helper.feature.minihome.MiniHomeRepository
import com.planterior.helper.feature.minihome.MiniHomeUiState

fun debugMiniHomeRepository(
    context: Context,
    database: PlanteriorDatabase,
    fallback: MiniHomeRepository,
): MiniHomeRepository = fallback

fun setDebugMiniHomeSaveOutcome(context: Context, outcome: String) = Unit

fun observeDebugMiniHomeState(context: Context, state: MiniHomeUiState) = Unit

const val OUTCOME_SUCCESS = "success"
const val OUTCOME_FAILURE = "failure"
const val OUTCOME_CONFLICT = "conflict"
const val OUTCOME_UNAVAILABLE = "unavailable"
const val OUTCOME_MISMATCH = "mismatch"
const val OUTCOME_INVALID = "invalid"
