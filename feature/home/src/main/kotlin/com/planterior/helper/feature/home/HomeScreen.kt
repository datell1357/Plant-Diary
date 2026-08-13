package com.planterior.helper.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.planterior.helper.core.designsystem.component.PlanteriorCard
import com.planterior.helper.core.designsystem.icon.PlanteriorIcons
import com.planterior.helper.core.designsystem.theme.PlanteriorBorderWidth
import com.planterior.helper.core.designsystem.theme.PlanteriorRadius
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme

/** Figma `top-area-wrapper`의 아바타 지름. */
private val AvatarSize = 40.dp

/** Figma 관리 카드의 식물 썸네일 크기. */
private val ThumbnailSize = 48.dp

/** Figma `bell-dot` 원형 버튼 지름. */
private val NotificationButtonSize = 40.dp

/** Figma `bell-dot` 아이콘 크기. */
private val NotificationIconSize = 20.dp

/** 접근성 최소 터치 대상. */
private val MinimumTouchTarget = 48.dp

/** Figma `main-content`의 미니홈피 미리보기 비율(370x306). */
private const val PreviewAspectRatio = 370f / 306f

/** 화면 요소를 계층 덤프와 UI 테스트에서 찾기 위한 태그이다. */
object HomeTestTags {
    const val GREETING: String = "home:greeting"
    const val SIGN_IN: String = "home:sign-in"
    const val NOTIFICATION: String = "home:notification"
    const val MINI_HOME: String = "home:mini-home"
    const val WEATHER_RISK: String = "home:weather-risk"
    const val WEATHER_UNAVAILABLE: String = "home:weather-unavailable"
    const val SYNC_STALE: String = "home:sync-stale"
    const val CARE_SECTION: String = "home:care-section"
    const val CARE_ITEM: String = "home:care-item"
    const val IDENTIFY_CTA: String = "home:identify"
    const val EMPTY: String = "home:empty"
    const val ERROR: String = "home:error"
}

