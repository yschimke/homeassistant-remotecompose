import Foundation

actor LiveBindingStream {
  enum StreamEvent: Sendable {
    case bindings([String: BindingValue])
    case dashboardChanged
    case documentsChanged(Set<String>)
    case disconnected(String)
  }

  private var task: URLSessionWebSocketTask?
  private var receiveTask: Task<Void, Never>?
  private var nextCommandID = 2

  func connect(
    url: URL,
    authorization: String?,
    entities: Set<String>,
    onEvent: @escaping @MainActor @Sendable (StreamEvent) -> Void
  ) {
    disconnect()
    var request = URLRequest(url: url)
    if let authorization { request.setValue(authorization, forHTTPHeaderField: "Authorization") }
    let socket = URLSession.shared.webSocketTask(with: request)
    task = socket
    socket.resume()

    receiveTask = Task { [weak self] in
      guard let self else { return }
      do {
        try await self.sendSubscription(entities)
        while !Task.isCancelled {
          let message = try await socket.receive()
          let text: String
          switch message {
          case .string(let value): text = value
          case .data(let data): text = String(decoding: data, as: UTF8.self)
          @unknown default: continue
          }
          if let event = Self.decode(text) { await onEvent(event) }
        }
      } catch is CancellationError {
        return
      } catch {
        await onEvent(.disconnected(error.localizedDescription))
      }
    }
  }

  func disconnect() {
    receiveTask?.cancel()
    receiveTask = nil
    task?.cancel(with: .goingAway, reason: nil)
    task = nil
  }

  func callService(
    domain: String,
    service: String,
    entityID: String?,
    serviceData: [String: String] = [:]
  ) async throws {
    guard let task else { throw URLError(.notConnectedToInternet) }
    let commandID = nextCommandID
    nextCommandID += 1
    var object: [String: Any] = [
      "id": commandID,
      "type": "call_service",
      "domain": domain,
      "service": service,
      "service_data": serviceData,
    ]
    if let entityID { object["target"] = ["entity_id": entityID] }
    let data = try JSONSerialization.data(withJSONObject: object)
    try await task.send(.data(data))
  }

  private func sendSubscription(_ entities: Set<String>) async throws {
    guard let task else { return }
    let object: [String: Any] = ["id": 1, "type": "subscribe", "entities": entities.sorted()]
    let data = try JSONSerialization.data(withJSONObject: object)
    try await task.send(.data(data))
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
    return .bindings(raw.compactMapValues(BindingValue.init(json:)))
  }
}
