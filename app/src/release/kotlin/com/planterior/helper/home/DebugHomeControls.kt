package com.planterior.helper.home

import android.content.Context

/**
 * 출시 빌드의 날씨 공급자이다.
 *
 * 실제 날씨 연동은 이후 todo에서 붙인다. 지금은 지역이 없다는 사실만 정직하게 돌려주고, 디버그 QA 시나리오 코드는 이 소스 세트에 존재하지 않는다.
 */
fun debugHomeWeatherSource(context: Context): HomeWeatherSource = HomeWeatherSource {
    Result.success(null)
}
