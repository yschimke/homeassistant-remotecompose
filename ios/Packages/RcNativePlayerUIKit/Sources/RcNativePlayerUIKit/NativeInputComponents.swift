#if canImport(UIKit)
import UIKit
#if canImport(RcNativePlayerCore)
import RcNativePlayerCore
#endif

extension RemoteComposeNativeCustomComponentRegistry {
  /// Explicit policy opt-in. Widgets, previews and demo hosts leave editable false.
  @discardableResult
  public func registerInputs(
    drafts: NativeSwiftInputDrafts,
    editable: Bool = false,
    onChange: @escaping (String, String) -> Void = { _, _ in },
    onSubmit: @escaping (String, [String: String]) -> Void = { _, _ in }
  ) -> Self {
    for config in NativeSwiftInput.configs.sorted() {
      register(config, makeUIView: { component in
        NativeInputView(component: component, drafts: drafts, editable: editable,
                        onChange: onChange, onSubmit: onSubmit)
      }, updateUIView: { view, component in view.update(component) },
      dismantleUIView: { view in view.endEditing(true) })
    }
    return self
  }
}

@MainActor
final class NativeInputView: UIStackView, UITextFieldDelegate, UITextViewDelegate {
  private var component: RemoteComposeNativeCustomComponent
  private let drafts: NativeSwiftInputDrafts
  private let editable: Bool
  private let onChange: (String, String) -> Void
  private let onSubmit: (String, [String: String]) -> Void
  private let caption = UILabel()
  private let fallback = UILabel()
  private let field = UITextField()
  private let multiline = UITextView()
  private let choice = UIButton(type: .system)
  private let toggle = UISwitch()
  private let range = UISlider()
  private let validation = UILabel()
  private let send = UIButton(type: .system)
  private var pendingChanges = 0
  private var submitRequested = false
  private var key: String { component.text(.init(NativeSwiftInput.key)) }
  private var writable: Bool {
    editable && component.integer(.init(NativeSwiftInput.enabled, default: 1)) == 1 && !key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
      component.properties.contains { $0.id == NativeSwiftInput.valueReturn &&
        $0.kind == .textReturn }
  }

  private func constraint(_ id: Int) -> Double? { Double(component.text(.init(id))) }
  private var valid: Bool {
    let value = drafts.value(for: key, documentValue: component.text(.init(NativeSwiftInput.value)))
    return [NativeSwiftInput.minimum, NativeSwiftInput.maximum, NativeSwiftInput.step].allSatisfy { component.text(.init($0)).isEmpty || constraint($0)?.isFinite == true } &&
      NativeSwiftInput.isValid(config: component.config, value: value,
        options: component.text(.init(NativeSwiftInput.options)).split(separator: "\n").map(String.init),
        minimum: constraint(NativeSwiftInput.minimum), maximum: constraint(NativeSwiftInput.maximum), step: constraint(NativeSwiftInput.step))
  }

