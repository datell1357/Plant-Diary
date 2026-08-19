import XCTest

extension ProgressionUITestCase {
    func attachProgressionJSON(_ value: Any, named name: String) {
        guard let data = try? JSONSerialization.data(
            withJSONObject: value,
            options: [.prettyPrinted, .sortedKeys]
        ) else {
            XCTFail("Progression JSON evidence could not be encoded")
            return
        }
        let attachment = XCTAttachment(
            data: data,
            uniformTypeIdentifier: "public.json"
        )
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    func attachProgressionScreenshot(named name: String) {
        let attachment = XCTAttachment(
            screenshot: XCUIScreen.main.screenshot()
        )
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
