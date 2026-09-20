import Foundation

/// The Apple-side seam for Lovelace card → Remote Compose generation.
///
/// The recorded implementation keeps the app usable while AndroidX authoring is Android-only. The
/// eventual Kotlin/Native converter and the temporary gateway implementation both fit behind this
/// boundary, so SwiftUI and `RcNativePlayerUIKit` do not need to change when generation moves onto
/// the device.
protocol CardDocumentGenerator: Sendable {
  func document(for card: DashboardCard) async throws -> Data
}

struct RecordedCardDocumentGenerator: CardDocumentGenerator {
  func document(for card: DashboardCard) async throws -> Data {
    try DemoContent.data(for: card)
  }
}

struct GatewayCardDocumentGenerator: CardDocumentGenerator {
  let service: RemoteComposeService

  func document(for card: DashboardCard) async throws -> Data {
    try await service.fetchDocument(for: card)
  }
}
