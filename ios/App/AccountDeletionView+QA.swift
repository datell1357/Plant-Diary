#if DEBUG
    import PlanteriorDesignSystem
    import SwiftUI

    extension AccountDeletionView {
        var qaButtons: some View {
            HStack {
                Button("부분 실패") {
                    coordinator.simulatePartialFailure()
                }
                .frame(minHeight: PlanteriorControl.minimumTarget)
                .accessibilityIdentifier("account-deletion.qa.partial")
                Button("완료") {
                    Task { await coordinator.simulateCompletion() }
                }
                .frame(minHeight: PlanteriorControl.minimumTarget)
                .accessibilityIdentifier("account-deletion.qa.complete")
            }
            .frame(minHeight: PlanteriorControl.minimumTarget)
        }

        func writeRecoveryArtifact() async {
            guard let port = QALaunchArguments().deletionRecoveryPort,
                  let workflow = coordinator.workflow
            else {
                return
            }
            let artifact = DeletionRecoveryArtifact(
                ownerID: workflow.ownerID.rawValue,
                status: "completed",
                cleanupReceipts: coordinator.cleanupReceipts.sorted()
            )
            do {
                let outputURL = try DeletionRecoveryArtifactStore.outputURL()
                let sourceData = try DeletionRecoveryArtifactStore.write(
                    artifact,
                    to: outputURL
                )
                try await DeletionRecoveryArtifactBridge.send(
                    sourceData: sourceData,
                    to: port
                )
                recoveryArtifactStatus = "written"
            } catch {
                recoveryArtifactStatus = "write-failed"
            }
        }
    }
#endif
