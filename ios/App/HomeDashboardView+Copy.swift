import Foundation
import PlanteriorData
import SwiftUI

/// Figma `top-area-wrapper` / care-header copy (figma-analysis §6.2/§6.4/§6.5).
extension HomeDashboardView {
    /// Figma §6.2 greeting name. Signed-out renders the guest variant.
    var profileName: String {
        #if DEBUG
            if let name = ProcessInfo.processInfo.environment[
                "QA_HOME_PROFILE_NAME"
            ], !name.isEmpty {
                return name
            }
        #endif
        return authenticationState == .authenticated ? "집사" : "게스트"
    }

    /// §6.2 subline: "서울 성동구 · 28°C" / signed-out "위치 미설정 · - °C".
    /// The banner (§6.4) carries the risk copy, so the subline stays a
    /// location-and-temperature line even when a risk is active.
    var greetingMeta: String {
        guard authenticationState == .authenticated else {
            return "위치 미설정 · - °C"
        }
        let region = weatherRuntime.effectiveRegionName ?? "위치 미설정"
        guard let temperature = weatherTemperatureText else {
            return "\(region) · - °C"
        }
        return "\(region) · \(temperature)"
    }

    /// The weather summary leads with "NN℃" only when no risk is active; when a
    /// risk fires it reports the count instead. Either way the subline shows the
    /// live weather word for the region.
    private var weatherTemperatureText: String? {
        guard case let .content(summary) = store.snapshot.weather else {
            return nil
        }
        let degrees = summary.split(separator: "℃").first
        let value = degrees.flatMap {
            Int($0.trimmingCharacters(in: .whitespaces))
        }
        guard let value else {
            return summary
        }
        return "\(value)℃"
    }

    /// §6.2 title row. A committed `MiniHome.name` is the only persisted room
    /// title; owner-derived copy is used only before a room exists.
    var roomTitle: String {
        if let name = store.miniHome?.name, !name.isEmpty {
            return name
        }
        return authenticationState == .authenticated
            ? "\(profileName)의 미니 식물원"
            : "나의 미니 식물원"
    }

    /// §6.4 amber banner copy; geometry is identical across auth states.
    var weatherWarningText: String {
        guard authenticationState == .authenticated else {
            return "로그인하면 내 지역의 날씨 기반 식물 관리 알림을 받을 수 있어요!"
        }
        return "오늘 기온이 높아요! 강한 직사광선을 피해 통풍이 잘되는 그늘로 식물을 피해주세요."
    }

    /// §6.5 count chip: "오늘 N개" when signed in, "0개" in the zero state.
    var careBadgeText: String {
        let count = store.snapshot.careItems.count
        guard authenticationState == .authenticated, count > 0 else {
            return "0개"
        }
        return "오늘 \(count)개"
    }
}
