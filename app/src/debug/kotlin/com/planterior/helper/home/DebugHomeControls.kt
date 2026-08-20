package com.planterior.helper.home

import android.content.Context
import androidx.core.content.edit
import com.planterior.helper.feature.home.HomeSession
import com.planterior.helper.feature.home.HomeWeather
import com.planterior.helper.feature.home.HomeWeatherRisk
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * QA가 홈의 각 상태를 결정적으로 재현할 수 있게 하는 디버그 전용 제어이다.
 *
 * 릴리스 소스 세트에는 아무것도 하지 않는 같은 이름의 구현만 있어 이 코드는 출시 아티팩트에 들어가지 않는다. 제어 범위는 **세션과 날씨뿐**이며 식물·미니홈피·동기화
 * 데이터는 계속 실제 Room 캐시에서 읽는다. 그래야 계측 테스트가 진짜 저장된 데이터를 검증할 수 있다.
 */
fun debugHomeWeatherSource(
    context: Context,
    fallback: HomeWeatherSource,
): HomeWeatherSource = HomeWeatherSource {
    when (weatherScenario(context)) {
        WEATHER_FAILURE -> Result.failure(IllegalStateException("QA weather provider failure"))
        WEATHER_RISK ->
            Result.success(
                HomeWeather(
                    regionName = "서울 성동구",
                    temperatureCelsius = 35.0,
                    observedAt = Instant.parse("2026-08-12T00:00:00Z"),
                    risks =
                        listOf(
                            HomeWeatherRisk.Dry("공기가 건조해요. 잎에 분무해 주세요."),
                            HomeWeatherRisk.HighTemperature(
                                "오늘 기온이 35°C로 높아요! 강한 직사광선을 피해 통풍이 잘되는 그늘로 식물을 옮겨주세요."
                            ),
                        ),
                )
            )
        WEATHER_OK ->
            Result.success(
                HomeWeather(
                    regionName = "서울 성동구",
                    temperatureCelsius = 28.0,
                    observedAt = Instant.parse("2026-08-12T00:00:00Z"),
                    risks = emptyList(),
                )
            )
        // 시나리오를 지정하지 않으면 아직 지역이 없다는 뜻이며 실패가 아니다.
        else -> fallback.current()
    }
}

/**
 * QA가 세션을 고정하고 싶을 때 쓰는 흐름이다.
 *
 * 실제 계정을 만들지 않고도 로그인 전·복원 중·로그인 후를 결정적으로 재현한다. 로그인 후로 고정하면 저장소는 여기서 정한 계정 ID로 **실제 Room 캐시**를
 * 조회하므로, 계측 테스트가 미리 넣어 둔 식물·미니홈피·동기화 기록이 그대로 화면에 나타난다.
 *
 * @return 고정할 세션 흐름. 시나리오가 없으면 `null`이고 이때는 실제 인증 상태를 그대로 쓴다.
 */
fun debugHomeAccountUid(context: Context): String? =
    sessionAccount(context).takeIf {
        sessionScenario(context) == SESSION_SIGNED_IN && it.isNotBlank()
    }

fun debugHomeSessions(context: Context): Flow<HomeSession>? =
    when (sessionScenario(context)) {
        SESSION_LOGGED_OUT -> flowOf(HomeSession.SignedOut)
        SESSION_RESTORING -> flowOf(HomeSession.Restoring)
        SESSION_SIGNED_IN ->
            flowOf(
                HomeSession.SignedIn(
                    accountUid = sessionAccount(context),
                    displayName = sessionDisplayName(context).takeIf(String::isNotBlank),
                    zoneId = ZoneId.systemDefault(),
                )
            )
        else -> null
    }

/** 날씨 QA 시나리오를 설정한다. 디버그 하네스와 계측 테스트만 호출한다. */
fun setDebugHomeScenario(context: Context, value: String) {
    preferences(context).edit { putString(SCENARIO, value) }
}

/**
 * 세션 QA 시나리오를 설정한다.
 *
 * @param accountUid [SESSION_SIGNED_IN]에서 실제 캐시를 조회할 계정 ID.
 */
fun setDebugHomeSession(
    context: Context,
    value: String,
    accountUid: String = "",
    displayName: String = "",
) {
    preferences(context).edit {
        putString(SESSION, value)
        putString(SESSION_ACCOUNT, accountUid)
        putString(SESSION_NAME, displayName)
    }
}

private fun preferences(context: Context) =
    context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

private fun weatherScenario(context: Context): String =
    preferences(context).getString(SCENARIO, "").orEmpty()

private fun sessionScenario(context: Context): String =
    preferences(context).getString(SESSION, "").orEmpty()

private fun sessionAccount(context: Context): String =
    preferences(context).getString(SESSION_ACCOUNT, "").orEmpty()

private fun sessionDisplayName(context: Context): String =
    preferences(context).getString(SESSION_NAME, "").orEmpty()

private const val PREFERENCES = "home-qa"
private const val SCENARIO = "scenario"
private const val SESSION = "session"
private const val SESSION_ACCOUNT = "session-account"
private const val SESSION_NAME = "session-name"

/** 날씨 저장소만 실패시킨다. 식물 관리 콘텐츠는 그대로 유지되어야 한다. */
const val WEATHER_FAILURE: String = "weather-failure"

/** 고온·건조 복수 위험. 홈은 우선순위가 가장 높은 하나만 보여줘야 한다. */
const val WEATHER_RISK: String = "weather-risk"

/** 위험 없는 정상 날씨. */
const val WEATHER_OK: String = "weather-ok"

/** 로그인 전 홈. */
const val SESSION_LOGGED_OUT: String = "session-logged-out"

/** 세션 복원 중. 홈은 로그아웃으로 단정하지 않아야 한다. */
const val SESSION_RESTORING: String = "session-restoring"

/** 지정한 계정으로 로그인한 상태. 데이터는 실제 캐시에서 읽는다. */
const val SESSION_SIGNED_IN: String = "session-signed-in"
