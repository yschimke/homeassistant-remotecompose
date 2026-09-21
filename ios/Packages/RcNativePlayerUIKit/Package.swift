// swift-tools-version:5.9
import PackageDescription

let package = Package(
  name: "RcNativePlayerUIKit",
  platforms: [.iOS(.v13), .macOS(.v12)],
  products: [
    .library(name: "RcPlayerAppleFonts", targets: ["RcPlayerAppleFonts"]),
    .library(name: "RcNativePlayerCore", targets: ["RcNativePlayerCore"]),
    .library(name: "RcNativePlayerUIKit", targets: ["RcNativePlayerUIKit"]),
  ],
  targets: [
    .target(name: "RcPlayerAppleFonts"),
    .target(name: "RcNativePlayerCore"),
    .target(
      name: "RcNativePlayerUIKit", dependencies: ["RcNativePlayerCore", "RcPlayerAppleFonts"]),
    .testTarget(
      name: "RcNativePlayerCoreTests",
      dependencies: ["RcNativePlayerCore"],
      resources: [.copy("Fixtures/demo-tile.rc")]),
  ]
)
