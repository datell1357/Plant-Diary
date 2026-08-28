import Foundation

enum CameraPresentationMode: Equatable {
    case deterministicFixture
    case liveCapture
}

enum CameraPresentationPolicy {
    static func mode(
        isSimulator: Bool,
        isDebug: Bool,
        environment: [String: String]
    ) -> CameraPresentationMode {
        let usesExplicitFixture = isDebug
            && environment["QA_CAMERA_STATIC_FIXTURE"] == "1"
        return isSimulator || usesExplicitFixture
            ? .deterministicFixture
            : .liveCapture
    }

    static var current: CameraPresentationMode {
        #if targetEnvironment(simulator)
            let isSimulator = true
        #else
            let isSimulator = false
        #endif
        #if DEBUG
            let isDebug = true
        #else
            let isDebug = false
        #endif
        return mode(
            isSimulator: isSimulator,
            isDebug: isDebug,
            environment: ProcessInfo.processInfo.environment
        )
    }
}
