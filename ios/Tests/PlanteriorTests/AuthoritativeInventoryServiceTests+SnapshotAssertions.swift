import Foundation
@testable import Planterior
import Testing

extension AuthoritativeInventoryServiceTests {
    func assertSnapshotTamperingIsRejected(source: String) {
        let mediaHash = String(repeating: "a", count: 64)
        let tamperedSnapshots = [
            source.replacingOccurrences(
                of: "\"itemId\":\"item-lamp\"",
                with: "\"itemId\":\"item-vintage-lamp\""
            ),
            source.replacingOccurrences(
                of: "\"revision\":3",
                with: "\"revision\":4"
            ),
            source.replacingOccurrences(
                of: "\"revision\":5",
                with: "\"revision\":6"
            ),
            source.replacingOccurrences(
                of: "\"acquiredAtEpochMillis\":1787616000000",
                with: "\"acquiredAtEpochMillis\":1787616000001"
            ),
            source.replacingOccurrences(
                of: "\"sha256\":\"\(mediaHash)\"",
                with: "\"sha256\":\"\(String(repeating: "b", count: 64))\""
            )
        ]
        for tampered in tamperedSnapshots {
            #expect(throws: InventoryProviderError.malformedResponse) {
                try AuthoritativeInventoryResponseDecoder.snapshot(
                    data: Data(tampered.utf8),
                    expectedAccountID: "inventory-account-a"
                )
            }
        }
    }
}
