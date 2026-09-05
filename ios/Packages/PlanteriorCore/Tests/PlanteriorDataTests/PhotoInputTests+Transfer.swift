import Foundation
@testable import PlanteriorData
import Testing

extension PhotoInputTests {
    @Test
    func cancellationAndDeclinedAcknowledgementPerformNoTransfer() async throws {
        let transfer = PhotoTransferFake()
        let coordinator = PhotoConsentCoordinator(transfer: transfer)
        let photo = try PhotoImagePipeline().normalize(
            fixture(width: 300, height: 300)
        )

        await coordinator.review([photo])
        await coordinator.cancelSelection()
        #expect(await coordinator.hasDraft() == false)
        await coordinator.review([photo])
        await coordinator.declineAcknowledgement()
        #expect(await coordinator.hasDraft())

        #expect(await transfer.requestCount == 0)
    }

    @Test
    func acknowledgementTransfersReviewedPhotoBatchExactlyOnce() async throws {
        let transfer = PhotoTransferFake()
        let coordinator = PhotoConsentCoordinator(transfer: transfer)
        let photo = try PhotoImagePipeline().normalize(
            fixture(width: 300, height: 300)
        )

        await coordinator.review([photo, photo, photo])
        await coordinator.acknowledgeAndTransfer()

        #expect(await transfer.requestCount == 1)
        #expect(await transfer.receivedCounts == [3])
    }

    @Test
    func repeatedAcknowledgementTransfersOneReviewedDraftAtMostOnce() async throws {
        let transfer = PhotoTransferFake()
        let coordinator = PhotoConsentCoordinator(transfer: transfer)
        let firstPhoto = try PhotoImagePipeline().normalize(
            fixture(width: 300, height: 300)
        )
        let secondPhoto = try PhotoImagePipeline().normalize(
            fixture(width: 320, height: 320)
        )

        await coordinator.review([firstPhoto])
        await coordinator.acknowledgeAndTransfer()
        await coordinator.acknowledgeAndTransfer()
        #expect(await transfer.requestCount == 1)

        await coordinator.review([firstPhoto, secondPhoto])
        await coordinator.acknowledgeAndTransfer()
        #expect(await transfer.requestCount == 2)
    }
}

private actor PhotoTransferFake: PhotoTransferRequesting {
    private(set) var requestCount = 0
    private(set) var receivedCounts: [Int] = []

    func transfer(_ photos: [NormalizedPhoto]) async {
        requestCount += 1
        receivedCounts.append(photos.count)
    }
}