/**
 * 홈 대시보드이다.
 *
 * 로그인 전·후, 빈 상태, 콘텐츠, 날씨 부분 실패, 동기화 지연을 한 화면에서 모두 표현한다. 어떤 상태에서도 실제로 없는 식물이나 아이템을 지어내지 않는다.
 *
 * @param state 표시할 홈 상태.
 * @param onSignIn 로그인 화면으로 이동한다.
 * @param onNotifications 알림 목록으로 이동한다.
 * @param onIdentify 사진 식별 흐름을 시작한다.
 * @param onOpenMiniHome 미니홈피로 이동한다.
 * @param onOpenCollection 개인 도감으로 이동한다.
 * @param onOpenPlant 식물 상세로 이동한다. 인자는 불투명 domain ID이다.
 * @param bottomBar 하단 내비게이션.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onSignIn: () -> Unit,
    onNotifications: () -> Unit,
    onIdentify: () -> Unit,
    onOpenMiniHome: () -> Unit,
    onOpenCollection: () -> Unit,
    onOpenPlant: (String) -> Unit,
    modifier: Modifier = Modifier,
    strings: HomeStrings = HomeStrings.Korean,
    bottomBar: @Composable () -> Unit = {},
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        bottomBar = bottomBar,
    ) { innerPadding ->
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .padding(innerPadding)
                    .statusBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = PlanteriorTheme.spacing.extraLarge,
                        vertical = PlanteriorTheme.spacing.large,
                    ),
            verticalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.large),
        ) {
            when (state) {
                HomeUiState.Loading -> Unit
                HomeUiState.LoggedOut ->
                    LoggedOutHome(strings, onSignIn, onNotifications, onIdentify)
                is HomeUiState.Empty ->
                    EmptyHome(state, strings, onNotifications, onIdentify, onOpenCollection)
                is HomeUiState.Content ->
                    ContentHome(
                        state = state,
                        strings = strings,
                        onNotifications = onNotifications,
                        onIdentify = onIdentify,
                        onOpenMiniHome = onOpenMiniHome,
                        onOpenCollection = onOpenCollection,
                        onOpenPlant = onOpenPlant,
                    )
                is HomeUiState.Error -> ErrorHome(state, strings, onNotifications)
            }
        }
    }
}

@Composable
private fun LoggedOutHome(
    strings: HomeStrings,
    onSignIn: () -> Unit,
    onNotifications: () -> Unit,
    onIdentify: () -> Unit,
) {
    GreetingRow(
        name = null,
        subtitle = null,
        strings = strings,
        onNotifications = onNotifications,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(
            onClick = onSignIn,
            modifier =
                Modifier.sizeIn(minHeight = MinimumTouchTarget).testTag(HomeTestTags.SIGN_IN),
        ) {
            Text(text = strings.signIn, style = MaterialTheme.typography.bodyLarge)
        }
    }
    MiniHomePlaceholder(strings.miniHomeLoggedOut)
    Text(
        text = strings.loggedOutDescription,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
    IdentifyButton(strings, onIdentify)
}

@Composable
private fun EmptyHome(
    state: HomeUiState.Empty,
    strings: HomeStrings,
    onNotifications: () -> Unit,
    onIdentify: () -> Unit,
    onOpenCollection: () -> Unit,
) {
    GreetingRow(
        name = state.greetingName,
        subtitle = state.weather.subtitle(strings),
        strings = strings,
        onNotifications = onNotifications,
    )
    DegradationNotices(state.weather, state.sync, strings)
    MiniHomePlaceholder(strings.miniHomeEmpty)
    PlanteriorCard(modifier = Modifier.testTag(HomeTestTags.EMPTY)) {
        Text(
            text = strings.emptyTitle,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth().semantics { heading() },
        )
        Text(
            text = strings.emptyDescription,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = PlanteriorTheme.spacing.medium),
        )
    }
    IdentifyButton(strings, onIdentify)
    TextButton(
        onClick = onOpenCollection,
        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = MinimumTouchTarget),
    ) {
        Text(text = strings.registerManually, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ContentHome(
    state: HomeUiState.Content,
    strings: HomeStrings,
    onNotifications: () -> Unit,
    onIdentify: () -> Unit,
    onOpenMiniHome: () -> Unit,
    onOpenCollection: () -> Unit,
    onOpenPlant: (String) -> Unit,
) {
    GreetingRow(
        name = state.greetingName,
        subtitle = state.weather.subtitle(strings),
        strings = strings,
        onNotifications = onNotifications,
    )
    MiniHomePreview(state.miniHome, strings, onOpenMiniHome)
    DegradationNotices(state.weather, state.sync, strings)
    WeatherRiskBanner(state.weather)
    CareSection(
        items = state.careItems,
        dueTodayCount = state.dueTodayCount,
        strings = strings,
        onOpenCollection = onOpenCollection,
        onOpenPlant = onOpenPlant,
    )
    IdentifyButton(strings, onIdentify)
}

@Composable
private fun ErrorHome(
    state: HomeUiState.Error,
    strings: HomeStrings,
    onNotifications: () -> Unit,
) {
    GreetingRow(
        name = null,
        subtitle = null,
        strings = strings,
        onNotifications = onNotifications,
    )
    if (state.sync is HomeSyncState.Stale) StaleNotice(state.sync, strings)
    PlanteriorCard(modifier = Modifier.testTag(HomeTestTags.ERROR)) {
        Text(
            text = strings.errorTitle,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth().semantics { heading() },
        )
        Text(
            text = strings.errorDescription,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = PlanteriorTheme.spacing.medium),
        )
    }
}

/** Figma `top-area-wrapper`. 아바타 + 인사말 + 부제 + 알림 버튼. */
@Composable
private fun GreetingRow(
    name: String?,
    subtitle: String?,
    strings: HomeStrings,
    onNotifications: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.large),
    ) {
        Box(
            modifier =
                Modifier.size(AvatarSize)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                    .clearAndSetSemantics {}
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = strings.greeting(name),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier.fillMaxWidth().testTag(HomeTestTags.GREETING).semantics {
                        heading()
                    },
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(top = PlanteriorTheme.spacing.small),
                )
            }
        }
        NotificationButton(strings, onNotifications)
    }
}

