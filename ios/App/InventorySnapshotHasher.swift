import CryptoKit
import Foundation

enum InventorySnapshotHasher {
    static func hash(_ snapshot: InventorySnapshotResponse) -> String {
        var lines = [
            "INVENTORY-SNAPSHOT-V3",
            encoded(snapshot.ownerUid),
            String(snapshot.registeredPlantCount),
            snapshot.partial ? "1" : "0"
        ]
        lines += snapshot.catalog
            .sorted { $0.itemId < $1.itemId }
            .map(catalogLine)
        lines += snapshot.owned
            .sorted { $0.itemId < $1.itemId }
            .map(ownedLine)
        let digest = SHA256.hash(
            data: Data(lines.joined(separator: "\n").utf8)
        )
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    private static func catalogLine(
        _ item: InventoryCatalogResponse
    ) -> String {
        [
            "C", encoded(item.itemId), encoded(item.name),
            encoded(item.description), item.category,
            encoded(item.mediaIdentity.path), item.mediaIdentity.sha256,
            String(item.mediaIdentity.byteSize), item.mediaIdentity.mimeType,
            String(item.mediaIdentity.width), String(item.mediaIdentity.height),
            String(item.mediaIdentity.mediaRevision),
            item.acquisitionCondition ?? "~", String(item.revision),
            String(item.updatedAtEpochMillis)
        ].joined(separator: "\t")
    }

    private static func ownedLine(
        _ item: InventoryOwnedResponse
    ) -> String {
        let snapshot = item.catalogSnapshot
        return [
            "O", encoded(item.itemId), String(item.acquiredAtEpochMillis),
            String(item.revision), item.availability, encoded(snapshot?.name),
            snapshot?.category ?? "~", encoded(snapshot?.mediaIdentity.path),
            snapshot?.mediaIdentity.sha256 ?? "~",
            snapshot.map { String($0.mediaIdentity.byteSize) } ?? "~",
            snapshot?.mediaIdentity.mimeType ?? "~",
            snapshot.map { String($0.mediaIdentity.width) } ?? "~",
            snapshot.map { String($0.mediaIdentity.height) } ?? "~",
            snapshot.map { String($0.mediaIdentity.mediaRevision) } ?? "~",
            snapshot.map { String($0.catalogRevision) } ?? "~"
        ].joined(separator: "\t")
    }

    private static func encoded(_ value: String?) -> String {
        guard let value else { return "~" }
        return Data(value.utf8).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
