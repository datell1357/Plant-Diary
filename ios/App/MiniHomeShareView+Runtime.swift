import Foundation
import PlanteriorDomain

extension MiniHomeShareView {
    static var allowsProvisionalLinks: Bool {
        #if DEBUG
            return ProcessInfo.processInfo.environment[
                "QA_SHARE_FIXTURE"
            ] == "1"
        #else
            return false
        #endif
    }

    static var isOnline: Bool {
        #if DEBUG
            return ProcessInfo.processInfo.environment[
                "QA_SHARE_ONLINE"
            ] != "0"
        #else
            return true
        #endif
    }

    static var runtimeNow: Instant? {
        #if DEBUG
            if let value = ProcessInfo.processInfo.environment[
                "QA_SHARE_NOW"
            ] {
                return try? Instant.parse(value)
            }
        #endif
        return try? Instant.parse("2026-08-11T00:00:00Z")
    }

    static func qaRandomBytes() throws -> Data {
        #if DEBUG
            if allowsProvisionalLinks {
                return Data(repeating: 7, count: 24)
            }
        #endif
        throw ShareSnapshotError.invalidRandomBytes
    }
}
