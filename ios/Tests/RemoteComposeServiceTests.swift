import Foundation
import XCTest

@testable import TerrazzoBindingModels

final class RemoteComposeServiceTests: XCTestCase {
  func testSecureAndExistingWebSocketURLs() async throws {
    for (base, path, expected) in [
      ("https://gateway.test", nil, "wss://gateway.test/v1/stream"),
      ("http://gateway.test", "/custom?x=1", "ws://gateway.test/custom?x=1"),
      ("https://gateway.test", "wss://secure.test:8443/live?x=1", "wss://secure.test:8443/live?x=1"),
      ("https://gateway.test", "ws://local.test/live", "ws://local.test/live"),
      ("http://gateway.test", "https://other.test/live", "wss://other.test/live"),
    ] as [(String, String?, String)] {
      let configuration = try XCTUnwrap(ServerConfiguration(baseURLText: base, token: ""))
      let service = RemoteComposeService(configuration: configuration)
      let url = try await service.streamURL(for: DashboardFeed(version: 1, dashboards: [], streamURL: path))
      XCTAssertEqual(url.absoluteString, expected)
    }
  }

  func testUnsupportedStreamSchemeIsRejected() async throws {
    let configuration = try XCTUnwrap(ServerConfiguration(baseURLText: "https://gateway.test", token: ""))
    do {
      _ = try await RemoteComposeService(configuration: configuration).streamURL(
        for: DashboardFeed(version: 1, dashboards: [], streamURL: "file:///tmp/socket"))
      XCTFail("Unsupported stream scheme should fail")
    } catch { XCTAssertEqual((error as? URLError)?.code, .badURL) }
  }
}
