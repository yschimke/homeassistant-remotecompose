import RcNativePlayerUIKit
import SwiftUI

struct NativeCardPlayer: UIViewRepresentable {
  let data: Data
  let bindings: [String: BindingValue]
  let revision: Int
  var onMetadataAction: (String) -> Void = { _ in }
  var onDiagnostics: (RemoteComposeNativePlayerDiagnostics) -> Void = { _ in }

  func makeCoordinator() -> Coordinator { Coordinator() }

  func makeUIView(context: Context) -> RemoteComposeNativePlayerView {
    let view = RemoteComposeNativePlayerView(
      data: data,
      background: .transparent,
      compatibilityPolicy: .compatible,
      androidCompatibility: .enabled,
      onEvent: handle,
      onDiagnostics: onDiagnostics)
    context.coordinator.didLoad(data)
    context.coordinator.apply(bindings, revision: revision, to: view)
    return view
  }

  func updateUIView(_ view: RemoteComposeNativePlayerView, context: Context) {
    view.onEvent = handle
    view.onDiagnostics = onDiagnostics
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

  final class Coordinator {
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
