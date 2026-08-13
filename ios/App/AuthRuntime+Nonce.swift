import CryptoKit
import Foundation
import Security

extension AuthRuntime {
    static func randomNonce(length: Int = 32) -> String {
        let alphabet = Array(
            "0123456789ABCDEFGHIJKLMNOPQRSTUVXYZabcdefghijklmnopqrstuvwxyz-._"
        )
        var result = ""
        while result.count < length {
            var random: UInt8 = 0
            guard SecRandomCopyBytes(
                kSecRandomDefault,
                1,
                &random
            ) == errSecSuccess else {
                continue
            }
            if Int(random) < alphabet.count {
                result.append(alphabet[Int(random)])
            }
        }
        return result
    }

    static func sha256(_ value: String) -> String {
        SHA256.hash(data: Data(value.utf8))
            .map { String(format: "%02x", $0) }
            .joined()
    }
}
