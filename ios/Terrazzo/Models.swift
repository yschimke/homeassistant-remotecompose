import CoreFoundation
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
  case integer(Int)
  case color(UInt32)

  init?(name: String, json: Any) {
    switch json {
    case let value as NSNumber:
      if CFGetTypeID(value) == CFBooleanGetTypeID() {
        self = .integer(value.boolValue ? 1 : 0)
      } else if name.hasSuffix(".state_int") || name.hasSuffix(".is_on") {
        guard let integer = Int(exactly: value.doubleValue),
          !name.hasSuffix(".is_on") || integer == 0 || integer == 1
        else { return nil }
        self = .integer(integer)
      } else {
        guard value.floatValue.isFinite else { return nil }
        self = .float(value.floatValue)
      }
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

/// App Transport Security permits plain HTTP only for local-network hosts
/// (`NSAllowsLocalNetworking`); explain that instead of the system's generic ATS text.
func connectionFailureMessage(_ error: Error) -> String {
  if let urlError = error as? URLError, urlError.code == .appTransportSecurityRequiresSecureConnection {
    return "Plain HTTP is only allowed for local network addresses (for example homeassistant.local "
      + "or a LAN IP). Use an https:// URL for a remote Home Assistant or gateway."
  }
  return error.localizedDescription
}

enum AppRoute: Hashable, Identifiable {
  case settings
  var id: Self { self }
}

// Keep JSON bytes across the actor boundary, preserving arrays, objects, null and scalar types.
struct ServiceCall: Sendable {
  let domain: String
  let service: String
  let entityID: String?
  let serviceData: Data

  init?(metadata: String) {
    let payload = metadata.components(separatedBy: "\n--RC-METADATA-V1--\n").first ?? metadata
    guard let data = payload.data(using: .utf8),
      let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
    else { return nil }
    let type = (object["type"] as? String ?? "").lowercased()
    let entityID = (object["entityId"] ?? object["entity_id"]) as? String
    self.entityID = entityID
    if type.hasSuffix("toggle"), let entityID, !entityID.isEmpty {
      // Match Android's DashboardActionDispatcher: `homeassistant.toggle` flips every
      // toggleable domain, so the entity prefix is never used as the service domain.
      domain = "homeassistant"
      service = "toggle"
    } else if type.hasSuffix("callservice"),
      let domain = object["domain"] as? String,
      let service = object["service"] as? String
    {
      self.domain = domain
      self.service = service
    } else {
      return nil
    }
    guard let serviceData = try? JSONSerialization.data(
      withJSONObject: object["serviceData"] as? [String: Any] ?? [:])
    else { return nil }
    self.serviceData = serviceData
  }
}
