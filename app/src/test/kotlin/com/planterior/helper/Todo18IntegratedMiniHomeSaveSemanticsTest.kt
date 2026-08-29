package com.planterior.helper

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.feature.minihome.MiniHomeSaveActionStage
import com.planterior.helper.feature.minihome.MiniHomeTestTags
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Todo18IntegratedMiniHomeSaveSemanticsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `integrated helper scrolls the exact cardinality checked SAVE node before displayed`() {
        val source =
            repositoryRoot()
                .resolve(
                    "app/src/androidTest/kotlin/com/planterior/helper/" +
                        "Todo18IntegratedActionSemantics.kt"
                )
                .readText()

        assertOrdered(
            source,
            "onAllNodesWithTag(MiniHomeTestTags.SAVE).assertCountEquals(1)",
            "val save = compose.onNodeWithTag(MiniHomeTestTags.SAVE)",
            "save.performScrollTo()",
            "save.assertIsDisplayed()",
            "save.assertIsEnabled()",
            "save.assert(hasClickAction())",
        )
    }

    @Test
    fun `offscreen exact SAVE fails displayed then scroll records four facts for one operation`() {
        val operationId = OperationId("ordinal-10-save-viewport")
        val facts = mutableListOf<Pair<MiniHomeSaveActionStage, OperationId>>()
        compose.setContent {
            Column(Modifier.height(120.dp).verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(1_200.dp))
                Button(
                    onClick = {},
                    modifier = Modifier.fillMaxWidth().testTag(MiniHomeTestTags.SAVE),
                ) {
                    Text("배치 저장")
                }
            }
        }

        compose.onAllNodesWithTag(MiniHomeTestTags.SAVE).assertCountEquals(1)
        facts += MiniHomeSaveActionStage.SAVE_NODE_COUNT to operationId
        val save = compose.onNodeWithTag(MiniHomeTestTags.SAVE)
        assertThrows(AssertionError::class.java) { save.assertIsDisplayed() }
        assertEquals(
            listOf(MiniHomeSaveActionStage.SAVE_NODE_COUNT to operationId),
            facts,
        )
        assertEquals(
            MiniHomeSaveActionStage.SAVE_NODE_DISPLAYED,
            MiniHomeSaveActionStage.entries[facts.size],
        )

        save.performScrollTo()
        save.assertIsDisplayed()
        facts += MiniHomeSaveActionStage.SAVE_NODE_DISPLAYED to operationId
        save.assertIsEnabled()
        facts += MiniHomeSaveActionStage.SAVE_NODE_ENABLED to operationId
        save.assert(hasClickAction())
        facts += MiniHomeSaveActionStage.SAVE_NODE_ON_CLICK to operationId

        assertEquals(
            listOf(
                MiniHomeSaveActionStage.SAVE_NODE_COUNT,
                MiniHomeSaveActionStage.SAVE_NODE_DISPLAYED,
                MiniHomeSaveActionStage.SAVE_NODE_ENABLED,
                MiniHomeSaveActionStage.SAVE_NODE_ON_CLICK,
            ),
            facts.map(Pair<MiniHomeSaveActionStage, OperationId>::first),
        )
        assertEquals(
            setOf(operationId),
            facts.map(Pair<MiniHomeSaveActionStage, OperationId>::second).toSet(),
        )
    }

    private fun assertOrdered(source: String, vararg tokens: String) {
        val positions = tokens.map(source::indexOf)
        tokens.zip(positions).forEach { (token, position) ->
            assertTrue("Missing ordered code token: $token", position >= 0)
        }
        assertEquals("Code tokens are out of order", positions.sorted(), positions)
    }

    private fun repositoryRoot(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (!Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.parent ?: error("Repository root unavailable")
        }
        return current
    }
}
