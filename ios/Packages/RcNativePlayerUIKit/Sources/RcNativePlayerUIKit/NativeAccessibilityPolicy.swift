import Foundation

enum NativeAccessibilityRole: Int, Sendable {
  case button = 0
  case checkbox = 1
  case toggle = 2
  case radioButton = 3
  case tab = 4
  case image = 5
  case dropdownList = 6
  case picker = 7
  case carousel = 8
  case unknown = 9
}

enum NativeAccessibilityMode: Int, Sendable {
  case set = 0
  case clearAndSet = 1
  case merge = 2
}

enum NativeAccessibilityElementKind: Equatable, Sendable {
  case button
  case checkbox
  case toggle
  case radioButton
  case tab
  case image
  case dropdownList
  case picker
  case carousel
  case generic
}

struct NativeAccessibilityDescriptor: Equatable, Sendable {
  let role: NativeAccessibilityRole?
  let mode: NativeAccessibilityMode
  let contentDescription: String?
  let text: String?
  let stateDescription: String?
  let isEnabled: Bool
  let isClickable: Bool

  var elementKind: NativeAccessibilityElementKind {
    switch role {
    case .button: .button
    case .checkbox: .checkbox
    case .toggle: .toggle
    case .radioButton: .radioButton
    case .tab: .tab
    case .image: .image
    case .dropdownList: .dropdownList
    case .picker: .picker
    case .carousel: .carousel
    case .unknown, nil: isClickable ? .button : .generic
    }
  }

  var derivesLabelFromDescendants: Bool {
    guard Self.nonBlank(contentDescription) == nil, Self.nonBlank(text) == nil else { return false }
    switch elementKind {
    case .button, .checkbox, .toggle, .radioButton, .tab, .dropdownList, .picker, .carousel:
      return true
    case .image, .generic:
      return false
    }
  }

  var hidesDescendants: Bool {
    mode == .clearAndSet || mode == .merge || derivesLabelFromDescendants
  }

  func mergingBehavior(from descendant: NativeAccessibilityDescriptor) -> Self {
    guard mode == .merge else { return self }
    return NativeAccessibilityDescriptor(
      role: mergeRole ?? descendant.mergeRole,
      mode: mode,
      contentDescription: contentDescription,
      text: text,
      stateDescription: stateDescription ?? descendant.stateDescription,
      isEnabled: isEnabled && descendant.isEnabled,
      isClickable: isClickable || descendant.isClickable)
  }

  private var mergeRole: NativeAccessibilityRole? {
    role == .unknown ? nil : role
  }

  func resolvedLabel(descendantLabels: [String]) -> String? {
    var fragments = [contentDescription, text].compactMap(Self.nonBlank)
    if mode == .merge || derivesLabelFromDescendants {
      fragments += descendantLabels.compactMap(Self.nonBlank)
    }
    var seen = Set<String>()
    let unique = fragments.filter { seen.insert($0).inserted }
    return unique.isEmpty ? nil : unique.joined(separator: ", ")
  }

  private static func nonBlank(_ value: String?) -> String? {
    guard let value, !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
      return nil
    }
    return value
  }
}
