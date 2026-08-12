package com.planterior.helper.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 4dp 배수 간격 스케일이다.
 *
 * Figma `tab-bar`의 좌우 16dp, 상단 8dp, 하단 2dp 패딩이 모두 이 스케일 안에 들어온다. 화면에서 임의의 dp 값을 쓰지 않고 여기 정의된 단계만
 * 사용한다.
 */
@Immutable
data class PlanteriorSpacing(
    /** 2dp. `tab-bar` 하단 패딩. */
    val extraSmall: Dp = 2.dp,
    /** 4dp. 아이콘과 라벨 사이 최소 간격. */
    val small: Dp = 4.dp,
    /** 8dp. `tab-bar` 상단 패딩. */
    val medium: Dp = 8.dp,
    /** 12dp. 카드 내부 요소 간격. */
    val large: Dp = 12.dp,
    /** 16dp. `tab-bar` 좌우 패딩이자 화면 기본 여백. */
    val extraLarge: Dp = 16.dp,
    /** 24dp. 섹션 사이 간격. */
    val huge: Dp = 24.dp,
)

internal val LocalPlanteriorSpacing = staticCompositionLocalOf { PlanteriorSpacing() }
