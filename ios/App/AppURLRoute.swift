import Foundation

enum AppURLRoute {
    static func parse(_ url: URL) -> IncomingAppRoute {
        guard url.scheme == "planterior",
              url.host == "plant",
              url.pathComponents.count == 2
        else {
            return .invalid
        }
        return .plant(rawTarget: url.lastPathComponent)
    }
}
