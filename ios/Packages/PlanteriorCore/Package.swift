// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "PlanteriorCore",
    platforms: [.macOS(.v14), .iOS(.v17)],
    products: [
        .library(name: "PlanteriorDomain", targets: ["PlanteriorDomain"]),
        .library(name: "PlanteriorData", targets: ["PlanteriorData"]),
        .library(name: "PlanteriorDesignSystem", targets: ["PlanteriorDesignSystem"]),
        .library(name: "PlanteriorTestingSupport", targets: ["PlanteriorTestingSupport"])
    ],
    targets: [
        .target(name: "PlanteriorDomain"),
        .target(name: "PlanteriorData", dependencies: ["PlanteriorDomain"]),
        .target(name: "PlanteriorDesignSystem"),
        .target(name: "PlanteriorTestingSupport", dependencies: ["PlanteriorDomain"]),
        .testTarget(name: "PlanteriorDomainTests", dependencies: ["PlanteriorDomain"]),
        .testTarget(name: "PlanteriorDesignSystemTests", dependencies: ["PlanteriorDesignSystem"]),
        .testTarget(
            name: "PlanteriorDataTests",
            dependencies: ["PlanteriorData"],
            resources: [.copy("Fixtures")]
        )
    ]
)
