import Foundation
import SwiftUI

extension AppShellView {
    var effectiveShellSizeCategory: ContentSizeCategory {
        #if DEBUG
            if ProcessInfo.processInfo.environment[
                "QA_INVENTORY_SIZE_CATEGORY"
            ] == "AX5" {
                return .accessibilityExtraExtraExtraLarge
            }
            if ProcessInfo.processInfo.environment[
                "QA_PROGRESS_SIZE_CATEGORY"
            ] == "AX5" {
                return .accessibilityExtraExtraExtraLarge
            }
        #endif
        return sizeCategory
    }
}
