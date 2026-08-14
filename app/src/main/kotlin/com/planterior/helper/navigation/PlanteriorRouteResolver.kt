package com.planterior.helper.navigation

/**
 * 외부에서 들어온 딥링크를 앱이 신뢰할 수 있는 route로 바꾼다.
 *
 * 외부 입력은 전부 적대적이라고 보고 허용 목록으로만 해석한다. 해석하지 못한 입력은 오류를 노출하는 대신 항상 [PlanteriorRoute.Home]으로 안전하게 되돌린다.
 */
object PlanteriorRouteResolver {
    /** 앱이 수신하는 딥링크 scheme. */
    const val SCHEME: String = "planterior"

    /** 개인 식물 ID로 허용하는 최대 길이. Firestore 문서 ID 상한과 같다. */
    private const val MAX_PLANT_ID_LENGTH = 64

    private val PLANT_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,$MAX_PLANT_ID_LENGTH}$")

    /**
     * 딥링크 URI 문자열을 route로 해석한다.
     *
     * @param uri `adb shell am start -a android.intent.action.VIEW`나 알림이 전달한 원본 문자열.
     * @return 해석에 성공하면 해당 route, 실패하면 [PlanteriorRoute.Home].
     */
    fun resolve(uri: String?): PlanteriorRoute {
        val parsed = parse(uri) ?: return PlanteriorRoute.Home
        val (host, segments) = parsed
        return when (host) {
            "home" -> PlanteriorRoute.Home
            "collection" -> resolveCollection(segments)
            "storage" -> PlanteriorRoute.Storage
            "settings" -> PlanteriorRoute.Settings
            "camera" -> PlanteriorRoute.Camera
            "identify" -> resolveIdentification(segments)
            "registration" -> PlanteriorRoute.Registration
            "minihome" -> PlanteriorRoute.MiniHome
            "notifications" -> PlanteriorRoute.Notifications
            else -> PlanteriorRoute.Home
        }
    }

    /**
     * 로그인 후 복귀할 route를 해석한다.
     *
     * 로그인 화면은 자기 자신으로 되돌아갈 수 없고, 공개 그래프 목적지도 복귀 대상이 아니다.
     *
     * @param returnRoute [PlanteriorRoute.Login.returnRoute]로 전달된 문자열.
     * @return 허용된 인증 후 목적지, 없거나 허용되지 않으면 [PlanteriorRoute.Home].
     */
    fun resolveReturnRoute(returnRoute: String?): PlanteriorRoute.Authenticated =
        when (val resolved = resolve(returnRoute)) {
            is PlanteriorRoute.Authenticated -> resolved
            else -> PlanteriorRoute.Home
        }

    /**
     * 딥링크 백스택을 만든다.
     *
     * cold start로 하위 화면에 바로 진입해도 사용자가 뒤로 가기로 상위 화면에 도달할 수 있어야 한다.
     *
     * @param route 최종 목적지.
     * @return 루트부터 목적지까지의 순서 있는 스택. 항상 [PlanteriorRoute.Home]으로 시작한다.
     */
    fun backStackFor(route: PlanteriorRoute): List<PlanteriorRoute> =
        when (route) {
            is PlanteriorRoute.Home -> listOf(PlanteriorRoute.Home)
            is PlanteriorRoute.PlantDetail ->
                listOf(PlanteriorRoute.Home, PlanteriorRoute.Collection, route)
            else -> listOf(PlanteriorRoute.Home, route)
        }

    private fun resolveCollection(segments: List<String>): PlanteriorRoute =
        when {
            segments.isEmpty() -> PlanteriorRoute.Collection
            segments.size == 2 && segments[0] == "plant" && PLANT_ID_PATTERN.matches(segments[1]) ->
                PlanteriorRoute.PlantDetail(segments[1])
            else -> PlanteriorRoute.Home
        }

    private fun resolveIdentification(segments: List<String>): PlanteriorRoute =
        if (segments.size == 1 && PLANT_ID_PATTERN.matches(segments[0])) {
            PlanteriorRoute.Identification(segments[0])
        } else {
            PlanteriorRoute.Home
        }

    private fun parse(uri: String?): Pair<String, List<String>>? {
        val trimmed = uri?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val prefix = "$SCHEME://"
        if (!trimmed.startsWith(prefix)) return null
        val body = trimmed.removePrefix(prefix).substringBefore('?').substringBefore('#')
        val parts = body.split('/').filter { it.isNotEmpty() }
        val host = parts.firstOrNull() ?: return null
        return host to parts.drop(1)
    }
}
