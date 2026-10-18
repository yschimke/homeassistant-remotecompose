import Foundation

/// Versioned LAYOUT_CUSTOM input contract. Property storage is the existing native wire contract.
public enum NativeSwiftInput {
  public enum LineMode: Int, Sendable {
    case singleLine = 0
    case multiline = 1
  }

  public static let text = "rc:input/text:v1"
  public static let choice = "rc:input/choice:v1"
  public static let value = 1
  public static let valueReturn = 2
  public static let key = 3
  public static let label = 4
  public static let multiline = 5
  public static let options = 6
  public static let submit = 7
  public static let configs: Set<String> = [text, choice]
}

/// Retain per card across live snapshots/document replacement. Confined to the UI thread.
@MainActor
public final class NativeSwiftInputDrafts {
  private var edits: [String: String] = [:]
  private var defaults: [String: String] = [:]
  public var values: [String: String] { defaults.merging(edits) { _, edit in edit } }
  public init() {}
  public func value(for key: String, documentValue: String) -> String {
    defaults[key] = documentValue
    return edits[key] ?? documentValue
  }
  public func update(_ value: String, for key: String) { edits[key] = value }
  public func reset(_ key: String) { edits.removeValue(forKey: key) }
}

/// Explicit host policy and authenticated action bridge, shared by native Apple hosts.
@MainActor
public final class NativeSwiftInputConfiguration {
  public let drafts: NativeSwiftInputDrafts
  public let editable: Bool
  public let onChange: (String, String) -> Void
  public let onSubmit: (String, [String: String]) -> Void
  public init(drafts: NativeSwiftInputDrafts? = nil, editable: Bool = false,
              onChange: @escaping (String, String) -> Void = { _, _ in },
              onSubmit: @escaping (String, [String: String]) -> Void = { _, _ in }) {
    self.drafts = drafts ?? NativeSwiftInputDrafts(); self.editable = editable; self.onChange = onChange; self.onSubmit = onSubmit
  }
}
