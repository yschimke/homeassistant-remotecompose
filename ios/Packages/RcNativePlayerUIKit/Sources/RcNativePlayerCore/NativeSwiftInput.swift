import Foundation

/// Versioned LAYOUT_CUSTOM input contract. Property storage is the existing native wire contract.
public enum NativeSwiftInput {
  public enum LineMode: Int, Sendable {
    case singleLine = 0
    case multiline = 1
  }

  public static let text = "rc:input/text:v1"
  public static let choice = "rc:input/choice:v1"
  public static let toggle = "rc:input/toggle:v1"
  public static let number = "rc:input/number:v1"
  public static let range = "rc:input/range:v1"
  public static let minimum = 8
  public static let maximum = 9
  public static let step = 10
  public static let enabled = 11
  public static let submitLabel = 12
  public static let value = 1
  public static let valueReturn = 2
  public static let key = 3
  public static let label = 4
  public static let multiline = 5
  public static let options = 6
  public static let submit = 7
  public static let configs: Set<String> = [text, choice, toggle, number, range]
  /// Raw edits remain strings. Hosts validate values again before domain actions.
  public static func isValid(config: String, value: String, options: [String] = [], minimum: Double? = nil, maximum: Double? = nil, step: Double? = nil) -> Bool {
    if config == text { return true }
    if config == choice { return !options.isEmpty && options.allSatisfy { !$0.isEmpty && !$0.contains("\n") } && Set(options).count == options.count && options.contains(value) }
    if config == toggle { return value == "true" || value == "false" }
    guard config == number || config == range else { return false }
    guard [minimum, maximum, step].compactMap({ $0 }).allSatisfy({ $0.isFinite }),
          minimum == nil || maximum == nil || minimum! <= maximum!, step == nil || step! > 0 else { return false }
    if config == range {
      guard let minimum, let maximum, let step, minimum < maximum, (maximum-minimum)/step <= 10000 else { return false }
      let count = (maximum-minimum)/step
      guard abs(count-count.rounded()) <= 1e-7 * max(1,abs(count)) else { return false }
    }
    guard value.trimmingCharacters(in: .whitespacesAndNewlines) == value, value.range(of: #"^-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?$"#, options: .regularExpression) != nil,
          let number = Double(value), number.isFinite,
          minimum == nil || number >= minimum!, maximum == nil || number <= maximum! else { return false }
    if let step {
      let units = (number - (minimum ?? 0))/step
      return units.isFinite && abs(units-units.rounded()) <= 1e-7 * max(1,abs(units))
    }
    return true
  }

}

/// Retain per card across live snapshots/document replacement. Confined to the UI thread.
@MainActor
public final class NativeSwiftInputDrafts {
  private var edits: [String: String] = [:]
  private var defaults: [String: String] = [:]
  public var values: [String: String] { defaults.merging(edits) { _, edit in edit } }
  /// Submit only fields in the current form; retain dormant edits for future replacements.
  public func values(for keys: Set<String>) -> [String: String] { values.filter { keys.contains($0.key) } }
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
