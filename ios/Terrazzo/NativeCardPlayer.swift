import RcNativePlayerCore
import RcNativePlayerUIKit
import SwiftUI

struct NativeCardPlayer: UIViewRepresentable {
  let data: Data
  let bindings: [String: BindingValue]
  let revision: Int
  var inputEditing: Bool = false
  var onMetadataAction: (String) -> Void = { _ in }
  var onDiagnostics: (RemoteComposeNativePlayerDiagnostics) -> Void = { _ in }

  func makeCoordinator() -> Coordinator { Coordinator() }

  func makeUIView(context: Context) -> RemoteComposeNativePlayerView {
    let view = RemoteComposeNativePlayerView(
      data: data,
      background: .transparent,
      compatibilityPolicy: .compatible,
      androidCompatibility: .enabled,
      customComponents: context.coordinator.inputRegistry(editable: inputEditing, onAction: onMetadataAction),
      onEvent: handle,
      onDiagnostics: onDiagnostics)
    context.coordinator.didLoad(data)
    context.coordinator.apply(bindings, revision: revision, to: view)
    return view
  }

  func updateUIView(_ view: RemoteComposeNativePlayerView, context: Context) {
    view.onEvent = handle
    view.onDiagnostics = onDiagnostics
    view.configureCustomComponents(context.coordinator.inputRegistry(editable: inputEditing, onAction: onMetadataAction))
    context.coordinator.load(data, into: view)
    context.coordinator.apply(bindings, revision: revision, to: view)
  }

  private func handle(_ event: RemoteComposeNativePlayerEvent) {
    switch event {
    case .actionWithMetadata(_, let metadata): onMetadataAction(metadata)
    case .namedAction(let name, let value):
      if name == "ha", case .text(let metadata) = value { onMetadataAction(metadata) }
    default: break
    }
  }

  @MainActor
  final class Coordinator {
    private let drafts = NativeSwiftInputDrafts()
    private var inputs: RemoteComposeNativeCustomComponentRegistry?
    private var inputPolicy: Bool?
    func inputRegistry(editable: Bool, onAction: @escaping (String) -> Void) -> RemoteComposeNativeCustomComponentRegistry {
      if let inputs, inputPolicy == editable { return inputs }
      inputPolicy = editable
      let registry = RemoteComposeNativeCustomComponentRegistry().registerInputs(drafts: drafts, editable: editable,
        onSubmit: { action, values in
          let prefix = "ha:submit-inputs:v1:"
          guard editable, action.hasPrefix(prefix), values.values.allSatisfy({ $0.count <= 4096 }),
            let data = String(action.dropFirst(prefix.count)).data(using: .utf8),
            var object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
          else { return }
          func bind(_ value: Any) -> Any {
            if let text = value as? String, text.hasPrefix("{{input."), text.hasSuffix("}}") {
              return values[String(text.dropFirst(8).dropLast(2))] ?? ""
            }
            if let object = value as? [String: Any] { return object.mapValues(bind) }
            if let array = value as? [Any] { return array.map(bind) }
            return value
          }
          object["serviceData"] = bind(object["serviceData"] ?? [:])
          guard let data = try? JSONSerialization.data(withJSONObject: object), let payload = String(data: data, encoding: .utf8) else { return }
          onAction(payload)
        })
      inputs = registry
      return registry
    }

    private var revision = -1
    private var loadedData: Data?
    private var task: Task<Void, Never>?

    deinit { task?.cancel() }

    func didLoad(_ data: Data) { loadedData = data }

    @MainActor
    func load(_ data: Data, into view: RemoteComposeNativePlayerView) {
      guard data != loadedData else { return }
      loadedData = data
      revision = -1
      task?.cancel()
      view.load(data)
    }

    @MainActor
    func apply(
      _ bindings: [String: BindingValue], revision nextRevision: Int,
      to view: RemoteComposeNativePlayerView
    ) {
      guard nextRevision != revision else { return }
      revision = nextRevision
      task?.cancel()
      task = Task { @MainActor [weak view] in
        // Opening a document is asynchronous. Retry briefly so subscription hydration received
        // with the manifest cannot race the native session's first frame.
        for attempt in 0..<5 {
          guard !Task.isCancelled, let view else { return }
          var acceptedAny = bindings.isEmpty
          for (name, value) in bindings {
            let accepted: Bool
            switch value {
            case .string(let value): accepted = await view.setString(value, for: name)
            case .float(let value): accepted = await view.setFloat(value, for: name)
            case .integer(let value): accepted = await view.setInteger(value, for: name)
            case .color(let value): accepted = await view.setColor(value, for: name)
            }
            acceptedAny = acceptedAny || accepted
          }
          if acceptedAny || attempt == 4 { return }
          try? await Task.sleep(for: .milliseconds(80))
        }
      }
    }
  }
}
