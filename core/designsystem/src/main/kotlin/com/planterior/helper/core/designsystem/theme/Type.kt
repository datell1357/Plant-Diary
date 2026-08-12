package com.planterior.helper.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Figma `home-screen` 텍스트 레이어의 검사 값을 그대로 옮긴 타이포그래피이다.
 *
 * | Material 3 슬롯 | Figma 레이어    | 크기 / 행간 / 굵기      |
 * |---------------|--------------|-------------------|
 * | `titleLarge`  | `user-name`  | 17sp / 21sp / 700 |
 * | `titleMedium` | `title`      | 16sp / 19sp / 700 |
 * | `bodyLarge`   | `plant-name` | 14sp / 17sp / 700 |
 * | `bodyMedium`  | `location`   | 13sp / 16sp / 400 |
 * | `labelSmall`  | `tab-label`  | 10sp / 12sp / 700 |
 *
 * Figma는 Inter를 쓰지만 한국어 글리프는 Inter에 없어 실제 렌더링은 시스템 폰트가 담당한다. 앱에서도 같은 결과가 나오도록
 * [FontFamily.SansSerif]를 사용해 기기 기본 한글 서체로 그린다.
 */
private val PlanteriorFontFamily = FontFamily.SansSerif

internal val PlanteriorTypography: Typography =
    Typography(
        titleLarge =
            TextStyle(
                fontFamily = PlanteriorFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                lineHeight = 21.sp,
            ),
        titleMedium =
            TextStyle(
                fontFamily = PlanteriorFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                lineHeight = 19.sp,
            ),
        bodyLarge =
            TextStyle(
                fontFamily = PlanteriorFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                lineHeight = 17.sp,
            ),
        bodyMedium =
            TextStyle(
                fontFamily = PlanteriorFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 13.sp,
                lineHeight = 16.sp,
            ),
        labelSmall =
            TextStyle(
                fontFamily = PlanteriorFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                lineHeight = 12.sp,
            ),
    )
