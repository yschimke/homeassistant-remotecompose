import Foundation
import XCTest

@testable import TerrazzoBindingModels

final class FeedProtocol: URLProtocol, @unchecked Sendable {
  private static let lock = NSLock()
  private static var requests: [URL] = []
  static func reset() { lock.withLock { requests = [] } }
  static func count(_ path: String) -> Int {
    lock.withLock { requests.filter { $0.path == path }.count }
  }
  override class func canInit(with request: URLRequest) -> Bool { true }
  override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
  override func startLoading() {
    let url = request.url!
    Self.lock.withLock { Self.requests.append(url) }
    let data: Data
    if url.path.hasSuffix("manifest.json") {
      let entities = url.host == "old.test" ? ["light.old"] : []
      let feed = DashboardFeed(version: 1, dashboards: [
        Dashboard(id: url.host!, title: "Test", sections: [
          DashboardSection(id: "section", cards: [
            DashboardCard(id: "static", type: "markdown", documentURL: "/card.rc", entities: entities, updateMode: .document)
          ])
        ])
      ])
      data = try! JSONEncoder().encode(feed)
    } else { data = Data("test document".utf8) }
    client?.urlProtocol(self, didReceive: HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: nil)!, cacheStoragePolicy: .notAllowed)
    client?.urlProtocol(self, didLoad: data)
    client?.urlProtocolDidFinishLoading(self)
  }
  override func stopLoading() {}
}

@MainActor
final class DashboardStoreTests: XCTestCase {
  private func makeStore(_ stream: LiveBindingStream) -> DashboardStore {
    FeedProtocol.reset()
    let configuration = URLSessionConfiguration.ephemeral
    configuration.protocolClasses = [FeedProtocol.self]
    let session = URLSession(configuration: configuration)
    return DashboardStore(liveStream: stream, restoreOnLaunch: false) {
      RemoteComposeService(configuration: $0, session: session)
    }
  }

  func testSwitchToEntityFreeFeedDisconnectsOldSocketAndProcessesInvalidations() async throws {
    let sockets = TestSockets()
    let stream = LiveBindingStream(makeSocket: sockets.make)
    let store = makeStore(stream)
    store.connect(baseURL: "https://old.test", token: "")
    await eventually { sockets.all.first?.1.sent.count == 1 }
    let old = sockets.all[0].1
    XCTAssertEqual(try json(old.sent[0])["entities"] as? [String], ["light.old"])
    store.connect(baseURL: "https://new.test", token: "")
    await eventually { sockets.all.count == 2 && sockets.all[1].1.sent.count == 1 }
    XCTAssertTrue(old.cancelled)
    XCTAssertEqual(sockets.all[1].0.url?.host, "new.test")
    XCTAssertEqual(try json(sockets.all[1].1.sent[0])["entities"] as? [String], [])
    XCTAssertEqual(store.feed?.dashboards.first?.id, "new.test")
    store.dispatch(metadata: #"{"type":"callService","domain":"light","service":"turn_on","serviceData":{"rgb_color":[255,0,0],"enabled":true,"nested":{"value":null},"brightness":128}}"#)
    await eventually { sockets.all[1].1.sent.count == 2 }
    let command = try json(sockets.all[1].1.sent[1])
    let values = try XCTUnwrap(command["service_data"] as? [String: Any])
    XCTAssertEqual(values["rgb_color"] as? [Int], [255, 0, 0])
    XCTAssertEqual(values["brightness"] as? Int, 128)
    XCTAssertEqual(values["enabled"] as? Bool, true)
    XCTAssertNotNil(values["nested"] as? [String: Any])
    XCTAssertFalse(values["brightness"] is String)
    XCTAssertFalse(values["enabled"] is String)
    let documents = FeedProtocol.count("/card.rc")
    sockets.all[1].1.deliver(.success(.string(#"{"type":"documents_changed","card_ids":["static"]}"#)))
    await eventually { FeedProtocol.count("/card.rc") > documents }
    let manifests = FeedProtocol.count("/v1/apple/manifest.json")
    sockets.all[1].1.deliver(.success(.string(#"{"type":"lovelace_updated"}"#)))
    await eventually { FeedProtocol.count("/v1/apple/manifest.json") > manifests && sockets.all.count == 3 }
    store.signOut()
    await stream.disconnect()
  }

  func testDemoAndSignOutCancelReconnectWithoutChangingReadyData() async {
    for demo in [true, false] {
      let sockets = TestSockets()
      let backoff = TestBackoff()
      let stream = LiveBindingStream(makeSocket: sockets.make, sleep: { await backoff.sleep($0) })
      let store = makeStore(stream)
      store.connect(baseURL: "https://old.test", token: "")
      await eventually { sockets.all.first?.1.sent.count == 1 }
      sockets.all[0].1.deliver(.failure(URLError(.networkConnectionLost)))
      await eventually { await backoff.delays.count == 1 }
      if demo { store.startDemo() } else { store.signOut() }
      await stream.disconnect()
      await backoff.release()
      for _ in 0..<20 { await Task.yield() }
      XCTAssertEqual(sockets.all.count, 1)
      XCTAssertEqual(store.isDemo, demo)
      XCTAssertEqual(store.phase, demo ? .ready : .signedOut)
      XCTAssertEqual(store.feed, demo ? DemoContent.feed : nil)
      store.signOut()
    }
  }
}