  init(component: RemoteComposeNativeCustomComponent, drafts: NativeSwiftInputDrafts,
       editable: Bool, onChange: @escaping (String, String) -> Void,
       onSubmit: @escaping (String, [String: String]) -> Void) {
    self.component = component; self.drafts = drafts; self.editable = editable
    self.onChange = onChange; self.onSubmit = onSubmit
    super.init(frame: .zero)
    axis = .vertical; spacing = 4; isLayoutMarginsRelativeArrangement = true
    directionalLayoutMargins = .init(top: 6, leading: 8, bottom: 6, trailing: 8)
    [caption, fallback, field, multiline, choice, toggle, range, validation, send].forEach(addArrangedSubview)
    caption.font = .preferredFont(forTextStyle: .caption1)
    fallback.numberOfLines = 0
    field.borderStyle = .roundedRect; field.delegate = self
    field.addTarget(self, action: #selector(fieldChanged), for: .editingChanged)
    multiline.delegate = self; multiline.font = .preferredFont(forTextStyle: .body)
    if #available(iOS 14.0, *) { choice.showsMenuAsPrimaryAction = true }
    else { choice.addTarget(self, action: #selector(choose), for: .touchUpInside) }
    toggle.addTarget(self, action: #selector(toggleChanged), for: .valueChanged)
    range.addTarget(self, action: #selector(rangeChanged), for: .valueChanged)
    validation.text = "Enter a valid value"; validation.textColor = .systemRed
    send.setTitle("Send", for: .normal)
    send.addTarget(self, action: #selector(submit), for: .touchUpInside)
    update(component)
  }

  @available(*, unavailable)
  required init(coder: NSCoder) { fatalError("init(coder:) is unavailable") }

  func update(_ component: RemoteComposeNativeCustomComponent) {
    self.component = component
    let plain = component.integer(.init(NativeSwiftInput.chrome)) == NativeSwiftInput.ChromeMode.none.rawValue
    directionalLayoutMargins = plain ? .zero : .init(top: 6, leading: 8, bottom: 6, trailing: 8)
    caption.isHidden = plain
    field.borderStyle = plain ? .none : .roundedRect
    multiline.backgroundColor = plain ? .clear : .systemBackground
    multiline.textContainerInset = plain ? .zero : .init(top: 8, left: 0, bottom: 8, right: 0)
    multiline.textContainer.lineFragmentPadding = plain ? 0 : 5
    let label = component.text(.init(NativeSwiftInput.label))
    caption.text = label; field.accessibilityLabel = label
    multiline.accessibilityLabel = label; choice.accessibilityLabel = label
    let documentValue = component.text(.init(NativeSwiftInput.value))
    let value = drafts.value(for: key, documentValue: documentValue)
    let isChoice = component.config == NativeSwiftInput.choice
    let isToggle = component.config == NativeSwiftInput.toggle
    let isRange = component.config == NativeSwiftInput.range
    toggle.accessibilityLabel = label; range.accessibilityLabel = label
    toggle.isHidden = !writable || !isToggle
    range.isHidden = !writable || !isRange
    toggle.isOn = value == "true"
    if isRange, let minimum = constraint(NativeSwiftInput.minimum), let maximum = constraint(NativeSwiftInput.maximum), let step = constraint(NativeSwiftInput.step), step > 0, minimum < maximum, ((maximum-minimum)/step).isFinite {
      range.minimumValue = 0; range.maximumValue = Float(min(10000, (maximum-minimum)/step))
      range.value = Float(((Double(value) ?? minimum)-minimum)/step)
      range.accessibilityValue = value
    }
    if isRange { caption.text = "\(label): \(value)" }
    field.keyboardType = component.config == NativeSwiftInput.number ? .numbersAndPunctuation : .default
    validation.text = "Enter a valid value"
    validation.isHidden = !writable || valid
    let isMultiline = component.config == NativeSwiftInput.text && component.integer(.init(NativeSwiftInput.multiline)) == NativeSwiftInput.LineMode.multiline.rawValue
    fallback.isHidden = writable
    fallback.text = "\(value) · \(component.integer(.init(NativeSwiftInput.enabled, default: 1)) != 1 ? "Unavailable" : "Open in app to edit")"
    field.isHidden = !writable || isChoice || isToggle || isRange || isMultiline
    multiline.isHidden = !writable || isChoice || isToggle || isRange || !isMultiline
    choice.isHidden = !writable || !isChoice
    let submitLabel = component.text(.init(NativeSwiftInput.submitLabel))
    send.setTitle(submitLabel.isEmpty ? "Send" : submitLabel, for: .normal)
    send.isHidden = !writable || component.text(.init(NativeSwiftInput.submit)).isEmpty
    field.returnKeyType = send.isHidden ? .next : .send
    // Never replace an active marked-text composition or reset its selection on a live update.
    if pendingChanges == 0 && field.markedTextRange == nil && field.text != value { field.text = value }
    if pendingChanges == 0 && multiline.markedTextRange == nil && multiline.text != value { multiline.text = value }
    choice.setTitle(value.isEmpty ? "Choose…" : value, for: .normal)
    let options = component.text(.init(NativeSwiftInput.options)).split(separator: "\n").map(String.init)
    if #available(iOS 14.0, *) {
    choice.menu = UIMenu(children: options.map { option in
      UIAction(title: option, state: option == value ? .on : .off) { [weak self] _ in
        self?.changed(option)
      }
    })
    }
    if writable && pendingChanges == 0 && value != documentValue {
      component.send(value, to: .init(NativeSwiftInput.valueReturn))
    }
  }
  @objc private func choose() {
    let sheet = UIAlertController(title: caption.text, message: nil, preferredStyle: .actionSheet)
    for option in component.text(.init(NativeSwiftInput.options)).split(separator: "\n").map(String.init) {
      sheet.addAction(UIAlertAction(title: option, style: .default) { [weak self] _ in self?.changed(option) })
    }
    sheet.addAction(UIAlertAction(title: "Cancel", style: .cancel))
    sheet.popoverPresentationController?.sourceView = choice
    var responder: UIResponder? = self
    while let current = responder {
      if let controller = current as? UIViewController { controller.present(sheet, animated: true); break }
      responder = current.next
    }
  }
  private func changed(_ value: String) {
    guard writable else { return }
    let editedKey = key
    pendingChanges += 1
    component.send(value, to: .init(NativeSwiftInput.valueReturn)) { [weak self] accepted in
      guard let self else { return }
      self.pendingChanges -= 1
      guard self.key == editedKey else { return }
      if accepted {
        self.drafts.update(value, for: editedKey)
        self.onChange(editedKey, value)
      } else { self.submitRequested = false }
      if self.pendingChanges == 0 {
        self.update(self.component)
        if !accepted {
          self.validation.isHidden = false
          self.validation.text = "Edit was not accepted"
          UIAccessibility.post(notification: .announcement, argument: "Edit was not accepted")
        }
      }
      self.completePendingSubmissions(accepted: accepted)
    }
  }
  private func formRoot() -> UIView {
    var root: UIView = self
    while let parent = root.superview {
      root = parent
      if parent is RemoteComposeNativePlayerView { break }
    }
    return root
  }
  private func completePendingSubmissions(accepted: Bool) {
    func visit(_ view: UIView) {
      if let input = view as? NativeInputView {
        if !accepted { input.submitRequested = false }
        else if input.submitRequested { input.submit() }
      } else { view.subviews.forEach(visit) }
    }
    visit(formRoot())
  }
  @objc private func toggleChanged() { changed(toggle.isOn ? "true" : "false") }
  @objc private func rangeChanged() {
    guard let minimum = constraint(NativeSwiftInput.minimum), let step = constraint(NativeSwiftInput.step) else { return }
    range.value = range.value.rounded(); changed(String(minimum + Double(range.value)*step))
  }
  @objc private func fieldChanged() { changed(field.text ?? "") }
  func textViewDidChange(_ textView: UITextView) { changed(textView.text) }
  func textFieldShouldReturn(_ textField: UITextField) -> Bool {
    if send.isHidden {
      var root: UIView = self
      while let parent = root.superview {
        root = parent
        if parent is RemoteComposeNativePlayerView { break }
      }
      func editors(_ view: UIView) -> [UIView] {
        guard !view.isHidden else { return [] }
        if view is UITextField || view is UITextView { return [view] }
        return view.subviews.flatMap(editors)
      }
      let controls = editors(root)
      if let index = controls.firstIndex(where: { $0 === textField }), index + 1 < controls.count {
        controls[index + 1].becomeFirstResponder()
      } else { textField.resignFirstResponder() }
    } else { submit() }
    return false
  }
  @objc private func submit() {
    guard writable else { return }
    let action = component.text(.init(NativeSwiftInput.submit))
    guard !action.isEmpty else { return }
    let root = formRoot()
    func pending(_ view: UIView) -> Bool {
      if view.isHidden { return false }
      if let input = view as? NativeInputView { return input.pendingChanges > 0 }
      return view.subviews.contains(where: pending)
    }
    if pending(root) { submitRequested = true; return }
    submitRequested = false
    func keys(_ view: UIView) -> [String] {
      guard !view.isHidden else { return [] }
      if let input = view as? NativeInputView { return input.component.integer(.init(NativeSwiftInput.enabled, default: 1)) == 1 ? [input.key] : [] }
      return view.subviews.flatMap(keys)
    }
    func validInputs(_ view: UIView) -> Bool {
      if view.isHidden { return true }
      if let input = view as? NativeInputView { return input.component.integer(.init(NativeSwiftInput.enabled, default: 1)) != 1 || input.valid }
      return view.subviews.allSatisfy(validInputs)
    }
    guard validInputs(root) else { UIAccessibility.post(notification: .announcement, argument: "Enter a valid value"); return }
    var values = drafts.values(for: Set(keys(root)))
    values[key] = drafts.value(for: key, documentValue: component.text(.init(NativeSwiftInput.value)))
    onSubmit(action, values); endEditing(true)
  }
}
#endif
