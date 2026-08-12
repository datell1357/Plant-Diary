package com.planterior.helper.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Figma 모서리 반경 토큰이다.
 *
 * [Large]는 `home-screen` 프레임에서 읽은 48dp이며 화면 컨테이너 같은 큰 표면에만 쓴다.
 */
object PlanteriorRadius {
    /** 8dp. 작은 배지와 칩. */
    val Small: Dp = 8.dp

    /** 16dp. 카드와 버튼. */
    val Medium: Dp = 16.dp

    /** 48dp. `home-screen` 프레임 모서리. */
    val Large: Dp = 48.dp
}

/** Figma 경계선 두께 토큰. `home-screen`과 `tab-bar` 모두 1dp이다. */
val PlanteriorBorderWidth: Dp = 1.dp

internal val PlanteriorShapes: Shapes =
    Shapes(
        small = RoundedCornerShape(PlanteriorRadius.Small),
        medium = RoundedCornerShape(PlanteriorRadius.Medium),
        large = RoundedCornerShape(PlanteriorRadius.Large),
    )
