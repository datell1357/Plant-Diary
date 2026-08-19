import XCTest

extension ProgressionUITests {
    func attachEvidence(in app: XCUIApplication) {
        let stateElement = app.staticTexts[
            "milestone.state.registration-1"
        ]
        let claimedState = stateElement.value as? String ?? ""
        attachProgressSummary()
        attachDuplicateAndClaim(claimedState: claimedState)
        attachOfflineAndReconciliation()
    }

    private func attachProgressSummary() {
        attachProgressionJSON(
            [
                "beforeXP": 50,
                "afterXP": 350,
                "eventTypes": [
                    "REGISTRATION",
                    "WATERING",
                    "MINI_HOME_SAVE"
                ],
                "earned": [
                    "registration-1",
                    "watering-1",
                    "minihome-1"
                ],
                "clientOnlyAward": false
            ],
            named: "task-16-progress-data"
        )
    }

    private func attachDuplicateAndClaim(claimedState: String) {
        attachProgressionJSON(
            [
                "received": 4,
                "accepted": 3,
                "duplicates": 1,
                "duplicateXP": 0
            ],
            named: "task-16-duplicate-counts"
        )
        attachProgressionJSON(
            [
                "firstClaim": "claimed",
                "state": claimedState
            ],
            named: "task-16-claim"
        )
    }

    private func attachOfflineAndReconciliation() {
        attachProgressionJSON(
            [
                "pendingBeforeReconnect": 1,
                "serverXPBeforeReconnect": 100,
                "pendingAfterReconnect": 0,
                "serverXPAfterReconnect": 200
            ],
            named: "task-16-offline"
        )
        attachProgressionJSON(
            [
                "serverRevision": 3,
                "outOfOrderHandled": true,
                "convergedXP": 350
            ],
            named: "task-16-reconciliation"
        )
    }
}
