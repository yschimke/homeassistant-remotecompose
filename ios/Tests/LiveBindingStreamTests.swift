import Foundation
import XCTest

@testable import TerrazzoBindingModels

final class TestSocket: LiveWebSocket, @unchecked Sendable {
  private let lock = NSLock()
  private var messages: [URLSessionWebSocketTask.Message] = []
  private var incoming: [Result<URLSessionWebSocketTask.Message, Error>] = []
  private var receiver: CheckedContinuation<URLSessionWebSocketTask.Message, Error>?
  private var closed = false
  var sent: [URLSessionWebSocketTask.Message] { lock.withLock { messages } }
  var cancelled: Bool { lock.withLock { closed } }
  func resume() {}
  func cancel(with closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
    lock.withLock { closed = true }
    deliver(.failure(URLError(.cancelled)))
  }
  func send(_ message: URLSessionWebSocketTask.Message) async throws {
    try lock.withLock {
      if closed { throw URLError(.cancelled) }
      messages.append(message)
    }
  }
  func receive() async throws -> URLSessionWebSocketTask.Message {
    try await withCheckedThrowingContinuation { continuation in
      let result = lock.withLock { () -> Result<URLSessionWebSocketTask.Message, Error>? in
        if !incoming.isEmpty { return incoming.removeFirst() }
        receiver = continuation
        return nil
      }
      if let result { continuation.resume(with: result) }
    }
  }
  func deliver(_ result: Result<URLSessionWebSocketTask.Message, Error>) {
    let continuation = lock.withLock { () -> CheckedContinuation<URLSessionWebSocketTask.Message, Error>? in
      if let current = receiver { receiver = nil; return current }
      incoming.append(result)
      return nil
    }
    continuation?.resume(with: result)
  }
}

final class TestSockets: @unchecked Sendable {
  private let lock = NSLock()
  private var connections: [(URLRequest, TestSocket)] = []
  var all: [(URLRequest, TestSocket)] { lock.withLock { connections } }
  func make(_ request: URLRequest) -> any LiveWebSocket {
    let socket = TestSocket()
    lock.withLock { connections.append((request, socket)) }
    return socket
  }
}

actor TestBackoff {
  private(set) var delays: [UInt64] = []
  private var pending: CheckedContinuation<Void, Never>?
  func sleep(_ delay: UInt64) async {
    delays.append(delay)
    // Deliberately cancellation-insensitive: obsolete reconnects must check their generation too.
    await withCheckedContinuation { pending = $0 }
  }
  func release() { pending?.resume(); pending = nil }
}

@MainActor
func eventually(
  file: StaticString = #filePath, line: UInt = #line,
  _ condition: () async -> Bool
) async {
  let deadline = Date().addingTimeInterval(3)
  while !(await condition()) {
    if Date() > deadline { XCTFail("Timed out waiting for condition", file: file, line: line); return }
    try? await Task.sleep(nanoseconds: 1_000_000)
  }
}

func json(_ message: URLSessionWebSocketTask.Message) throws -> [String: Any] {
  guard case .string(let text) = message else {
    XCTFail("The server accepts JSON text frames only")
    return [:]
  }
  return try XCTUnwrap(JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any])
}

