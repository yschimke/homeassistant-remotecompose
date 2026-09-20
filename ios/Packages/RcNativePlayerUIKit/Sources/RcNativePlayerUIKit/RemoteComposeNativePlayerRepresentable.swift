#if canImport(UIKit) && canImport(SwiftUI)
  #if canImport(RcPlayerAppleFonts)
    import RcPlayerAppleFonts
  #endif
  import SwiftUI

  /// Optional SwiftUI host adapter. Rendering remains entirely UIKit/Core Graphics.
  @MainActor
  public struct RemoteComposeNativePlayerRepresentable: UIViewRepresentable {
    public let data: Data
    public var background: RemoteComposeNativePlayerBackground
    public var compatibilityPolicy: RemoteComposeNativePlayerCompatibilityPolicy
    public var androidCompatibility: RemoteComposeNativePlayerAndroidCompatibility
    public var resourceLimits: RemoteComposeNativeResourceLimits
    public var executionLimits: RemoteComposeNativeExecutionLimits
    public var customComponents: RemoteComposeNativeCustomComponentRegistry?
    public var resourceResolver: (any RemoteComposeNativeResourceResolving)?
    public var downloadableFontResolver: (any RemoteComposeDownloadableFontResolving)?
    public var onEvent: (RemoteComposeNativePlayerEvent) -> Void
    public var onDiagnostics: (RemoteComposeNativePlayerDiagnostics) -> Void

    public init(
      data: Data,
      background: RemoteComposeNativePlayerBackground = .opaque,
      compatibilityPolicy: RemoteComposeNativePlayerCompatibilityPolicy = .compatible,
      androidCompatibility: RemoteComposeNativePlayerAndroidCompatibility = .disabled,
      resourceLimits: RemoteComposeNativeResourceLimits = .default,
      executionLimits: RemoteComposeNativeExecutionLimits = .default,
      customComponents: RemoteComposeNativeCustomComponentRegistry? = nil,
      resourceResolver: (any RemoteComposeNativeResourceResolving)? = nil,
      downloadableFontResolver: (any RemoteComposeDownloadableFontResolving)? = nil,
      onEvent: @escaping (RemoteComposeNativePlayerEvent) -> Void = { _ in },
      onDiagnostics: @escaping (RemoteComposeNativePlayerDiagnostics) -> Void = { _ in }
    ) {
      self.data = data
      self.background = background
      self.compatibilityPolicy = compatibilityPolicy
      self.androidCompatibility = androidCompatibility
      self.resourceLimits = resourceLimits
      self.executionLimits = executionLimits
      self.customComponents = customComponents
      self.resourceResolver = resourceResolver
      self.downloadableFontResolver = downloadableFontResolver
      self.onEvent = onEvent
      self.onDiagnostics = onDiagnostics
    }

    public func makeUIView(context: Context) -> RemoteComposeNativePlayerView {
      RemoteComposeNativePlayerView(
        data: data, background: background, compatibilityPolicy: compatibilityPolicy,
        androidCompatibility: androidCompatibility,
        resourceLimits: resourceLimits, executionLimits: executionLimits,
        customComponents: customComponents ?? .init(),
        resourceResolver: resourceResolver,
        downloadableFontResolver: downloadableFontResolver,
        onEvent: onEvent,
        onDiagnostics: onDiagnostics)
    }

    public func updateUIView(_ view: RemoteComposeNativePlayerView, context: Context) {
      view.playerBackground = background
      view.onEvent = onEvent
      view.onDiagnostics = onDiagnostics
      view.compatibilityPolicy = compatibilityPolicy
      view.androidCompatibility = androidCompatibility
      view.configureResources(limits: resourceLimits, resolver: resourceResolver)
      view.configureDownloadableFonts(resolver: downloadableFontResolver)
      view.configureExecutionLimits(executionLimits)
      if let customComponents { view.configureCustomComponents(customComponents) }
      view.load(data)
    }
  }
#endif
