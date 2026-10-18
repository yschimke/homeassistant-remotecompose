import Foundation

// A transport seam lets tests terminate a connection or suspend a receive/backoff deterministically.
protocol LiveWebSocket: Sendable {
  func resume()
  func cancel(with closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?)
  func send(_ message: URLSessionWebSocketTask.Message) async throws
  func receive() async throws -> URLSessionWebSocketTask.Message
}

extension URLSessionWebSocketTask: LiveWebSocket {}

actor LiveBindingStream {
  enum StreamEvent: Sendable {
    case bindings([String: BindingValue])
    case dashboardChanged
    case documentsChanged(Set<String>)
    case disconnected(String)
  }

  private let makeSocket: @Sendable (URLRequest) -> any LiveWebSocket
  private let sleep: @Sendable (UInt64) async throws -> Void
  private var task: (any LiveWebSocket)?
  private var receiveTask: Task<Void, Never>?
  private var generation = 0
  private var nextCommandID = 2

  init(
    makeSocket: @escaping @Sendable (URLRequest) -> any LiveWebSocket = {
      URLSession.shared.webSocketTask(with: $0)
    },
    sleep: @escaping @Sendable (UInt64) async throws -> Void = {
      try await Task.sleep(nanoseconds: $0)
    }
  ) {
    self.makeSocket = makeSocket
    self.sleep = sleep
  }

  func connect(
    url: URL,
    authorization: String?,
    entities: Set<String>,
    onEvent: @escaping @MainActor @Sendable (StreamEvent) -> Void
  ) {
    disconnect()
    let connectionGeneration = generation
    var request = URLRequest(url: url)
    if let authorization { request.setValue(authorization, forHTTPHeaderField: "Authorization") }
    receiveTask = Task { [weak self] in
      await self?.receive(
        request: request, entities: entities, generation: connectionGeneration, onEvent: onEvent)
    }
  }

  private func isCurrent(_ generation: Int) -> Bool {
    self.generation == generation && !Task.isCancelled
  }

  private func receive(
    request: URLRequest,
    entities: Set<String>,
    generation: Int,
    onEvent: @escaping @MainActor @Sendable (StreamEvent) -> Void
  ) async {
    var retryDelay: UInt64 = 1_000_000_000
    while isCurrent(generation) {
      let socket = makeSocket(request)
      task = socket
      socket.resume()
      do {
        try await socket.send(Self.jsonMessage([
          "id": 1, "type": "subscribe", "entities": entities.sorted(),
        ]))
        while isCurrent(generation) {
          let message = try await socket.receive()
          guard isCurrent(generation) else { return }
          retryDelay = 1_000_000_000
          let text: String
          switch message {
          case .string(let value): text = value
          case .data(let data): text = String(decoding: data, as: UTF8.self)
          @unknown default: continue
          }
          if let event = Self.decode(text) { await onEvent(event) }
        }
      } catch {
        guard isCurrent(generation) else { return }
        task = nil
        socket.cancel(with: .goingAway, reason: nil)
        await onEvent(.disconnected(error.localizedDescription))
      }
      guard isCurrent(generation) else { return }
      do { try await sleep(retryDelay) } catch { return }
      retryDelay = min(retryDelay * 2, 30_000_000_000)
    }
  }

  func disconnect() {
    generation &+= 1
    receiveTask?.cancel()
    receiveTask = nil
    task?.cancel(with: .goingAway, reason: nil)
    task = nil
  }

  func callService(_ call: ServiceCall) async throws {
    guard let task else { throw URLError(.notConnectedToInternet) }
    let commandID = nextCommandID
    nextCommandID += 1
    var object: [String: Any] = [
      "id": commandID,
      "type": "call_service",
      "domain": call.domain,
      "service": call.service,
      "service_data": try JSONSerialization.jsonObject(with: call.serviceData),
    ]
    if let entityID = call.entityID { object["target"] = ["entity_id": entityID] }
    // Commands are never replayed after a failure: the server may already have executed them.
    try await task.send(Self.jsonMessage(object))
  }

  nonisolated private static func jsonMessage(_ object: [String: Any]) throws
    -> URLSessionWebSocketTask.Message
  {
    let data = try JSONSerialization.data(withJSONObject: object)
    return .string(String(decoding: data, as: UTF8.self))
  }

  nonisolated private static func decode(_ text: String) -> StreamEvent? {
    guard let data = text.data(using: .utf8),
      let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
      let type = object["type"] as? String
    else { return nil }
    if type == "lovelace_updated" { return .dashboardChanged }
    if type == "documents_changed", let cardIDs = object["card_ids"] as? [String] {
      return .documentsChanged(Set(cardIDs))
    }
    guard type == "state", let raw = object["bindings"] as? [String: Any] else { return nil }
    var bindings: [String: BindingValue] = [:]
    for (name, value) in raw {
      bindings[name] = BindingValue(name: name, json: value)
    }
    return .bindings(bindings)
  }
}
