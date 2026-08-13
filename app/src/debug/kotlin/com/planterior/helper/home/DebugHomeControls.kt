package com.planterior.helper.home

import android.content.Context
import androidx.core.content.edit
import com.planterior.helper.feature.home.HomeWeather
import com.planterior.helper.feature.home.HomeWeatherRisk
import java.time.Instant

/**
 * QA가 홈의 부분 실패를 재현할 수 있게 하는 디버그 전용 날씨 공급자이다.
 *
 * 릴리스 소스 세트에는 같은 이름의 통과용 구현만 있어 이 시나리오 코드는 출시 아티팩트에 들어가지 않는다. 시나리오는 `adb shell am broadcast`가 아니라 앱
 * 전용 SharedPreferences로만 바뀌며 외부 입력을 신뢰하지 않는다.
 */
fun debugHomeWeatherSource(context: Context): HomeWeatherSource = HomeWeatherSource {
    when (scenario(context)) {
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
        else -> Result.success(null)
    }
}

/** QA 시나리오를 설정한다. 디버그 하네스와 계측 테스트만 호출한다. */
fun setDebugHomeScenario(context: Context, value: String) {
    context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit {
        putString(SCENARIO, value)
    }
}

private fun scenario(context: Context): String =
    context
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getString(SCENARIO, "")
        .orEmpty()

private const val PREFERENCES = "home-qa"
private const val SCENARIO = "scenario"

/** 날씨 저장소만 실패시킨다. 식물 관리 콘텐츠는 그대로 유지되어야 한다. */
const val WEATHER_FAILURE: String = "weather-failure"

/** 고온·건조 복수 위험. 홈은 우선순위가 가장 높은 하나만 보여줘야 한다. */
const val WEATHER_RISK: String = "weather-risk"

/** 위험 없는 정상 날씨. */
const val WEATHER_OK: String = "weather-ok"
