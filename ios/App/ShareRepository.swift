import Combine
import CryptoKit
import Foundation
import PlanteriorData
import PlanteriorDomain
import Security

struct ProvisionalShareLink: Equatable {
    let id: ShareLinkID
    let token: ShareToken
    let tokenHash: String
    let snapshotDigest: String
    let snapshot: MiniHomeShareSnapshot
    let createdAt: Instant
    let expiresAt: Instant
    let revokedAt: Instant?

    var url: URL? {
        URL(
            string: "https://share.planterior.invalid/s/\(token.rawValue)"
        )
    }
}

enum ShareRepositoryOutcome: Equatable {
    case created(ProvisionalShareLink)
    case revoked(ProvisionalShareLink)
    case alreadyRevoked(ProvisionalShareLink)
    case offline
    case unavailable
    case notFound
}

@MainActor
final class ShareRepository: ObservableObject {
    @Published private(set) var links: [ProvisionalShareLink] = []
    let allowsProvisionalLinks: Bool
    let now: Instant?
    let randomBytes: () throws -> Data

    init(
        allowsProvisionalLinks: Bool,
        now: Instant?,
        randomBytes: @escaping () throws -> Data = {
            var bytes = [UInt8](repeating: 0, count: 24)
            let status = SecRandomCopyBytes(
                kSecRandomDefault,
                bytes.count,
                &bytes
            )
            guard status == errSecSuccess else {
                throw ShareSnapshotError.invalidRandomBytes
            }
            return Data(bytes)
        }
    ) {
        self.allowsProvisionalLinks = allowsProvisionalLinks
        self.now = now
        self.randomBytes = randomBytes
    }

    func createLink(
        snapshot: MiniHomeShareSnapshot,
        digest: String,
        online: Bool
    ) -> ShareRepositoryOutcome {
        guard allowsProvisionalLinks else { return .unavailable }
        guard online else { return .offline }
        guard let now,
              let token = try? ShareSnapshotPolicy.token(
                  randomBytes: randomBytes()
              ),
              let expiresAt = try? ShareSnapshotPolicy.expiresAt(
                  createdAt: now
              ),
              let id = try? ShareLinkID.parse(
                  "share-link-\(links.count + 1)"
              )
        else {
            return .unavailable
        }
        let tokenHash = SHA256.hash(data: Data(token.rawValue.utf8))
            .map { String(format: "%02x", $0) }
            .joined()
        let link = ProvisionalShareLink(
            id: id,
            token: token,
            tokenHash: tokenHash,
            snapshotDigest: digest,
            snapshot: snapshot,
            createdAt: now,
            expiresAt: expiresAt,
            revokedAt: nil
        )
        links.append(link)
        return .created(link)
    }

    func revoke(_ id: ShareLinkID) -> ShareRepositoryOutcome {
        guard allowsProvisionalLinks else { return .unavailable }
        guard let index = links.firstIndex(where: { $0.id == id }),
              let now
        else {
            return .notFound
        }
        let current = links[index]
        if current.revokedAt != nil {
            return .alreadyRevoked(current)
        }
        let revoked = ProvisionalShareLink(
            id: current.id,
            token: current.token,
            tokenHash: current.tokenHash,
            snapshotDigest: current.snapshotDigest,
            snapshot: current.snapshot,
            createdAt: current.createdAt,
            expiresAt: current.expiresAt,
            revokedAt: now
        )
        links[index] = revoked
        return .revoked(revoked)
    }

    func resolve(_ token: ShareToken, now: Instant) -> ProvisionalShareLink? {
        guard allowsProvisionalLinks,
              let link = links.first(where: { $0.token == token }),
              ShareSnapshotPolicy.isReadable(
                  expiresAt: link.expiresAt,
                  revokedAt: link.revokedAt,
                  now: now
              )
        else {
            return nil
        }
        return link
    }
}
