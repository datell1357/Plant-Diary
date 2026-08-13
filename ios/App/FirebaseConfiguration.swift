import Foundation

enum FirebaseConfiguration {
    static var isAvailable: Bool {
        Bundle.main.path(
            forResource: "GoogleService-Info",
            ofType: "plist"
        ) != nil
    }
}
