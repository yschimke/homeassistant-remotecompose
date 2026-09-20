import Foundation
import UIKit

@MainActor
final class DashboardStore: ObservableObject {
  enum Phase: Equatable {
    case signedOut
    case loading
    case ready
    case failed(String)
  }

  @Published private(set) var phase: Phase = .signedOut
  @Published private(set) var feed: DashboardFeed?
  @Published private(set) var documents: [String: Data] = [:]
  @Published private(set) var bindings: [String: BindingValue] = [:]
  @Published private(set) var bindingRevision = 0
  @Published var selectedDashboardID: String?
  @Published var route: AppRoute?
  @Published private(set) var isDemo = false
  @Published private(set) var streamMessage: String?

  private let cache = CardCache()
  private let credentialStore = CredentialStore()
  private let liveStream = LiveBindingStream()
  private var service: RemoteComposeService?
  private var documentGenerator: any CardDocumentGenerator = RecordedCardDocumentGenerator()
  private var refreshTask: Task<Void, Never>?
  private var documentRefreshTask: Task<Void, Never>?
  private var pendingDocumentCardIDs: Set<String> = []

  var selectedDashboard: Dashboard? {
    let dashboards = feed?.dashboards ?? []
    return dashboards.first(where: { $0.id == selectedDashboardID }) ?? dashboards.first
  }

  init() {
    if ProcessInfo.processInfo.arguments.contains("--demo") {
      startDemo()
    } else {
      refreshTask = Task { [weak self] in await self?.restore() }
    }
  }

  func connect(baseURL: String, token: String) {
    guard let configuration = ServerConfiguration(baseURLText: baseURL, token: token) else {
      phase = .failed("Enter a valid http or https URL.")
      return
    }
    UserDefaults.standard.set(baseURL, forKey: "serverURL")
    credentialStore.saveToken(token)
    isDemo = false
    phase = .loading
    let nextService = RemoteComposeService(configuration: configuration)
    service = nextService
    documentGenerator = GatewayCardDocumentGenerator(service: nextService)
    refreshTask?.cancel()
    documentRefreshTask?.cancel()
    documentRefreshTask = nil
    pendingDocumentCardIDs.removeAll()
    refreshTask = Task { [weak self] in await self?.refresh() }
  }

  func startDemo() {
    refreshTask?.cancel()
    documentRefreshTask?.cancel()
    documentRefreshTask = nil
    pendingDocumentCardIDs.removeAll()
    Task { await liveStream.disconnect() }
    service = nil
    documentGenerator = RecordedCardDocumentGenerator()
    isDemo = true
    feed = DemoContent.feed
    selectedDashboardID = feed?.dashboards.first?.id
    documents = Dictionary(
      uniqueKeysWithValues: DemoContent.feed.dashboards.flatMap(\.cards).compactMap { card in
        try? (card.id, DemoContent.data(for: card))
      })
    phase = .ready
  }

  func refresh() async {
    guard let service else { return }
    phase = feed == nil ? .loading : .ready
    do {
      let nextFeed = try await service.fetchFeed()
      try await cache.save(feed: nextFeed)
      feed = nextFeed
      selectedDashboardID = selectedDashboardID ?? nextFeed.dashboards.first?.id
      await loadDocuments(in: nextFeed)
      try await connectStream(feed: nextFeed, service: service)
      streamMessage = nil
      phase = .ready
    } catch {
      if feed == nil { await restoreCachedFeed() }
      if feed == nil { phase = .failed(error.localizedDescription) } else { phase = .ready }
      streamMessage = "Showing cached data"
    }
  }

  func signOut() {
    refreshTask?.cancel()
    documentRefreshTask?.cancel()
    documentRefreshTask = nil
    pendingDocumentCardIDs.removeAll()
    Task {
      await liveStream.disconnect()
      try? await cache.clear()
    }
    UserDefaults.standard.removeObject(forKey: "serverURL")
    credentialStore.clearToken()
    service = nil
    isDemo = false
    feed = nil
    documents = [:]
    bindings = [:]
    phase = .signedOut
  }

