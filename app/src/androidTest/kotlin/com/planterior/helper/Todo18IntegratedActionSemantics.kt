package com.planterior.helper

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.feature.minihome.MiniHomeSaveActionStage
import com.planterior.helper.feature.minihome.MiniHomeTestTags
import com.planterior.helper.feature.watering.WateringConfirmActionStage
import com.planterior.helper.feature.watering.WateringTestTags

internal fun Todo18MainActivityJourneyHarness.assertMiniHomeSaveActionNode(
    operationId: OperationId
) {
    compose.onAllNodesWithTag(MiniHomeTestTags.SAVE).assertCountEquals(1)
    runtime.actionRecorder.recordMiniHomeSemantic(
        MiniHomeSaveActionStage.SAVE_NODE_COUNT,
        operationId,
    )
    val save = compose.onNodeWithTag(MiniHomeTestTags.SAVE)
    save.performScrollTo()
    save.assertIsDisplayed()
    runtime.actionRecorder.recordMiniHomeSemantic(
        MiniHomeSaveActionStage.SAVE_NODE_DISPLAYED,
        operationId,
    )
    save.assertIsEnabled()
    runtime.actionRecorder.recordMiniHomeSemantic(
        MiniHomeSaveActionStage.SAVE_NODE_ENABLED,
        operationId,
    )
    save.assert(hasClickAction())
    runtime.actionRecorder.recordMiniHomeSemantic(
        MiniHomeSaveActionStage.SAVE_NODE_ON_CLICK,
        operationId,
    )
}

internal fun Todo18MainActivityJourneyHarness.assertWateringConfirmActionNode(
    plantId: PersonalPlantId
) {
    compose.onAllNodesWithTag(WateringTestTags.CONFIRM).assertCountEquals(1)
    runtime.actionRecorder.recordWateringSemantic(
        WateringConfirmActionStage.CONFIRM_NODE_COUNT,
        plantId,
    )
    val confirm = compose.onNodeWithTag(WateringTestTags.CONFIRM)
    confirm.assertIsDisplayed()
    runtime.actionRecorder.recordWateringSemantic(
        WateringConfirmActionStage.CONFIRM_NODE_DISPLAYED,
        plantId,
    )
    confirm.assertIsEnabled()
    runtime.actionRecorder.recordWateringSemantic(
        WateringConfirmActionStage.CONFIRM_NODE_ENABLED,
        plantId,
    )
    confirm.assert(hasClickAction())
    runtime.actionRecorder.recordWateringSemantic(
        WateringConfirmActionStage.CONFIRM_NODE_ON_CLICK,
        plantId,
    )
}
