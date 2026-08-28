import Foundation
import Testing

struct PrivacyManifestContractTests {
    @Test
    func declaresCollectedPhotoAndPreciseLocationPurpose() throws {
        let manifestURL = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("Config/PrivacyInfo.xcprivacy")
        let data = try Data(contentsOf: manifestURL)
        let plist = try PropertyListSerialization.propertyList(
            from: data,
            options: [],
            format: nil
        )
        let root = try #require(plist as? [String: Any])
        let collected = try #require(
            root["NSPrivacyCollectedDataTypes"] as? [[String: Any]]
        )
        let types = Set(
            collected.compactMap {
                $0["NSPrivacyCollectedDataType"] as? String
            }
        )
        #expect(types.contains("NSPrivacyCollectedDataTypePhotosOrVideos"))
        #expect(types.contains("NSPrivacyCollectedDataTypePreciseLocation"))
        #expect(!types.contains("NSPrivacyCollectedDataTypeHealth"))
        #expect(!types.contains("NSPrivacyCollectedDataTypeContacts"))

        for entry in collected where types.contains(
            entry["NSPrivacyCollectedDataType"] as? String ?? ""
        ) {
            let purposes = entry["NSPrivacyCollectedDataTypePurposes"] as? [String]
            #expect(
                purposes == ["NSPrivacyCollectedDataTypePurposeAppFunctionality"]
            )
        }

        let accessed = try #require(
            root["NSPrivacyAccessedAPITypes"] as? [[String: Any]]
        )
        let reasons = accessed.first {
            $0["NSPrivacyAccessedAPIType"] as? String
                == "NSPrivacyAccessedAPICategoryUserDefaults"
        }?["NSPrivacyAccessedAPITypeReasons"] as? [String]
        #expect(reasons == ["CA92.1"])
    }
}
