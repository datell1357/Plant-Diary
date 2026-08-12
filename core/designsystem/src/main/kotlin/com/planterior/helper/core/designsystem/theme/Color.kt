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

    /** 경고 테두리. */
    val WarningBorder = Color(0xFFFDE68A)
}
