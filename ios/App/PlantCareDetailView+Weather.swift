import PlanteriorDomain
import SwiftUI

extension PlantCareDetailView {
    var weatherAlertToggle: some View {
        Toggle(
            "식물별 날씨 위험 알림",
            isOn: $weatherAlertsEnabled
        )
        .onChange(of: weatherAlertsEnabled) { _, enabled in
            guard let plantID = collection.weatherPlantID(at: index) else {
                return
            }
            LocalWeatherAlertStore.shared.setPlantEnabled(
                enabled,
                plantID: plantID
            )
            NotificationCenter.default.post(
                name: .weatherAlertPreferencesDidChange,
                object: nil
            )
        }
        .accessibilityIdentifier("weather.plant-alerts-enabled")
    }
}

extension Notification.Name {
    static let weatherAlertPreferencesDidChange = Notification.Name(
        "weatherAlertPreferencesDidChange"
    )
}
