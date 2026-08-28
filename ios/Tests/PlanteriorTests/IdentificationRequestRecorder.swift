import Foundation

final class IdentificationRequestRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private var storedRequest: URLRequest?
    private var storedBody: Data?

    var request: URLRequest? {
        lock.withLock { storedRequest }
    }

    var body: Data? {
        lock.withLock { storedBody }
    }

    func record(_ request: URLRequest) {
        let body = request.httpBody ?? request.httpBodyStream.flatMap(Self.read)
        lock.withLock {
            storedRequest = request
            storedBody = body
        }
    }

    private static func read(_ stream: InputStream) -> Data? {
        stream.open()
        defer { stream.close() }
        var result = Data()
        var buffer = [UInt8](repeating: 0, count: 4096)
        while stream.hasBytesAvailable {
            let count = stream.read(&buffer, maxLength: buffer.count)
            guard count >= 0 else {
                return nil
            }
            if count == 0 {
                break
            }
            result.append(buffer, count: count)
        }
        return result
    }
}
