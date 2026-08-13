import UIKit

enum PhotoQAFixture {
    static var data: Data {
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: 300, height: 300))
        return renderer.jpegData(withCompressionQuality: 0.9) { context in
            UIColor.systemGreen.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 300, height: 300))
        }
    }
}
