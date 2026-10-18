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
    for config in [NativeSwiftInput.text, NativeSwiftInput.choice] {
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
private final class NativeInputView: UIStackView, UITextFieldDelegate, UITextViewDelegate {
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
  private let send = UIButton(type: .system)
  private var key: String { component.text(.init(NativeSwiftInput.key)) }
  private var writable: Bool {
    editable && !key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
      component.properties.contains { $0.id == NativeSwiftInput.valueReturn &&
        $0.kind == .textReturn }
  }

  init(component: RemoteComposeNativeCustomComponent, drafts: NativeSwiftInputDrafts,
       editable: Bool, onChange: @escaping (String, String) -> Void,
       onSubmit: @escaping (String, [String: String]) -> Void) {
    self.component = component; self.drafts = drafts; self.editable = editable
    self.onChange = onChange; self.onSubmit = onSubmit
    super.init(frame: .zero)
    axis = .vertical; spacing = 4; isLayoutMarginsRelativeArrangement = true
    directionalLayoutMargins = .init(top: 6, leading: 8, bottom: 6, trailing: 8)
    [caption, fallback, field, multiline, choice, send].forEach(addArrangedSubview)
    caption.font = .preferredFont(forTextStyle: .caption1)
    fallback.numberOfLines = 0
    field.borderStyle = .roundedRect; field.delegate = self
    field.addTarget(self, action: #selector(fieldChanged), for: .editingChanged)
    multiline.delegate = self; multiline.font = .preferredFont(forTextStyle: .body)
    if #available(iOS 14.0, *) { choice.showsMenuAsPrimaryAction = true }
    else { choice.addTarget(self, action: #selector(choose), for: .touchUpInside) }
    send.setTitle("Send", for: .normal)
    send.addTarget(self, action: #selector(submit), for: .touchUpInside)
    update(component)
  }

  @available(*, unavailable)
  required init(coder: NSCoder) { fatalError("init(coder:) is unavailable") }

  func update(_ component: RemoteComposeNativeCustomComponent) {
    self.component = component
    let label = component.text(.init(NativeSwiftInput.label))
    caption.text = label; field.accessibilityLabel = label
    multiline.accessibilityLabel = label; choice.accessibilityLabel = label
    let documentValue = component.text(.init(NativeSwiftInput.value))
    let value = drafts.value(for: key, documentValue: documentValue)
    let isChoice = component.config == NativeSwiftInput.choice
    let isMultiline = component.integer(.init(NativeSwiftInput.multiline)) == NativeSwiftInput.LineMode.multiline.rawValue
    fallback.isHidden = writable
    fallback.text = "\(value) · Open in app to edit"
    field.isHidden = !writable || isChoice || isMultiline
    multiline.isHidden = !writable || isChoice || !isMultiline
    choice.isHidden = !writable || !isChoice
    send.isHidden = !writable || component.text(.init(NativeSwiftInput.submit)).isEmpty
    field.returnKeyType = send.isHidden ? .next : .send
    // Never replace an active marked-text composition or reset its selection on a live update.
    if field.markedTextRange == nil && field.text != value { field.text = value }
    if multiline.markedTextRange == nil && multiline.text != value { multiline.text = value }
    choice.setTitle(value.isEmpty ? "Choose…" : value, for: .normal)
    let options = component.text(.init(NativeSwiftInput.options)).split(separator: "\n").map(String.init)
    if #available(iOS 14.0, *) {
    choice.menu = UIMenu(children: options.map { option in
      UIAction(title: option, state: option == value ? .on : .off) { [weak self] _ in
        self?.changed(option)
      }
    })
    }
    if writable && value != documentValue {
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
    guard writable, component.send(value, to: .init(NativeSwiftInput.valueReturn)) else { return }
    drafts.update(value, for: key); onChange(key, value)
    choice.setTitle(value, for: .normal)
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
    var root: UIView = self
    while let parent = root.superview {
      root = parent
      if parent is RemoteComposeNativePlayerView { break }
    }
    func keys(_ view: UIView) -> [String] {
      guard !view.isHidden else { return [] }
      if let input = view as? NativeInputView { return [input.key] }
      return view.subviews.flatMap(keys)
    }
    var values = drafts.values(for: Set(keys(root)))
    values[key] = drafts.value(for: key, documentValue: component.text(.init(NativeSwiftInput.value)))
    onSubmit(action, values); endEditing(true)
  }
}
#endif
