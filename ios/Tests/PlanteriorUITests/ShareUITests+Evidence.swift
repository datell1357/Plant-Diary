import XCTest

extension ShareUITests {
    func attachShareEvidence(digest: String) {
        attachJSON(
            [
                "width": 1200,
                "height": 1200,
                "format": "PNG",
                "sourceRevision": 1,
                "offlineImage": true
            ],
            named: "task-17-share-image"
        )
        attachJSON(
            [
                "algorithm": "SHA-256",
                "digest": digest,
                "deterministicRepeat": true,
                "rawTokenIncluded": false
            ],
            named: "task-17-share-digest"
        )
        attachJSON(
            [
                "forbiddenMatches": 0,
                "fields": [
                    "note", "location", "ownerUID",
                    "representativePhotoPath", "rawToken",
                    "privateURL", "draft"
                ]
            ],
            named: "task-17-share-redaction"
        )
        attachLifecycleEvidence()
    }

    private func attachLifecycleEvidence() {
        attachJSON(
            [
                "sourceRevision": 1,
                "lifetimeDays": 30,
                "tokenLength": 32,
                "backendIntegration": "unavailable"
            ],
            named: "task-17-share-link"
        )
        attachJSON(
            ["result": "revoked", "postRevokeReadable": false],
            named: "task-17-share-revoke"
        )
        attachJSON(
            [
                "readableBeforeExpiry": true,
                "readableAtExpiry": false,
                "readableAtDay31": false
            ],
            named: "task-17-share-expiry"
        )
        attachJSON(
            [
                "result": "cancelled",
                "error": false,
                "progressionAward": false
            ],
            named: "task-17-share-cancel"
        )
    }
}
