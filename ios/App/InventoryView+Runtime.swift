import Foundation
import PlanteriorDomain
import SwiftUI

extension InventoryView {
    static var initialMode: InventoryMode {
        #if DEBUG
            if ProcessInfo.processInfo.environment[
                "QA_INVENTORY_MODE"
            ] == "shop" {
                return .shop
            }
        #endif
        return .warehouse
    }

    var effectiveSizeCategory: ContentSizeCategory {
        #if DEBUG
            if ProcessInfo.processInfo.environment[
                "QA_INVENTORY_SIZE_CATEGORY"
            ] == "AX5" {
                return .accessibilityExtraExtraExtraLarge
            }
        #endif
        return sizeCategory
    }

    static func runtimeInstant() -> Instant? {
        #if DEBUG
            if let value = ProcessInfo.processInfo.environment[
                "QA_INVENTORY_NOW"
            ] {
                return try? Instant.parse(value)
            }
        #endif
        let formatter = ISO8601DateFormatter()
        return try? Instant.parse(formatter.string(from: Date()))
    }

    static var allowsLocalAcquisition: Bool {
        #if DEBUG
            return ProcessInfo.processInfo.environment[
                "QA_INVENTORY_FIXTURE"
            ] == "1"
        #else
            return false
        #endif
    }

    static var failsFirstAcquisition: Bool {
        #if DEBUG
            return ProcessInfo.processInfo.environment[
                "QA_INVENTORY_FAIL_ONCE"
            ] == "1"
        #else
            return false
        #endif
    }
}
