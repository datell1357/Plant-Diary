import SwiftUI
import UIKit

enum ShareSheetResult: Equatable {
    case completed
    case cancelled
}

struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    let completion: (ShareSheetResult) -> Void

    func makeUIViewController(
        context: Context
    ) -> UIActivityViewController {
        let controller = UIActivityViewController(
            activityItems: items,
            applicationActivities: nil
        )
        controller.completionWithItemsHandler = { _, completed, _, _ in
            completion(completed ? .completed : .cancelled)
        }
        return controller
    }

    func updateUIViewController(
        _ uiViewController: UIActivityViewController,
        context: Context
    ) {
        _ = uiViewController
        _ = context
    }
}