@MainActor
final class LiveBindingStreamTests: XCTestCase {
  func testTextSubscriptionAndNestedServicePayloadRoundTrip() async throws {
    let sockets = TestSockets()
    let stream = LiveBindingStream(makeSocket: sockets.make)
    var bindings: [String: BindingValue] = [:]
    await stream.connect(url: URL(string: "wss://example.test/stream")!, authorization: "Bearer test", entities: ["light.b", "light.a"]) {
      if case .bindings(let values) = $0 { bindings = values }
    }
    await eventually { sockets.all.first?.1.sent.count == 1 }
    let socket = try XCTUnwrap(sockets.all.first?.1)
    let subscription = try json(socket.sent[0])
    XCTAssertEqual(subscription["type"] as? String, "subscribe")
    XCTAssertEqual(subscription["entities"] as? [String], ["light.a", "light.b"])
    XCTAssertEqual(sockets.all[0].0.value(forHTTPHeaderField: "Authorization"), "Bearer test")
    // Hydration response, as emitted by StreamRoute after a text subscription.
    socket.deliver(.success(.string(#"{"type":"state","bindings":{"light.a.is_on":true}}"#)))
    await eventually { bindings["light.a.is_on"] == .integer(1) }

    let metadata = #"{"type":"callService","domain":"light","service":"turn_on","entityId":"light.a","serviceData":{"rgb_color":[255,0,0],"brightness":128,"enabled":true,"transition":1.5,"name":"Kitchen","nested":{"values":[false,null,{"x":2}]}}}"#
    let call = try XCTUnwrap(ServiceCall(metadata: metadata + "\n--RC-METADATA-V1--\nextra"))
    try await stream.callService(call)
    let command = try json(try XCTUnwrap(socket.sent.last))
    XCTAssertEqual(command["type"] as? String, "call_service")
    XCTAssertEqual(command["domain"] as? String, "light")
    XCTAssertEqual(command["service"] as? String, "turn_on")
    XCTAssertEqual(command["target"] as? [String: String], ["entity_id": "light.a"])
    let original = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(metadata.utf8)) as? [String: Any])
    XCTAssertEqual(command["service_data"] as? NSDictionary, original["serviceData"] as? NSDictionary)
    let values = try XCTUnwrap(command["service_data"] as? [String: Any])
    XCTAssertFalse(values["brightness"] is String)
    XCTAssertFalse(values["enabled"] is String)
    await stream.disconnect()
  }

  func testReconnectResubscribesAndBackoffIsBounded() async throws {
    let sockets = TestSockets()
    let backoff = TestBackoff()
    let stream = LiveBindingStream(makeSocket: sockets.make, sleep: { await backoff.sleep($0) })
    await stream.connect(url: URL(string: "wss://example.test/stream")!, authorization: "Bearer test", entities: ["sensor.a"]) { _ in }
    for attempt in 0..<7 {
      await eventually { sockets.all.count == attempt + 1 && sockets.all[attempt].1.sent.count == 1 }
      XCTAssertEqual(try json(sockets.all[attempt].1.sent[0])["entities"] as? [String], ["sensor.a"])
      XCTAssertEqual(sockets.all[attempt].0.value(forHTTPHeaderField: "Authorization"), "Bearer test")
      sockets.all[attempt].1.deliver(.failure(URLError(.networkConnectionLost)))
      await eventually { await backoff.delays.count == attempt + 1 }
      XCTAssertTrue(sockets.all[attempt].1.cancelled)
      await backoff.release()
    }
    await eventually { sockets.all.count == 8 }
    let delays = await backoff.delays
    XCTAssertEqual(delays, [1, 2, 4, 8, 16, 30, 30].map { UInt64($0) * 1_000_000_000 })
    await stream.disconnect()
  }

  func testFeedSwitchCancelsOldBackoffAndEntityFreeFeedReceivesInvalidations() async throws {
    let sockets = TestSockets()
    let backoff = TestBackoff()
    let stream = LiveBindingStream(makeSocket: sockets.make, sleep: { await backoff.sleep($0) })
    var oldEvents = 0
    var dashboardChanged = false
    var documentsChanged = false
    await stream.connect(url: URL(string: "ws://old.test/stream")!, authorization: nil, entities: ["light.old"]) { _ in oldEvents += 1 }
    await eventually { sockets.all.first?.1.sent.count == 1 }
    sockets.all[0].1.deliver(.failure(URLError(.networkConnectionLost)))
    await eventually { await backoff.delays.count == 1 }
    let eventsBeforeSwitch = oldEvents
    await stream.connect(url: URL(string: "wss://new.test/stream")!, authorization: nil, entities: []) {
      if case .dashboardChanged = $0 { dashboardChanged = true }
      if case .documentsChanged(let ids) = $0 { documentsChanged = ids == ["static"] }
    }
    await eventually { sockets.all.count == 2 && sockets.all[1].1.sent.count == 1 }
    await backoff.release()
    let socket = sockets.all[1].1
    XCTAssertEqual(try json(socket.sent[0])["entities"] as? [String], [])
    socket.deliver(.success(.string(#"{"type":"lovelace_updated"}"#)))
    socket.deliver(.success(.string(#"{"type":"documents_changed","card_ids":["static"]}"#)))
    await eventually { dashboardChanged && documentsChanged }
    XCTAssertEqual(oldEvents, eventsBeforeSwitch)
    XCTAssertEqual(sockets.all.count, 2)
    XCTAssertEqual(sockets.all[1].0.url?.host, "new.test")
    await stream.disconnect()
  }

  func testExplicitDisconnectCancelsPendingReconnect() async {
    let sockets = TestSockets()
    let backoff = TestBackoff()
    let stream = LiveBindingStream(makeSocket: sockets.make, sleep: { await backoff.sleep($0) })
    await stream.connect(url: URL(string: "ws://example.test/stream")!, authorization: nil, entities: []) { _ in }
    await eventually { sockets.all.first?.1.sent.count == 1 }
    sockets.all[0].1.deliver(.failure(URLError(.networkConnectionLost)))
    await eventually { await backoff.delays.count == 1 }
    await stream.disconnect()
    await backoff.release()
    // Drain the old task, then verify no replacement socket can be created.
    for _ in 0..<20 { await Task.yield() }
    XCTAssertEqual(sockets.all.count, 1)
  }

  func testToggleAndEmptyServiceDataRemainSupported() throws {
    let call = try XCTUnwrap(ServiceCall(metadata: #"{"type":"toggle","entity_id":"switch.test"}"#))
    XCTAssertEqual(call.domain, "homeassistant")
    XCTAssertEqual(call.service, "toggle")
    XCTAssertEqual(call.entityID, "switch.test")
    XCTAssertEqual(try JSONSerialization.jsonObject(with: call.serviceData) as? NSDictionary, [:])
    XCTAssertNil(ServiceCall(metadata: #"{"type":"url","url":"https://example.test"}"#))
  }

  func testAppTransportSecurityFailureExplainsLocalOnlyHTTP() {
    let message = connectionFailureMessage(URLError(.appTransportSecurityRequiresSecureConnection))
    XCTAssertTrue(message.contains("only allowed for local network addresses"))
    XCTAssertTrue(message.contains("https://"))
    let other = URLError(.timedOut)
    XCTAssertEqual(connectionFailureMessage(other), other.localizedDescription)
  }
}
