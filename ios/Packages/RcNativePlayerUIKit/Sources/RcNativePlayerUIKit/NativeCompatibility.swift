import Foundation

/// How a load handles features outside the native player's current compatibility profile.
public enum RemoteComposeNativePlayerCompatibilityPolicy: Equatable, Sendable {
  /// Render the supported subset and report every known difference through diagnostics.
  case compatible

  /// Refuse a frame with any unsupported or approximate behavior.
  case strict
}

public struct RemoteComposeNativePlayerDiagnostic: Equatable, Sendable {
  public enum Severity: Equatable, Sendable {
    case warning
    case unsupported
  }

  public let severity: Severity
  public let opcode: Int
  public let operationName: String
  public let componentID: Int
  public let reason: String
}

public struct RemoteComposeNativePlayerDiagnostics: Equatable, Sendable {
  public let issues: [RemoteComposeNativePlayerDiagnostic]
  public let unsupportedOpcodes: [Int]
  public let notes: [String]

  public var isPartial: Bool { !issues.isEmpty }
}

public enum RemoteComposeNativePlayerError: Error, LocalizedError {
  case documentTooLarge(Int)
  case decode(String)
  case incompatible(RemoteComposeNativePlayerDiagnostics)

  public var errorDescription: String? {
    switch self {
    case .documentTooLarge(let count):
      "The Remote Compose document is too large to bridge (\(count) bytes)."
    case .decode(let message):
      "The native player could not decode this document: \(message)"
    case .incompatible(let diagnostics):
      "The document is outside the native player compatibility profile "
        + "(\(diagnostics.issues.count) issue(s))."
    }
  }
}

enum RemoteComposeNativeCompatibilityDecision {
  static func shouldRender(
    policy: RemoteComposeNativePlayerCompatibilityPolicy,
    diagnostics: RemoteComposeNativePlayerDiagnostics
  ) -> Bool {
    policy == .compatible || !diagnostics.isPartial
  }
}
