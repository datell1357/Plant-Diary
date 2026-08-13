package com.planterior.helper.feature.home

/**
 * Robolectric이 실행할 수 있는 최신 SDK이다.
 *
 * 앱은 API 37을 target 하지만 Robolectric 4.16은 API 36까지만 지원한다. 실제 API 29·37 동작은 에뮬레이터 QA로 확인한다.
 */
internal const val ROBOLECTRIC_MAX_SDK: Int = 36
