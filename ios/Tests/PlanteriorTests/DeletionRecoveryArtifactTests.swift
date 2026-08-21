import Foundation
@testable import Planterior
import XCTest

final class DeletionRecoveryArtifactTests: XCTestCase {
    private var outputDirectory: URL!

    override func setUpWithError() throws {
        outputDirectory = FileManager.default.temporaryDirectory
            .appending(path: UUID().uuidString, directoryHint: .isDirectory)
        try FileManager.default.createDirectory(
            at: outputDirectory,
            withIntermediateDirectories: true
        )
    }

    override func tearDownWithError() throws {
        try FileManager.default.removeItem(at: outputDirectory)
    }

    func testWriteGivenExistingOutputWhenPersistedThenReturnsExactProtectedSource() throws {
        // Given
        let outputURL = outputDirectory.appending(
            path: "deletion-recovery.json",
            directoryHint: .notDirectory
        )
        try Data("stale".utf8).write(to: outputURL)
        let artifact = DeletionRecoveryArtifact(
            ownerID: "qa-account",
            status: "completed",
            cleanupReceipts: ["auth", "media"]
        )

        // When
        let sourceData = try DeletionRecoveryArtifactStore.write(
            artifact,
            to: outputURL
        )

        // Then
        XCTAssertFalse(sourceData.isEmpty)
        XCTAssertEqual(sourceData, try Data(contentsOf: outputURL))
        XCTAssertEqual(
            try JSONDecoder().decode(
                DeletionRecoveryArtifact.self,
                from: sourceData
            ),
            artifact
        )
        XCTAssertTrue(
            DeletionRecoveryArtifactStore.writingOptions.contains(.atomic)
        )
        XCTAssertTrue(
            DeletionRecoveryArtifactStore.writingOptions.contains(
                .completeFileProtection
            )
        )
    }

    func testOutputGivenApplicationSupportThenUsesStableTaskOwnedPath() throws {
        // Given / When
        let outputURL = try DeletionRecoveryArtifactStore.outputURL(
            applicationSupport: outputDirectory
        )

        // Then
        XCTAssertEqual(
            outputURL,
            outputDirectory
                .appending(path: "QAEvidence", directoryHint: .isDirectory)
                .appending(path: "st_01a02461", directoryHint: .isDirectory)
                .appending(path: "deletion-recovery-source.json")
        )
    }

    func testLaunchArgumentsGivenPortFlagWhenParsedThenRejectsMissingOrInvalidValues() {
        // Given / When / Then
        XCTAssertEqual(
            QALaunchArguments(arguments: [
                "Planterior",
                QALaunchArguments.deletionRecoveryPortFlag,
                "46119"
            ]).deletionRecoveryPort,
            46119
        )
        XCTAssertNil(
            QALaunchArguments(arguments: [
                "Planterior",
                QALaunchArguments.deletionRecoveryPortFlag
            ]).deletionRecoveryPort
        )
        XCTAssertNil(
            QALaunchArguments(arguments: [
                "Planterior",
                QALaunchArguments.deletionRecoveryPortFlag,
                "not-a-port"
            ]).deletionRecoveryPort
        )
    }
}