/**
 * Figma `bell-dot` 원형 버튼이다.
 *
 * 원은 40dp이지만 터치 대상은 48dp로 넓힌다. 아이콘 크기는 Figma가 정한 20dp를 그대로 유지한다.
 */
@Composable
private fun NotificationButton(strings: HomeStrings, onNotifications: () -> Unit) {
    Box(
        modifier =
            Modifier.sizeIn(minWidth = MinimumTouchTarget, minHeight = MinimumTouchTarget)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onNotifications,
                )
                .testTag(HomeTestTags.NOTIFICATION)
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = strings.notifications
                },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier.size(NotificationButtonSize)
                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                    .border(
                        PlanteriorBorderWidth,
                        MaterialTheme.colorScheme.outline,
                        CircleShape,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = PlanteriorIcons.Notification,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(NotificationIconSize),
            )
        }
    }
}

/** Figma `main-content`의 미니홈피 미리보기 카드. */
@Composable
private fun MiniHomePreview(
    preview: HomeMiniHomePreview?,
    strings: HomeStrings,
    onOpenMiniHome: () -> Unit,
) {
    Text(
        text = preview?.title ?: strings.miniHomeDefaultTitle,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().semantics { heading() },
    )
    PlanteriorCard(
        modifier =
            Modifier.aspectRatio(PreviewAspectRatio).testTag(HomeTestTags.MINI_HOME).semantics(
                mergeDescendants = true
            ) {
                contentDescription = strings.miniHomeAction
            },
        onClick = onOpenMiniHome,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentPadding = PlanteriorTheme.spacing.extraLarge,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text =
                    preview?.let { strings.miniHomePlacedCount(it.placedPlantCount) }
                        ?: strings.miniHomeEmpty,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 미니홈피를 아직 만들 수 없는 상태에서 자리만 잡아 준다. 가짜 배치 식물은 그리지 않는다. */
@Composable
private fun MiniHomePlaceholder(message: String) {
    PlanteriorCard(
        modifier = Modifier.aspectRatio(PreviewAspectRatio).testTag(HomeTestTags.MINI_HOME),
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 날씨 실패와 동기화 지연을 각각 독립적으로 알린다. 하나가 떠도 나머지 콘텐츠는 그대로 쓸 수 있다. */
@Composable
private fun DegradationNotices(
    weather: HomeWeatherState,
    sync: HomeSyncState,
    strings: HomeStrings,
) {
    if (weather is HomeWeatherState.Unavailable) {
        Notice(
            text = strings.weatherUnavailable,
            tag = HomeTestTags.WEATHER_UNAVAILABLE,
            container = MaterialTheme.colorScheme.surface,
            border = MaterialTheme.colorScheme.outline,
            content = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (sync is HomeSyncState.Stale) StaleNotice(sync, strings)
}

@Composable
private fun StaleNotice(sync: HomeSyncState.Stale, strings: HomeStrings) {
    Notice(
        text = strings.syncStale(sync.lastSuccessfulAt),
        tag = HomeTestTags.SYNC_STALE,
        container = MaterialTheme.colorScheme.surface,
        border = MaterialTheme.colorScheme.outline,
        content = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Figma `main-content`의 노란 경고 배너. 우선순위가 가장 높은 위험 하나만 그린다. */
@Composable
private fun WeatherRiskBanner(weather: HomeWeatherState) {
    val risk = (weather as? HomeWeatherState.Available)?.topRisk ?: return
    Notice(
        text = risk.message,
        tag = HomeTestTags.WEATHER_RISK,
        container = MaterialTheme.colorScheme.errorContainer,
        border = PlanteriorTheme.warningBorder,
        content = MaterialTheme.colorScheme.onErrorContainer,
    )
}

@Composable
private fun Notice(
    text: String,
    tag: String,
    container: Color,
    border: Color,
    content: Color,
) {
    val shape = RoundedCornerShape(PlanteriorRadius.Medium)
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = content,
        modifier =
            Modifier.fillMaxWidth()
                .background(container, shape)
                .border(PlanteriorBorderWidth, border, shape)
                .padding(PlanteriorTheme.spacing.extraLarge)
                .testTag(tag),
    )
}

/** Figma `오늘의 식물 관리` 섹션. 헤더 + 배지 + 일정 더보기 + 카드 목록. */
@Composable
private fun CareSection(
    items: List<HomeCareItem>,
    dueTodayCount: Int,
    strings: HomeStrings,
    onOpenCollection: () -> Unit,
    onOpenPlant: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag(HomeTestTags.CARE_SECTION),
        verticalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.medium),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.medium),
        ) {
            Text(
                text = strings.careSectionTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = strings.dueTodayBadge(dueTodayCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier =
                    Modifier.background(
                            MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(PlanteriorRadius.Small),
                        )
                        .padding(
                            horizontal = PlanteriorTheme.spacing.medium,
                            vertical = PlanteriorTheme.spacing.small,
                        ),
            )
            Box(modifier = Modifier.weight(1f))
            TextButton(
                onClick = onOpenCollection,
                modifier = Modifier.sizeIn(minHeight = MinimumTouchTarget),
            ) {
                Text(text = strings.moreSchedule, style = MaterialTheme.typography.bodyMedium)
            }
        }
        items.forEach { item -> CareCard(item, strings, onOpenPlant) }
    }
}

@Composable
private fun CareCard(item: HomeCareItem, strings: HomeStrings, onOpenPlant: (String) -> Unit) {
    PlanteriorCard(
        modifier = Modifier.testTag("${HomeTestTags.CARE_ITEM}:${item.plantId}"),
        onClick = { onOpenPlant(item.plantId) },
        contentPadding = PlanteriorTheme.spacing.large,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PlanteriorTheme.spacing.large),
        ) {
            Box(
                modifier =
                    Modifier.size(ThumbnailSize)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(PlanteriorRadius.Card),
                        )
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = strings.careStatus(item.status),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = PlanteriorTheme.spacing.small),
                )
            }
            CareBadge(item.status, strings)
        }
    }
}

