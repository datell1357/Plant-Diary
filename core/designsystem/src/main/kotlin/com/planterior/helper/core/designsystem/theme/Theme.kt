package com.planterior.helper.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/**
 * Figma 색상 토큰을 Material 3 슬롯에 연결한 스킴이다.
 *
 * Figma `Page 1`에는 dark 대응 화면이 없다. 임의로 dark 색을 만들어내지 않고 항상 이 light 스킴을 사용해 디자인과 앱이 어긋나지 않게 한다.
 */
internal val PlanteriorColorScheme =
    lightColorScheme(
        primary = PlanteriorPalette.Primary,
        onPrimary = PlanteriorPalette.OnPrimary,
        primaryContainer = PlanteriorPalette.PrimaryContainer,
        onPrimaryContainer = PlanteriorPalette.Primary,
        secondary = PlanteriorPalette.Primary,
        onSecondary = PlanteriorPalette.OnPrimary,
        background = PlanteriorPalette.Background,
        onBackground = PlanteriorPalette.TextPrimary,
        surface = PlanteriorPalette.Surface,
        onSurface = PlanteriorPalette.TextPrimary,
        surfaceVariant = PlanteriorPalette.PrimaryContainer,
        onSurfaceVariant = PlanteriorPalette.TextSecondary,
        outline = PlanteriorPalette.Border,
        outlineVariant = PlanteriorPalette.Border,
        error = PlanteriorPalette.Warning,
        onError = PlanteriorPalette.OnPrimary,
        errorContainer = PlanteriorPalette.WarningContainer,
        onErrorContainer = PlanteriorPalette.OnWarningContainer,
    )

/**
 * 앱 전체 테마이다. 모든 화면은 이 테마 안에서만 그려야 색·타이포·모서리 토큰이 보장된다.
 *
 * @param content 테마 토큰을 적용할 화면 내용.
 */
@Composable
fun PlanteriorTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPlanteriorSpacing provides PlanteriorSpacing()) {
        MaterialTheme(
            colorScheme = PlanteriorColorScheme,
            typography = PlanteriorTypography,
            shapes = PlanteriorShapes,
            content = content,
        )
    }
}

/** 테마 토큰 접근점이다. 화면 코드에서 리터럴 dp 대신 `PlanteriorTheme.spacing`을 쓴다. */
object PlanteriorTheme {
    /** 4dp 배수 간격 스케일. */
    val spacing: PlanteriorSpacing
        @Composable @ReadOnlyComposable get() = LocalPlanteriorSpacing.current

    /** 비활성 탭 아이콘·라벨 등 3차 텍스트 색. Material 3 슬롯으로 표현되지 않아 별도로 노출한다. */
    val tertiaryText
        @Composable @ReadOnlyComposable get() = PlanteriorPalette.TextTertiary

    /** 경고 테두리 색. */
    val warningBorder
        @Composable @ReadOnlyComposable get() = PlanteriorPalette.WarningBorder
}
