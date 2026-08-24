package com.planterior.helper.core.designsystem.theme

import androidx.compose.ui.graphics.Color

/**
 * Figma `Page 1` 검사 패널에서 그대로 읽은 색상 토큰이다.
 *
 * 값을 추가하거나 바꿀 때는 Figma Dev Mode 검사 결과를 근거로만 수정한다. 화면 코드에서 리터럴 색상을 직접 쓰지 않고 항상 이 토큰이나
 * [PlanteriorColorScheme]을 통해 참조한다.
 */
internal object PlanteriorPalette {
    /** `home-screen` 배경 색상. */
    val Background = Color(0xFFFCFBF7)

    /** `tab-bar` 배경 색상이자 카드 표면. */
    val Surface = Color(0xFFFFFFFF)

    /** primary 연한 표면. */
    val PrimaryContainer = Color(0xFFEEF3F0)

    /** 활성 탭과 카메라 원형 버튼에 쓰는 primary. */
    val Primary = Color(0xFF3D6642)

    /** primary 위에 올라가는 전경색. */
    val OnPrimary = Color(0xFFFFFFFF)

    /** 본문과 제목 텍스트. */
    val TextPrimary = Color(0xFF1F2937)

    /** 보조 설명 텍스트. */
    val TextSecondary = Color(0xFF6B7280)

    /** 비활성 탭 아이콘과 라벨. */
    val TextTertiary = Color(0xFF9CA3AF)

    /** 화면과 탭 바 경계선. */
    val Border = Color(0xFFE5E7EB)

    /** 경고 강조 텍스트와 아이콘. */
    val Warning = Color(0xFFD97706)

    /** 경고 배경. */
    val WarningContainer = Color(0xFFFEF3C7)

    /**
     * 경고 배경 위에 올라가는 본문 전경색.
     *
     * [Warning]은 아이콘·강조용 accent라 [WarningContainer] 위에서 2.861:1밖에 되지 않아 WCAG 2.1 SC 1.4.3의 본문 최소치
     * 4.5:1을 만족하지 못한다. 같은 amber 계열의 한 단계 짙은 색을 써서 경고 의미를 유지한 채 6.367:1을 확보한다.
     */
    val OnWarningContainer = Color(0xFF92400E)

    /** 경고 테두리. */
    val WarningBorder = Color(0xFFFDE68A)

    /** 되돌릴 수 없는 파괴적 행동. 경고 amber와 의미를 분리한다. */
    val Destructive = Color(0xFFB42318)

    /** 파괴적 결과와 확인 내용을 담는 저강도 표면. */
    val DestructiveContainer = Color(0xFFFFE9E7)

    /** 파괴적 표면 위의 고대비 본문. */
    val OnDestructiveContainer = Color(0xFF7A271A)
}
