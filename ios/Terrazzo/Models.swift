import Foundation

struct DashboardFeed: Codable, Equatable, Sendable {
  let version: Int
  let dashboards: [Dashboard]
  var streamURL: String?

  enum CodingKeys: String, CodingKey {
    case version, dashboards
    case streamURL = "stream_url"
  }
}

struct Dashboard: Codable, Identifiable, Equatable, Sendable {
  let id: String
  let title: String
  var icon: String?
  let sections: [DashboardSection]

  var cards: [DashboardCard] { sections.flatMap(\.cards) }
}

struct DashboardSection: Codable, Identifiable, Equatable, Sendable {
  let id: String
  var title: String?
  let cards: [DashboardCard]
}

struct DashboardCard: Codable, Identifiable, Equatable, Sendable {
  enum UpdateMode: String, Codable, Sendable {
    case namedBindings = "named_bindings"
    case document
  }

  let id: String
  let type: String
  var title: String?
  let documentURL: String
  var height: Double?
  var entities: [String] = []
  var updateMode: UpdateMode?

  var effectiveUpdateMode: UpdateMode { updateMode ?? .namedBindings }

  enum CodingKeys: String, CodingKey {
    case id, type, title, height, entities
    case documentURL = "document_url"
    case updateMode = "update_mode"
  }

  var displayHeight: Double { max(44, height ?? 120) }
}

enum BindingValue: Equatable, Sendable {
  case string(String)
  case float(Float)
  case color(UInt32)

  init?(json: Any) {
    switch json {
    case let value as Bool: self = .float(value ? 1 : 0)
    case let value as NSNumber: self = .float(value.floatValue)
    case let value as String: self = .string(value)
    default: return nil
    }
  }
}

struct ServerConfiguration: Codable, Equatable, Sendable {
  let baseURL: URL
  let token: String

  init?(baseURLText: String, token: String) {
    let normalized = baseURLText.trimmingCharacters(in: .whitespacesAndNewlines)
      .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
    guard let url = URL(string: normalized), let scheme = url.scheme,
      scheme == "http" || scheme == "https"
    else { return nil }
    self.baseURL = url
    self.token = token.trimmingCharacters(in: .whitespacesAndNewlines)
  }
}

enum AppRoute: Hashable, Identifiable {
  case settings
  var id: Self { self }
}
