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
  private let liveStream: LiveBindingStream
  private let makeService: (ServerConfiguration) -> RemoteComposeService
  private var connectionRevision = 0
  private var activeRefreshID = UUID()
  private var service: RemoteComposeService?
  private var documentGenerator: any CardDocumentGenerator = RecordedCardDocumentGenerator()
  private var refreshTask: Task<Void, Never>?
  private var documentRefreshTask: Task<Void, Never>?
  private var pendingDocumentCardIDs: Set<String> = []

  var selectedDashboard: Dashboard? {
    let dashboards = feed?.dashboards ?? []
    return dashboards.first(where: { $0.id == selectedDashboardID }) ?? dashboards.first
  }

  init(
    liveStream: LiveBindingStream = LiveBindingStream(),
    restoreOnLaunch: Bool = true,
    makeService: @escaping (ServerConfiguration) -> RemoteComposeService = {
      RemoteComposeService(configuration: $0)
    }
  ) {
    self.liveStream = liveStream
    self.makeService = makeService
    guard restoreOnLaunch else { return }
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
    stopLiveUpdates()
    bindings = [:]
    streamMessage = nil
    let nextService = makeService(configuration)
    service = nextService
    documentGenerator = GatewayCardDocumentGenerator(service: nextService)
    let revision = connectionRevision
    refreshTask = Task { [weak self] in
      guard let self, revision == connectionRevision, !Task.isCancelled else { return }
      await liveStream.disconnect()
      guard revision == connectionRevision, !Task.isCancelled else { return }
      await refresh()
    }
  }

  // Invalidate callbacks synchronously, before crossing to the stream actor.
  private func stopLiveUpdates() {
    connectionRevision &+= 1
    activeRefreshID = UUID()
    refreshTask?.cancel()
    documentRefreshTask?.cancel()
    documentRefreshTask = nil
    pendingDocumentCardIDs.removeAll()
    let revision = connectionRevision
    Task {
      guard revision == connectionRevision else { return }
      await liveStream.disconnect()
    }
  }

  func startDemo() {
    stopLiveUpdates()
    bindings = [:]
    streamMessage = nil
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
    let revision = connectionRevision
    let refreshID = UUID()
    activeRefreshID = refreshID
    func isCurrent() -> Bool {
      revision == connectionRevision && refreshID == activeRefreshID && !Task.isCancelled
    }
    phase = feed == nil ? .loading : .ready
    do {
      let nextFeed = try await service.fetchFeed()
      guard isCurrent() else { return }
      try await cache.save(feed: nextFeed)
      guard isCurrent() else { return }
      feed = nextFeed
      selectedDashboardID = selectedDashboardID ?? nextFeed.dashboards.first?.id
      await loadDocuments(in: nextFeed)
      guard isCurrent() else { return }
      try await connectStream(feed: nextFeed, service: service, revision: revision)
      guard isCurrent() else { return }
      streamMessage = nil
      phase = .ready
    } catch {
      guard isCurrent() else { return }
      if feed == nil { await restoreCachedFeed() }
      guard isCurrent() else { return }
      if feed == nil { phase = .failed(connectionFailureMessage(error)) } else { phase = .ready }
      streamMessage = "Showing cached data"
    }
  }

  func signOut() {
    stopLiveUpdates()
    Task { try? await cache.clear() }
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

    guard let call = ServiceCall(metadata: metadata) else { return }
    Task {
      do {
        try await liveStream.callService(call)
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
    let revision = connectionRevision
    let refreshID = activeRefreshID
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
        guard revision == connectionRevision, refreshID == activeRefreshID, !Task.isCancelled else {
          group.cancelAll()
          return
        }
        guard let data else { continue }
        documents[cardID] = data
        try? await cache.save(document: data, key: cacheKey)
      }
    }
  }

  private func connectStream(
    feed: DashboardFeed, service: RemoteComposeService, revision: Int
  ) async throws {
    let entities = Set(feed.dashboards.flatMap(\.cards).flatMap(\.entities))
    let url = try await service.streamURL(for: feed)
    let authorization = await service.authorizationHeader()
    guard revision == connectionRevision, !Task.isCancelled else { return }
    await liveStream.connect(url: url, authorization: authorization, entities: entities) {
      [weak self] event in
      guard let self, revision == connectionRevision else { return }
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
    let revision = connectionRevision
    for card in cards {
      guard let data = try? await generator.document(for: card) else { continue }
      guard revision == connectionRevision, !Task.isCancelled else { return }
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
      if !Task.isCancelled { documentRefreshTask = nil }
    }
  }
}
