import Foundation

enum AppURLRoute {
    static func parse(_ url: URL) -> IncomingAppRoute {
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              components.scheme == "planterior",
              components.host == "plant",
              url.user == nil,
              url.password == nil,
              url.port == nil,
              components.query == nil,
              components.fragment == nil,
              url.pathComponents.count == 2,
              components.percentEncodedPath.first == "/",
              !components.percentEncodedPath.dropFirst().contains("/"),
              !url.lastPathComponent.isEmpty,
              PlantRouteTarget(rawValue: url.lastPathComponent) != nil
        else {
            return .invalid
        }
        return .plant(rawTarget: url.lastPathComponent)
    }
}
