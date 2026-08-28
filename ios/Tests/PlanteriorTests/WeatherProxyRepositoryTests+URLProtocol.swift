import Foundation

final class RequestRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private var storedRequest: URLRequest?

    var request: URLRequest? {
        lock.lock()
        defer { lock.unlock() }
        return storedRequest
    }

    func record(_ request: URLRequest) {
        lock.lock()
        storedRequest = request
        lock.unlock()
    }
}

final class TestWeatherURLProtocol: URLProtocol, @unchecked Sendable {
    typealias Handler = @Sendable (URLRequest) throws -> (Int, Data)

    private nonisolated(unsafe) static var handlers: [String: Handler] = [:]
    private nonisolated(unsafe) static var responseURLs: [String: URL] = [:]
    private static let lock = NSLock()

    static func install(host: String, handler: @escaping Handler) {
        install(host: host, responseURL: nil, handler: handler)
    }

    static func install(
        host: String,
        responseURL: URL?,
        handler: @escaping Handler
    ) {
        lock.lock()
        handlers[host] = handler
        if let responseURL {
            responseURLs[host] = responseURL
        } else {
            responseURLs.removeValue(forKey: host)
        }
        lock.unlock()
    }

    static func remove(host: String) {
        lock.lock()
        handlers.removeValue(forKey: host)
        responseURLs.removeValue(forKey: host)
        lock.unlock()
    }

    override static func canInit(with request: URLRequest) -> Bool {
        request.url?.host != nil
    }

    override static func canonicalRequest(
        for request: URLRequest
    ) -> URLRequest {
        request
    }

    override func startLoading() {
        guard let host = request.url?.host,
              let handler = Self.handler(for: host)
        else {
            client?.urlProtocol(
                self,
                didFailWithError: URLError(.badURL)
            )
            return
        }
        do {
            let (statusCode, data) = try handler(request)
            guard let url = request.url,
                  let response = HTTPURLResponse(
                      url: Self.responseURL(for: host) ?? url,
                      statusCode: statusCode,
                      httpVersion: "HTTP/1.1",
                      headerFields: ["Content-Type": "application/json"]
                  )
            else {
                throw URLError(.badURL)
            }
            client?.urlProtocol(
                self,
                didReceive: response,
                cacheStoragePolicy: .notAllowed
            )
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() {}

    private static func handler(for host: String) -> Handler? {
        lock.lock()
        defer { lock.unlock() }
        return handlers[host]
    }

    private static func responseURL(for host: String) -> URL? {
        lock.lock()
        defer { lock.unlock() }
        return responseURLs[host]
    }
}
