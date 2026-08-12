package com.planterior.helper.core.designsystem.icon

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Figma 에셋 패널에 있는 아이콘을 같은 24 격자·2 스트로크 규격으로 옮긴 집합이다.
 *
 * Figma 원본 크기는 `book-open` 24, `package` 24, `cog` 24, `camera` 26, `bell-dot` 20이다. 모두 24 뷰포트 기준으로
 * 그려 두고 표시 크기는 사용하는 쪽에서 정한다. 아이콘은 윤곽선만 그리므로 색은 항상 호출부의 tint를 따른다.
 */
object PlanteriorIcons {
    /** 홈 탭. 지붕과 문이 있는 집 윤곽. */
    val Home: ImageVector by lazy {
        strokeIcon("Home") {
            moveTo(3f, 10f)
            lineTo(12f, 3f)
            lineTo(21f, 10f)
            lineTo(21f, 20f)
            lineTo(15f, 20f)
            lineTo(15f, 14f)
            lineTo(9f, 14f)
            lineTo(9f, 20f)
            lineTo(3f, 20f)
            close()
        }
    }

    /** 도감 탭. Figma `book-open`. */
    val Collection: ImageVector by lazy {
        strokeIcon("Collection") {
            moveTo(12f, 7f)
            curveTo(10f, 5f, 7f, 4.5f, 3f, 5f)
            lineTo(3f, 19f)
            curveTo(7f, 18.5f, 10f, 19f, 12f, 21f)
            curveTo(14f, 19f, 17f, 18.5f, 21f, 19f)
            lineTo(21f, 5f)
            curveTo(17f, 4.5f, 14f, 5f, 12f, 7f)
            close()
            moveTo(12f, 7f)
            lineTo(12f, 21f)
        }
    }

    /** 창고 탭. Figma `package`. */
    val Storage: ImageVector by lazy {
        strokeIcon("Storage") {
            moveTo(12f, 2.5f)
            lineTo(21f, 7f)
            lineTo(21f, 17f)
            lineTo(12f, 21.5f)
            lineTo(3f, 17f)
            lineTo(3f, 7f)
            close()
            moveTo(3f, 7f)
            lineTo(12f, 11.5f)
            lineTo(21f, 7f)
            moveTo(12f, 11.5f)
            lineTo(12f, 21.5f)
            moveTo(7.5f, 4.75f)
            lineTo(16.5f, 9.25f)
        }
    }

    /**
     * 설정 탭. Figma `cog`.
     *
     * 가운데 원을 크게 잡고 톱니를 짧게 두어야 해 모양이 되고, 톱니가 길어지면 태양 아이콘처럼 보인다.
     */
    val Settings: ImageVector by lazy {
        strokeIcon("Settings") {
            moveTo(12f, 14.6f)
            curveTo(10.564f, 14.6f, 9.4f, 13.436f, 9.4f, 12f)
            curveTo(9.4f, 10.564f, 10.564f, 9.4f, 12f, 9.4f)
            curveTo(13.436f, 9.4f, 14.6f, 10.564f, 14.6f, 12f)
            curveTo(14.6f, 13.436f, 13.436f, 14.6f, 12f, 14.6f)
            close()
            moveTo(19.2f, 12f)
            curveTo(19.2f, 12.5f, 19.16f, 12.99f, 19.08f, 13.46f)
            lineTo(20.9f, 14.7f)
            lineTo(19.5f, 17.1f)
            lineTo(17.46f, 16.3f)
            curveTo(16.76f, 16.98f, 15.92f, 17.5f, 15f, 17.82f)
            lineTo(14.7f, 20f)
            lineTo(9.3f, 20f)
            lineTo(9f, 17.82f)
            curveTo(8.08f, 17.5f, 7.24f, 16.98f, 6.54f, 16.3f)
            lineTo(4.5f, 17.1f)
            lineTo(3.1f, 14.7f)
            lineTo(4.92f, 13.46f)
            curveTo(4.84f, 12.99f, 4.8f, 12.5f, 4.8f, 12f)
            curveTo(4.8f, 11.5f, 4.84f, 11.01f, 4.92f, 10.54f)
            lineTo(3.1f, 9.3f)
            lineTo(4.5f, 6.9f)
            lineTo(6.54f, 7.7f)
            curveTo(7.24f, 7.02f, 8.08f, 6.5f, 9f, 6.18f)
            lineTo(9.3f, 4f)
            lineTo(14.7f, 4f)
            lineTo(15f, 6.18f)
            curveTo(15.92f, 6.5f, 16.76f, 7.02f, 17.46f, 7.7f)
            lineTo(19.5f, 6.9f)
            lineTo(20.9f, 9.3f)
            lineTo(19.08f, 10.54f)
            curveTo(19.16f, 11.01f, 19.2f, 11.5f, 19.2f, 12f)
            close()
        }
    }

    /** 가운데 촬영 액션. Figma `camera`. */
    val Camera: ImageVector by lazy {
        strokeIcon("Camera") {
            moveTo(4f, 8f)
            lineTo(8f, 8f)
            lineTo(9.5f, 5.5f)
            lineTo(14.5f, 5.5f)
            lineTo(16f, 8f)
            lineTo(20f, 8f)
            lineTo(20f, 19f)
            lineTo(4f, 19f)
            close()
            moveTo(12f, 16.5f)
            curveTo(10.067f, 16.5f, 8.5f, 14.933f, 8.5f, 13f)
            curveTo(8.5f, 11.067f, 10.067f, 9.5f, 12f, 9.5f)
            curveTo(13.933f, 9.5f, 15.5f, 11.067f, 15.5f, 13f)
            curveTo(15.5f, 14.933f, 13.933f, 16.5f, 12f, 16.5f)
            close()
        }
    }

    /** 상단 알림 버튼. Figma `bell-dot`. */
    val Notification: ImageVector by lazy {
        strokeIcon("Notification") {
            moveTo(18f, 10f)
            lineTo(18f, 14f)
            lineTo(20f, 18f)
            lineTo(4f, 18f)
            lineTo(6f, 14f)
            lineTo(6f, 10f)
            curveTo(6f, 6.686f, 8.686f, 4f, 12f, 4f)
            curveTo(13.1f, 4f, 14.14f, 4.3f, 15.03f, 4.82f)
            moveTo(10f, 21f)
            curveTo(11.2f, 22.2f, 12.8f, 22.2f, 14f, 21f)
            moveTo(19.5f, 6.5f)
            curveTo(19.5f, 7.605f, 18.605f, 8.5f, 17.5f, 8.5f)
            curveTo(16.395f, 8.5f, 15.5f, 7.605f, 15.5f, 6.5f)
            curveTo(15.5f, 5.395f, 16.395f, 4.5f, 17.5f, 4.5f)
            curveTo(18.605f, 4.5f, 19.5f, 5.395f, 19.5f, 6.5f)
            close()
        }
    }
}

/**
 * 24 격자에 2 두께 윤곽선으로 아이콘을 만든다.
 *
 * @param name 미리보기와 디버깅에 쓰는 아이콘 이름.
 * @param pathBuilder 24x24 좌표계로 그리는 경로.
 */
private inline fun strokeIcon(
    name: String,
    crossinline pathBuilder: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit,
): ImageVector =
    ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        .apply {
            path(
                stroke = SolidColor(androidx.compose.ui.graphics.Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = { pathBuilder() },
            )
        }
        .build()
