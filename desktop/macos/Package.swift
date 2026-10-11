// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "DictAI",
    platforms: [.macOS(.v14)],
    products: [.library(name: "DictAICore", targets: ["DictAICore"]), .executable(name: "DictAI", targets: ["DictAI"])],
    targets: [
        .target(name: "DictAICore"),
        .executableTarget(name: "DictAI", dependencies: ["DictAICore"]),
        .testTarget(name: "DictAICoreTests", dependencies: ["DictAICore"]),
        .testTarget(name: "DictAIAppTests", dependencies: ["DictAI", "DictAICore"])
    ]
)
