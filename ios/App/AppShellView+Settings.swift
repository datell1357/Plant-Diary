extension AppShellView {
    var showsSettingsRootControls: Bool {
        navigation.selectedTab == .settings &&
            navigation.settingsPath.isEmpty
    }
}
