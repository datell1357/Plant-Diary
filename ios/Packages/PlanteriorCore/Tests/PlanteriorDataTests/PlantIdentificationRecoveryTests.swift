import Foundation
@testable import PlanteriorData
import PlanteriorDomain
import Testing

struct PlantIdentificationRecoveryTests {
    @Test(
        arguments: [
            IdentificationFailure.timeout,
            .rateLimited,
            .serverFailure
        ]
    )
    func mapsRecoverableProviderFailures(_ failure: IdentificationFailure) async {
        let coordinator = PlantIdentificationCoordinator(
            service: RecoveryServiceFake(failure: failure)
        )
        await coordinator.submit(Data("image".utf8))
        #expect(await coordinator.state == .failed(failure))
    }

    @Test
    func duplicateCancelPreservesZeroRows() async throws {
        let coordinator = try PlantRegistrationCoordinator(
            existingPlantIDs: ["duplicate"]
        )
        _ = try await coordinator.register(
            plantID: "duplicate",
            displayName: "중복"
        )
        #expect(await coordinator.resolveDuplicate(.cancel) == .cancelled)
        #expect(await coordinator.personalPlantCount == 0)
    }

    @Test
    func identificationDraftRestoresAfterStoreRecreation() async throws {
        let suite = "PlantIdentificationRecoveryTests.restore"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defaults.removePersistentDomain(forName: suite)
        let photo = NormalizedPhoto(
            data: Data("image".utf8),
            pixelWidth: 256,
            pixelHeight: 256,
            contentType: "image/jpeg"
        )
        await IdentificationDraftStore(suiteName: suite).transfer(photo)
        let restored = await IdentificationDraftStore(suiteName: suite).load()
        #expect(restored == photo)
        defaults.removePersistentDomain(forName: suite)
    }
}

private struct RecoveryServiceFake: PlantIdentificationService {
    let failure: IdentificationFailure

    func identify(
        requestID: IdentificationRequestID,
        idempotencyKey: OperationID,
        image: Data
    ) -> AsyncStream<IdentificationState> {
        AsyncStream(IdentificationState.self) { continuation in
            continuation.yield(.pending)
            continuation.yield(.failed(failure))
            continuation.finish()
        }
    }
}