  func dispatch(metadata: String) {
    guard !isDemo else { return }
    let payload = metadata.components(separatedBy: "\n--RC-METADATA-V1--\n").first ?? metadata
    guard let data = payload.data(using: .utf8),
      let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
    else { return }
    let type = (object["type"] as? String ?? "").lowercased()

    if type.hasSuffix("url"), let urlText = object["url"] as? String,
      let url = URL(string: urlText)
    {
      UIApplication.shared.open(url)
      return
    }

    let entityID = (object["entityId"] ?? object["entity_id"]) as? String
    let domain: String
    let serviceName: String
    if type.hasSuffix("toggle"), let entityID {
      domain = entityID.split(separator: ".").first.map(String.init) ?? "homeassistant"
      serviceName = "toggle"
    } else if type.hasSuffix("callservice"),
      let actionDomain = object["domain"] as? String,
      let actionService = object["service"] as? String
    {
      domain = actionDomain
      serviceName = actionService
    } else {
      return
    }

    let stringData = (object["serviceData"] as? [String: Any] ?? [:]).compactMapValues {
      value -> String? in
      if let value = value as? String { return value }
      if let value = value as? NSNumber { return value.stringValue }
      return nil
    }
    Task {
      do {
        try await liveStream.callService(
          domain: domain, service: serviceName, entityID: entityID, serviceData: stringData)
      } catch {
        streamMessage = error.localizedDescription
      }
    }
  }

  private func restore() async {
    let defaults = UserDefaults.standard
    guard let baseURL = defaults.string(forKey: "serverURL") else { return }
    connect(baseURL: baseURL, token: credentialStore.loadToken())
  }

  private func restoreCachedFeed() async {
    guard let cached = await cache.loadFeed() else { return }
    feed = cached
    selectedDashboardID = selectedDashboardID ?? cached.dashboards.first?.id
    for card in cached.dashboards.flatMap(\.cards) {
      if let data = await cache.loadDocument(key: card.documentURL) { documents[card.id] = data }
    }
  }

  private func loadDocuments(in feed: DashboardFeed) async {
    let generator = documentGenerator
    await withTaskGroup(of: (String, String, Data?).self) { group in
      for card in feed.dashboards.flatMap(\.cards) {
        group.addTask { [cache] in
          if let data = try? await generator.document(for: card) {
            return (card.id, card.documentURL, data)
          }
          return (card.id, card.documentURL, await cache.loadDocument(key: card.documentURL))
        }
      }
      for await (cardID, cacheKey, data) in group {
        guard let data else { continue }
        documents[cardID] = data
        try? await cache.save(document: data, key: cacheKey)
      }
    }
  }

  private func connectStream(feed: DashboardFeed, service: RemoteComposeService) async throws {
    let entities = Set(feed.dashboards.flatMap(\.cards).flatMap(\.entities))
    guard !entities.isEmpty else { return }
    let url = try await service.streamURL(for: feed)
    let authorization = await service.authorizationHeader()
    await liveStream.connect(url: url, authorization: authorization, entities: entities) {
      [weak self] event in
      guard let self else { return }
      switch event {
      case .bindings(let next):
        bindings.merge(next) { _, latest in latest }
        bindingRevision &+= 1
        streamMessage = nil
      case .dashboardChanged:
        refreshTask?.cancel()
        refreshTask = Task { [weak self] in await self?.refresh() }
      case .documentsChanged(let cardIDs):
        scheduleDocumentReload(cardIDs)
      case .disconnected(let message): streamMessage = message
      }
    }
  }

  private func reloadDocuments(cardIDs: Set<String>) async {
    guard let feed else { return }
    let cards = feed.dashboards.flatMap(\.cards).filter { cardIDs.contains($0.id) }
    let generator = documentGenerator
    for card in cards {
      guard let data = try? await generator.document(for: card) else { continue }
      documents[card.id] = data
      try? await cache.save(document: data, key: card.documentURL)
    }
  }

  private func scheduleDocumentReload(_ cardIDs: Set<String>) {
    pendingDocumentCardIDs.formUnion(cardIDs)
    guard documentRefreshTask == nil else { return }
    documentRefreshTask = Task { [weak self] in
      guard let self else { return }
      while !pendingDocumentCardIDs.isEmpty, !Task.isCancelled {
        let next = pendingDocumentCardIDs
        pendingDocumentCardIDs.removeAll()
        await reloadDocuments(cardIDs: next)
      }
      documentRefreshTask = nil
    }
  }
}
