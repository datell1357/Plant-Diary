package com.planterior.helper.feature.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniHomeSharePhraseChunkTest {
    @Test
    fun `ordinary words and auxiliary phrases become indivisible visual chunks`() {
        val text = "개인 이미지도 그대로예요. 제외하고\n" + "저장된 미니홈을 볼 수 있어요. 새 링크를 만들 수 있어요."

        val lines = miniHomeShareProseLines(text)

        assertEquals(
            listOf(
                listOf("개인", "이미지도", "그대로예요.", "제외하고"),
                listOf("저장된", "미니홈을", "볼 수 있어요.", "새", "링크를", "만들 수 있어요."),
            ),
            lines,
        )
    }

    @Test
    fun `chunks preserve punctuation spaces and explicit newlines`() {
        val text = "링크로는 볼 수 있어요, 필요하면\n새 링크를 만들 수 있어요."

        val lines = miniHomeShareProseLines(text)

        assertTrue(lines.flatten().contains("볼 수 있어요,"))
        assertTrue(lines.flatten().contains("만들 수 있어요."))
        assertEquals(text, lines.joinToString("\n") { it.joinToString(" ") })
    }
}
