package com.planterior.helper.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer

internal const val SETTINGS_LOCATION_DISCLOSURE =
    "동의를 철회하면 진행 중인 위치 요청을 즉시 취소해요. 직접 선택한 지역은 계속 사용할 수 있어요."

internal const val SETTINGS_PHOTO_DISCLOSURE =
    "사진 분석 원본은 요청마다 처리 후 24시간 이내 삭제되며, 도감 대표 사진은 별도로 선택한 경우에만 저장돼요."

private val SettingsAuxiliaryPhrases =
    listOf(
        listOf("사용할", "수", "있어요"),
        listOf("취소할", "수", "있고"),
        listOf("취소할", "수", "없어요"),
        listOf("확인해", "주세요"),
        listOf("진행해", "주세요"),
        listOf("시도해", "주세요"),
        listOf("불러와", "주세요"),
    )

/** Settings 전용 장문 렌더러. 일반 단어와 승인된 보조 용언 구를 글자 중간에서 나누지 않는다. */
@Composable
internal fun SettingsProse(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val lineHeight =
        remember(textMeasurer, style, density) {
            with(density) {
                textMeasurer
                    .measure("가", style = style, softWrap = false, maxLines = 1)
                    .size
                    .height
                    .toDp()
            }
        }
    Column(
        modifier =
            modifier.semantics(mergeDescendants = true) {
                this.text = AnnotatedString(text)
            }
    ) {
        settingsProseLines(text).forEach { chunks ->
            if (chunks.isEmpty()) {
                Spacer(Modifier.height(lineHeight))
            } else {
                FlowRow(modifier = Modifier.fillMaxWidth()) {
                    chunks.forEach { chunk ->
                        Text(
                            text = chunk,
                            style = style,
                            color = color,
                            softWrap = false,
                            maxLines = 1,
                            modifier = Modifier.clearAndSetSemantics {},
                        )
                    }
                }
            }
        }
    }
}

internal fun settingsProseLines(text: String): List<List<String>> =
    text.split("\n", ignoreCase = false, limit = Int.MAX_VALUE).map(::settingsProseLineChunks)

private fun settingsProseLineChunks(line: String): List<String> {
    if (line.isEmpty()) return emptyList()
    val words = line.split(' ', ignoreCase = false, limit = Int.MAX_VALUE)
    return buildList {
        var index = 0
        while (index < words.size) {
            val phrase = SettingsAuxiliaryPhrases.firstOrNull { candidate ->
                settingsPhraseMatches(words, index, candidate)
            }
            if (phrase == null) {
                add(words[index] + if (index < words.lastIndex) " " else "")
                index += 1
            } else {
                val end = index + phrase.size
                add(
                    words.subList(index, end).joinToString(" ") +
                        if (end <= words.lastIndex) " " else ""
                )
                index = end
            }
        }
    }
}

private fun settingsPhraseMatches(
    words: List<String>,
    start: Int,
    phrase: List<String>,
): Boolean {
    if (start + phrase.size > words.size) return false
    return phrase.indices.all { offset ->
        val word = words[start + offset]
        val expected = phrase[offset]
        if (offset == phrase.lastIndex) {
            word.trimEnd { character -> !character.isLetterOrDigit() } == expected
        } else {
            word == expected
        }
    }
}
