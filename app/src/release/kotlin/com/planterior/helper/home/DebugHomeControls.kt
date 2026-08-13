package com.planterior.helper.home

import android.content.Context
import com.planterior.helper.feature.home.HomeSession
import kotlinx.coroutines.flow.Flow

/**
 * 출시 빌드의 날씨 공급자이다.
 *
 * 실제 날씨 연동은 이후 todo에서 붙인다. 지금은 지역이 없다는 사실만 정직하게 돌려주고, 디버그 QA 시나리오 코드는 이 소스 세트에 존재하지 않는다.
 */
fun debugHomeWeatherSource(context: Context): HomeWeatherSource = HomeWeatherSource {
    Result.success(null)
}

/** 출시 빌드에는 세션을 가로채는 경로가 없다. 항상 실제 인증 상태만 쓴다. */
fun debugHomeSessions(context: Context): Flow<HomeSession>? = null
