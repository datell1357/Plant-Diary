#if DEBUG
    import Foundation
    import Network

    struct DeletionRecoveryArtifact: Codable, Equatable {
        let ownerID: String
        let status: String
        let cleanupReceipts: [String]
    }

    enum DeletionRecoveryArtifactStore {
        static let writingOptions: Data.WritingOptions = [
            .atomic, .completeFileProtection
        ]

        static func outputURL(applicationSupport: URL? = nil) throws -> URL {
            let root = try applicationSupport ?? FileManager.default.url(
                for: .applicationSupportDirectory,
                in: .userDomainMask,
                appropriateFor: nil,
                create: true
            )
            return root
                .appending(path: "QAEvidence", directoryHint: .isDirectory)
                .appending(path: "st_01a02461", directoryHint: .isDirectory)
                .appending(
                    path: "deletion-recovery-source.json",
                    directoryHint: .notDirectory
                )
        }

        static func write(_ artifact: DeletionRecoveryArtifact, to outputURL: URL) throws -> Data {
            try FileManager.default.createDirectory(
                at: outputURL.deletingLastPathComponent(),
                withIntermediateDirectories: true,
                attributes: [.protectionKey: FileProtectionType.complete]
            )
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.sortedKeys]
            let data = try encoder.encode(artifact)
            try data.write(to: outputURL, options: writingOptions)
            return try Data(contentsOf: outputURL)
        }
    }

    struct QALaunchArguments {
        static let deletionRecoveryPortFlag = "--qa-deletion-recovery-port"

        private let arguments: [String]

        init(arguments: [String] = ProcessInfo.processInfo.arguments) {
            self.arguments = arguments
        }

        var deletionRecoveryPort: UInt16? {
            guard let flagIndex = arguments.firstIndex(
                of: Self.deletionRecoveryPortFlag
            ) else {
                return nil
            }
            let valueIndex = arguments.index(after: flagIndex)
            guard arguments.indices.contains(valueIndex),
                  let port = UInt16(arguments[valueIndex]),
                  port > 0
            else {
                return nil
            }
            return port
        }
    }

    enum DeletionRecoveryArtifactBridge {
        static func send(sourceData: Data, to port: UInt16) async throws {
            guard let endpointPort = NWEndpoint.Port(rawValue: port) else {
                throw CocoaError(.fileWriteInvalidFileName)
            }
            let connection = NWConnection(
                host: NWEndpoint.Host("127.0.0.1"),
                port: endpointPort,
                using: .tcp
            )
            try await withCheckedThrowingContinuation { continuation in
                let completion = BridgeCompletion(continuation: continuation)
                connection.stateUpdateHandler = { state in
                    switch state {
                    case .ready:
                        connection.send(
                            content: sourceData,
                            contentContext: .finalMessage,
                            isComplete: true,
                            completion: .contentProcessed { error in
                                connection.cancel()
                                completion.resume(with: error)
                            }
                        )
                    case let .failed(error):
                        connection.cancel()
                        completion.resume(throwing: error)
                    case .cancelled:
                        completion.resume(throwing: CancellationError())
                    default:
                        break
                    }
                }
                connection.start(
                    queue: DispatchQueue(label: "planterior.qa-evidence.send")
                )
            }
        }

        private final class BridgeCompletion: @unchecked Sendable {
            private let lock = NSLock()
            private var continuation: CheckedContinuation<Void, any Error>?

            init(continuation: CheckedContinuation<Void, any Error>) {
                self.continuation = continuation
            }

            func resume(with error: NWError?) {
                if let error {
                    resume(throwing: error)
                } else {
                    resume(returning: ())
                }
            }

            func resume(returning value: Void) {
                lock.withLock {
                    continuation?.resume(returning: value)
                    continuation = nil
                }
            }

            func resume(throwing error: any Error) {
                lock.withLock {
                    continuation?.resume(throwing: error)
                    continuation = nil
                }
            }
        }
    }
#endif