/** Figma의 `물주기 완료` 알약과 `D-3` 배지. 상태에 따라 강조 정도가 다르다. */
@Composable
private fun CareBadge(status: HomeCareStatus, strings: HomeStrings) {
    val emphasised = status == HomeCareStatus.DueToday || status is HomeCareStatus.Overdue
    Text(
        text = strings.careBadge(status),
        style = MaterialTheme.typography.bodyMedium,
        color =
            if (emphasised) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier =
            Modifier.background(
                    if (emphasised) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.primaryContainer,
                    RoundedCornerShape(PlanteriorRadius.Medium),
                )
                .padding(
                    horizontal = PlanteriorTheme.spacing.large,
                    vertical = PlanteriorTheme.spacing.medium,
                ),
    )
}

@Composable
private fun IdentifyButton(strings: HomeStrings, onIdentify: () -> Unit) {
    Button(
        onClick = onIdentify,
        modifier =
            Modifier.fillMaxWidth()
                .sizeIn(minHeight = MinimumTouchTarget)
                .testTag(HomeTestTags.IDENTIFY_CTA),
    ) {
        Text(text = strings.identify, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun HomeWeatherState.subtitle(strings: HomeStrings): String? =
    when (this) {
        is HomeWeatherState.Available -> strings.weatherSubtitle(regionName, temperatureCelsius)
        HomeWeatherState.Unavailable,
        HomeWeatherState.NotConfigured -> null
    }
