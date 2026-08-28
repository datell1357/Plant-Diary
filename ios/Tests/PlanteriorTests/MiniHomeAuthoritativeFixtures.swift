import Foundation
@testable import Planterior
import PlanteriorDomain

extension MiniHomeAuthoritativeServiceTests {
    nonisolated static let accountID = "owner-fixture"

    nonisolated static func fixtureRoot() throws -> [String: Any] {
        var directory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        while directory.path != "/" {
            let fixture = directory.appendingPathComponent(
                "docs/ios/minihome-contract-v1.fixture.json"
            )
            if FileManager.default.fileExists(atPath: fixture.path) {
                let data = try Data(contentsOf: fixture)
                guard let root = try JSONSerialization
                    .jsonObject(with: data) as? [String: Any]
                else {
                    throw MiniHomeAuthoritativeError.malformedResponse
                }
                return root
            }
            directory.deleteLastPathComponent()
        }
        throw MiniHomeAuthoritativeError.malformedResponse
    }

    nonisolated static func fixtureData(_ key: String) throws -> Data {
        guard let value = try fixtureRoot()[key] else {
            throw MiniHomeAuthoritativeError.malformedResponse
        }
        return try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys])
    }

    nonisolated static func snapshot() throws -> MiniHomeVerifiedSnapshot {
        try MiniHomeResponseDecoder.snapshot(
            data: fixtureData("snapshot"),
            expectedAccountID: accountID
        )
    }

    nonisolated static func draft() throws -> MiniHome {
        try snapshot().home
    }

    nonisolated static func mutatedData(
        key: String,
        mutate: (inout [String: Any]) -> Void
    ) throws -> Data {
        guard var object = try fixtureRoot()[key] as? [String: Any] else {
            throw MiniHomeAuthoritativeError.malformedResponse
        }
        mutate(&object)
        return try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
    }
}

@MainActor
final class MiniHomeCallableRecorder: MiniHomeCallableClient {
    struct Call {
        let name: String
        let payload: [String: Any]
    }

    var responses: [String: Data]
    var failure: MiniHomeAuthoritativeError?
    private(set) var calls: [Call] = []

    init(responses: [String: Data] = [:]) {
        self.responses = responses
    }

    func call(name: String, payload: sending [String: Any]) async throws -> Data {
        calls.append(Call(name: name, payload: payload))
        if let failure {
            throw failure
        }
        guard let response = responses[name] else {
            throw MiniHomeAuthoritativeError.transport
        }
        return response
    }
}
