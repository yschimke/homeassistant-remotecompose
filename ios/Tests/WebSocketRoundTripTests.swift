import Foundation
import XCTest

@testable import TerrazzoBindingModels

@MainActor
final class WebSocketRoundTripTests: XCTestCase {
  func testURLSessionTextFramesHydrateAndSendStructuredServiceData() async throws {
    let fixture = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
      .appendingPathComponent("Fixtures/stream-server.py")
    let server = Process()
    let output = Pipe()
    server.executableURL = URL(fileURLWithPath: "/usr/bin/env")
    server.arguments = ["python3", fixture.path]
    server.standardOutput = output
    try server.run()
    defer { if server.isRunning { server.terminate() } }
    var portData = Data()
    while let byte = try output.fileHandleForReading.read(upToCount: 1), !byte.isEmpty {
      if byte == Data([10]) { break }
      portData.append(byte)
    }
    let port = try XCTUnwrap(Int(String(decoding: portData, as: UTF8.self)))
    let stream = LiveBindingStream()
    var hydrated = false
    var echo: String?
    await stream.connect(url: URL(string: "ws://127.0.0.1:\(port)/v1/stream")!, authorization: nil, entities: ["light.test"]) {
      if case .bindings(let values) = $0 {
        if values["light.test.is_on"] == .integer(1) { hydrated = true }
        if case .string(let value) = values["echo"] { echo = value }
      }
    }
    await eventually { hydrated }
    let call = try XCTUnwrap(ServiceCall(metadata: #"{"type":"callService","domain":"light","service":"turn_on","serviceData":{"rgb_color":[255,0,0],"enabled":true,"nested":{"value":null},"brightness":128}}"#))
    try await stream.callService(call)
    await eventually { echo != nil }
    let echoed = try XCTUnwrap(echo)
    XCTAssertEqual(
      try JSONSerialization.jsonObject(with: Data(echoed.utf8)) as? NSDictionary,
      try JSONSerialization.jsonObject(with: call.serviceData) as? NSDictionary)
    await stream.disconnect()
  }
}
