import Foundation
import Network
import XCTest

final class DeletionRecoveryArtifactUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    @MainActor
    func testDeletionRecoveryGivenCompletedCleanupThenRetainsExactProtectedSourceArtifact() throws {
        // Given
        let listenerReady = expectation(description: "artifact listener ready")
        let sourceReceived = expectation(description: "exact source received")
        let receiver = try ArtifactReceiver(
            listenerReady: listenerReady,
            sourceReceived: sourceReceived
        )
        receiver.start()
        defer { receiver.stop() }
        wait(for: [listenerReady], timeout: 3)
        let port = try XCTUnwrap(receiver.port)

        let app = launchApplication(artifactPort: port)
        app.buttons["tab.settings"].tap()
        let deletionButton = app.buttons["settings.delete-account"]
        if !deletionButton.isHittable {
            app.swipeUp()
        }
        deletionButton.tap()
        XCTAssertTrue(
            app.scrollViews["account-deletion.screen"]
                .waitForExistence(timeout: 3)
        )
        app.buttons["account-deletion.reauthenticate"].tap()
        app.buttons["account-deletion.confirm"].tap()

        // When
        let complete = app.buttons["account-deletion.qa.complete"]
        XCTAssertTrue(complete.waitForExistence(timeout: 3))
        complete.tap()
        wait(for: [sourceReceived], timeout: 3)
        XCTAssertTrue(
            app.staticTexts["account-deletion.artifact-written"]
                .waitForExistence(timeout: 3)
        )

        // Then
        let sourceData = try receiver.validatedSourceData()
        XCTAssertFalse(sourceData.isEmpty)
        let artifact = try JSONDecoder().decode(
            SourceArtifact.self,
            from: sourceData
        )
        XCTAssertEqual(artifact.ownerID, "qa-account")
        XCTAssertEqual(artifact.status, "completed")
        XCTAssertEqual(Set(artifact.cleanupReceipts), Self.expectedReceipts)

        let attachment = XCTAttachment(
            data: sourceData,
            uniformTypeIdentifier: "public.json"
        )
        attachment.name = "deletion-recovery-source.json"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    @MainActor
    private func launchApplication(artifactPort: UInt16) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = [
            "--qa-deletion-recovery-port", String(artifactPort)
        ]
        app.launchEnvironment = [
            "QA_AUTHENTICATED": "1",
            "QA_ACCOUNT_ID": "qa-account",
            "QA_SKIP_ONBOARDING": "1",
            "QA_DELETION_FIXTURE": "1"
        ]
        app.launch()
        return app
    }

    private struct SourceArtifact: Decodable {
        let ownerID: String
        let status: String
        let cleanupReceipts: [String]
    }

    private static let expectedReceipts = Set([
        "auth", "keychain", "swiftdata", "sync", "userdefaults",
        "notifications", "media", "routes"
    ])
}

private final class ArtifactReceiver: @unchecked Sendable {
    private let listener: NWListener
    private let listenerReady: XCTestExpectation
    private let sourceReceived: XCTestExpectation
    private let queue = DispatchQueue(label: "planterior.qa-evidence.receive")
    private let lock = NSLock()
    private var sourceData = Data()
    private var receiveError: (any Error)?

    var port: UInt16? {
        listener.port?.rawValue
    }

    init(
        listenerReady: XCTestExpectation,
        sourceReceived: XCTestExpectation
    ) throws {
        listener = try NWListener(using: .tcp, on: .any)
        self.listenerReady = listenerReady
        self.sourceReceived = sourceReceived
    }

    func start() {
        listener.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                listenerReady.fulfill()
            case let .failed(error):
                record(error: error)
                listenerReady.fulfill()
            default:
                break
            }
        }
        listener.newConnectionHandler = { [weak self] connection in
            guard let self else { return }
            receive(from: connection)
            connection.start(queue: queue)
        }
        listener.start(queue: queue)
    }

    func stop() {
        listener.cancel()
    }

    func validatedSourceData() throws -> Data {
        try lock.withLock {
            if let receiveError {
                throw receiveError
            }
            return sourceData
        }
    }

    private func receive(from connection: NWConnection) {
        connection.receive(
            minimumIncompleteLength: 1,
            maximumLength: 1_048_576
        ) { [weak self] content, _, isComplete, error in
            guard let self else { return }
            if let content {
                lock.withLock { sourceData.append(content) }
            }
            if let error {
                record(error: error)
                sourceReceived.fulfill()
                return
            }
            if isComplete {
                sourceReceived.fulfill()
                return
            }
            receive(from: connection)
        }
    }

    private func record(error: any Error) {
        lock.withLock { receiveError = error }
    }
}
