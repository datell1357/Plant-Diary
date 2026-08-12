package com.planterior.helper.core.designsystem

/**
 * Robolectric이 지원하는 최신 SDK이다.
 *
 * 앱은 API 37을 target 하지만 Robolectric 4.16은 API 36까지만 실행할 수 있다. 컴포넌트 테스트는 이 값으로 고정하고, 실제 API 29·최신
 * API 동작은 에뮬레이터 시각 QA로 확인한다.
 */
internal const val ROBOLECTRIC_MAX_SDK: Int = 36
