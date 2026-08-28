import XCTest

enum CollectionQAFixtureMode: String {
    case standard
    case figma

    var plantCount: Int {
        switch self {
        case .standard: 2
        case .figma: 5
        }
    }
}

struct CollectionQAFixtureIdentity {
    let accountID: String
    let token: String
    let expectedReceipt: String

    init(testID: String, variant: String, mode: CollectionQAFixtureMode, empty: Bool) {
        let stableTestID = testID
            .replacingOccurrences(of: "[^A-Za-z0-9]+", with: "-", options: .regularExpression)
            .trimmingCharacters(in: CharacterSet(charactersIn: "-"))
        accountID = "collection-\(stableTestID)-\(variant)"
        token = accountID
        expectedReceipt = [
            "account=\(accountID)",
            "token=\(token)",
            "fixture=\(mode.rawValue)",
            "presentation=\(empty ? "empty" : "content")",
            "plants=\(mode.plantCount)"
        ].joined(separator: ";")
    }
}

@MainActor
func configureCollectionQAFixture(
    _ app: XCUIApplication,
    identity: CollectionQAFixtureIdentity,
    mode: CollectionQAFixtureMode
) {
    app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
    app.launchEnvironment["QA_AUTHENTICATED"] = "1"
    app.launchEnvironment["QA_INITIAL_TAB"] = "collection"
    app.launchEnvironment["QA_ACCOUNT_ID"] = identity.accountID
    app.launchEnvironment["QA_COLLECTION_FIXTURE"] = "1"
    app.launchEnvironment["QA_COLLECTION_FIXTURE_TOKEN"] = identity.token
    app.launchEnvironment["QA_RESET_COLLECTION"] = "1"
    if mode == .figma {
        app.launchEnvironment["QA_COLLECTION_FIGMA_FIXTURE"] = "1"
    }
}

@MainActor
func waitForCollectionQAFixture(
    in app: XCUIApplication,
    identity: CollectionQAFixtureIdentity,
    timeout: TimeInterval = 10
) {
    let collection = app.scrollViews["collection.screen"]
    let mounted = XCTNSPredicateExpectation(
        predicate: NSPredicate(format: "value == %@", identity.expectedReceipt),
        object: collection
    )
    XCTAssertEqual(
        XCTWaiter.wait(for: [mounted], timeout: timeout),
        .completed,
        "expected exact mounted fixture receipt: \(identity.expectedReceipt)"
    )
    XCTAssertEqual(collection.value as? String, identity.expectedReceipt)
}
