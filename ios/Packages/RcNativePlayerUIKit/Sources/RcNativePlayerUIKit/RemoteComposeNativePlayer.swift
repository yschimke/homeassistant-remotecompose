#if canImport(UIKit)
  import Foundation
  #if canImport(RcNativePlayerCore)
    import RcNativePlayerCore
  #endif
  #if canImport(RcPlayerAppleFonts)
    import RcPlayerAppleFonts
  #endif
  import UIKit

  @MainActor
  private final class NativeDisplayLinkTarget: NSObject {
    weak var owner: RemoteComposeNativePlayerView?
    weak var displayLink: CADisplayLink?

    init(owner: RemoteComposeNativePlayerView) {
      self.owner = owner
    }

    @objc func fire() {
      guard let owner else {
        displayLink?.invalidate()
        return
      }
      owner.displayLinkDidFire()
    }
  }

  /// A UIKit-native Remote Compose player proof of concept.
  ///
  /// The wire decoder, retained state, lifecycle, component hierarchy, layout, and drawing are all
  /// implemented in Swift with UIKit, CoreGraphics, and CoreText.
  @MainActor
  public final class RemoteComposeNativePlayerViewController: UIViewController {
    public private(set) var playerView: RemoteComposeNativePlayerView

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
      clock: any RemoteComposeNativePlayerClock = RemoteComposeNativeSystemClock(),
      onEvent: @escaping (RemoteComposeNativePlayerEvent) -> Void = { _ in },
      onDiagnostics: @escaping (RemoteComposeNativePlayerDiagnostics) -> Void = { _ in }
    ) {
      let customComponents = customComponents ?? RemoteComposeNativeCustomComponentRegistry()
      playerView = RemoteComposeNativePlayerView(
        data: data, background: background, compatibilityPolicy: compatibilityPolicy,
        androidCompatibility: androidCompatibility,
        resourceLimits: resourceLimits, executionLimits: executionLimits,
        customComponents: customComponents,
        resourceResolver: resourceResolver,
        downloadableFontResolver: downloadableFontResolver,
        clock: clock,
        onEvent: onEvent,
        onDiagnostics: onDiagnostics)
      super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
      fatalError("init(coder:) is not supported")
    }

    public override func loadView() {
      view = playerView
    }

    public func load(_ data: Data) {
      playerView.load(data)
    }

    public func configureResources(
      limits: RemoteComposeNativeResourceLimits,
      resolver: (any RemoteComposeNativeResourceResolving)?
    ) {
      playerView.configureResources(limits: limits, resolver: resolver)
    }

    public func configureExecutionLimits(_ limits: RemoteComposeNativeExecutionLimits) {
      playerView.configureExecutionLimits(limits)
    }

    /// Select the density contract and retry the retained document when it changes.
    public func configureAndroidCompatibility(
      _ compatibility: RemoteComposeNativePlayerAndroidCompatibility
    ) {
      playerView.androidCompatibility = compatibility
    }

    /// Tell a deferred-density document what density this host plays at.
    public func configureHostDensity(_ density: Float, fontScale: Float = 1) {
      playerView.configureHostDensity(density, fontScale: fontScale)
    }

    public func configureDownloadableFonts(
      resolver: (any RemoteComposeDownloadableFontResolving)?
    ) {
      playerView.configureDownloadableFonts(resolver: resolver)
    }

    public func configureCustomComponents(
      _ registry: RemoteComposeNativeCustomComponentRegistry
    ) {
      playerView.configureCustomComponents(registry)
    }

    public func renderFrame(at timeSeconds: TimeInterval) {
      playerView.renderFrame(at: timeSeconds)
    }

    @discardableResult
    public func setFloat(_ value: Float, for name: String) async -> Bool {
      await playerView.setFloat(value, for: name)
    }

    @discardableResult
    public func setString(_ value: String, for name: String) async -> Bool {
      await playerView.setString(value, for: name)
    }

    @discardableResult
    public func setColor(_ argb: UInt32, for name: String) async -> Bool {
      await playerView.setColor(argb, for: name)
    }
  }

  public enum RemoteComposeNativePlayerBackground: Equatable, Sendable {
    case opaque
    case transparent
  }

  /// Root UIKit view. It can be embedded without SwiftUI or the supplied view controller.
  @MainActor
  public final class RemoteComposeNativePlayerView: UIView {
    private enum PendingWork {
      case documentLoad
      case frame
    }

    public var playerBackground: RemoteComposeNativePlayerBackground {
      didSet { applyBackground() }
    }

    public var compatibilityPolicy: RemoteComposeNativePlayerCompatibilityPolicy {
      didSet {
        guard compatibilityPolicy != oldValue, let documentData else { return }
        render(documentData)
      }
    }

    /// The density a deferred-density capture resolves against.
    ///
    /// A `RemoteDensity.Host` document computes its sizes from `ID_DENSITY`/`ID_FONT_SIZE` instead
    /// of folding a capture device's density in, so the player owes it a value. 1.0 is the default
    /// because that is what `androidCompatibility == .disabled` resolves dp geometry at; a host
    /// reproducing Android geometry sets its real playback density with
    /// `configureHostDensity(_:fontScale:)`.
    public private(set) var hostDensity: Float = 1
    public private(set) var hostFontScale: Float = 1

    /// Whether dp-typed geometry follows the document's Android density contract.
    ///
    /// Native rendering resolves dp geometry at density 1.0 by default and reports a document that
    /// expected otherwise. Changing this reloads the retained bytes, so a host can inspect the
    /// diagnostic first and then opt in.
    public var androidCompatibility: RemoteComposeNativePlayerAndroidCompatibility {
      didSet {
        guard androidCompatibility != oldValue, let documentData else { return }
        render(documentData)
      }
    }

    public var onDiagnostics: (RemoteComposeNativePlayerDiagnostics) -> Void
    public var onEvent: (RemoteComposeNativePlayerEvent) -> Void
    public private(set) var resourceLimits: RemoteComposeNativeResourceLimits
    public private(set) var executionLimits: RemoteComposeNativeExecutionLimits
    public private(set) var customComponents: RemoteComposeNativeCustomComponentRegistry
    private var customComponentsRevision: UInt
    public private(set) var resourceResolver: (any RemoteComposeNativeResourceResolving)?
    public private(set) var downloadableFontResolver:
      (any RemoteComposeDownloadableFontResolving)?
    private var documentView: NativeDocumentView?
    private var documentData: Data?
    private var loadTask: Task<Void, Never>?
    private var pendingWork: PendingWork?
    private var inputTail: Task<Void, Never>?
    private var loadGeneration: UInt64 = 0
    private var inputGeneration: UInt64 = 0
    private var lifecycleGeneration: UInt64 = 0
    private var sessionEpoch: UInt64 = 0
    private var retainedSessionEpoch: UInt64 = 0
    private var isApplicationActive = true
    private var needsForegroundRender = false
    private var isRenderingDocument = false
    private var needsRetry = false
    private var retainedSession: NativeSnapshotSessionHandle?
    private var retainedSessionData: Data?
    private var retainedResources: NativeResourceStore?
    private var currentFrameTime: TimeInterval = 0
    private var serializedInputCount = 0
    private var deferredFrameTime: TimeInterval?
    private let clock: any RemoteComposeNativePlayerClock
    private var animationTimeline = NativeAnimationTimeline()
    private var frameSchedule = NativeFrameSchedule.idle
    private var displayLink: CADisplayLink?
    private lazy var displayLinkTarget = NativeDisplayLinkTarget(owner: self)
    private var delayedWakeTask: Task<Void, Never>?
    private var wakeCountdown = NativeWakeCountdown()
    private var hasPendingScheduledFrame = false
    private var frameDriverGeneration: UInt64 = 0
    private var resourceCache: NativeImageCache
    private let errorLabel = UILabel()

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
      clock: any RemoteComposeNativePlayerClock = RemoteComposeNativeSystemClock(),
      onEvent: @escaping (RemoteComposeNativePlayerEvent) -> Void = { _ in },
      onDiagnostics: @escaping (RemoteComposeNativePlayerDiagnostics) -> Void = { _ in }
    ) {
      playerBackground = background
      self.compatibilityPolicy = compatibilityPolicy
      self.androidCompatibility = androidCompatibility
      self.resourceLimits = resourceLimits
      self.executionLimits = executionLimits
      let customComponents = customComponents ?? RemoteComposeNativeCustomComponentRegistry()
      self.customComponents = customComponents
      customComponentsRevision = customComponents.revision
      self.resourceResolver = resourceResolver
      self.downloadableFontResolver = downloadableFontResolver
      self.clock = clock
      resourceCache = NativeImageCache(
        countLimit: resourceLimits.maximumResourceCount,
        totalCostLimit: resourceLimits.maximumDecodedImageBytes)
      self.onEvent = onEvent
      self.onDiagnostics = onDiagnostics
      super.init(frame: .zero)
      isApplicationActive = UIApplication.shared.applicationState != .background
      animationTimeline.reset(
        at: clock.now(),
        active: isApplicationActive && window != nil && !UIAccessibility.isReduceMotionEnabled)
      isAccessibilityElement = false
      clipsToBounds = true
      configureErrorLabel()
      applyBackground()
      NotificationCenter.default.addObserver(
        self,
        selector: #selector(applicationDidEnterBackground),
        name: UIApplication.didEnterBackgroundNotification,
        object: nil)
      NotificationCenter.default.addObserver(
        self,
        selector: #selector(applicationDidBecomeActive),
        name: UIApplication.didBecomeActiveNotification,
        object: nil)
      NotificationCenter.default.addObserver(
        self,
        selector: #selector(reduceMotionStatusDidChange),
        name: UIAccessibility.reduceMotionStatusDidChangeNotification,
        object: nil)
      load(data)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
      fatalError("init(coder:) is not supported")
    }

    deinit {
      loadTask?.cancel()
      delayedWakeTask?.cancel()
      NotificationCenter.default.removeObserver(self)
    }

    public func load(_ data: Data) {
      if data == documentData, !needsRetry { return }
      if data != documentData {
        animationTimeline.reset(
          at: clock.now(),
          active: isApplicationActive && window != nil && !UIAccessibility.isReduceMotionEnabled)
      }
      loadTask?.cancel()
      documentData = data
      render(data)
    }

    /// Apply host-owned resource policy and retry the current document when it changes.
    public func configureResources(
      limits: RemoteComposeNativeResourceLimits,
      resolver: (any RemoteComposeNativeResourceResolving)?
    ) {
      let resolverChanged: Bool
      switch (resourceResolver, resolver) {
      case (nil, nil): resolverChanged = false
      case (let current?, let next?): resolverChanged = current !== next
      default: resolverChanged = true
      }
      let limitsChanged = limits != resourceLimits
      guard limitsChanged || resolverChanged else { return }
      loadTask?.cancel()
      loadGeneration &+= 1
      resourceLimits = limits
      resourceResolver = resolver
      resourceCache = NativeImageCache(
        countLimit: limits.maximumResourceCount,
        totalCostLimit: limits.maximumDecodedImageBytes)
      if let documentData { render(documentData) }
    }

    /// Apply native per-frame work limits and retry the current document when they change.
    public func configureExecutionLimits(_ limits: RemoteComposeNativeExecutionLimits) {
      guard limits != executionLimits else { return }
      executionLimits = limits
      if let documentData { render(documentData) }
    }

    /// Tell a deferred-density document what density this host plays at.
    ///
    /// Reloads the retained bytes, because the first frame is resolved as the session opens and a
    /// document that reads `ID_DENSITY` would otherwise keep the geometry it built from the old
    /// value. Non-finite and non-positive values are ignored by the core rather than stored.
    public func configureHostDensity(_ density: Float, fontScale: Float = 1) {
      let density = density.isFinite && density > 0 ? density : hostDensity
      let fontScale = fontScale.isFinite && fontScale > 0 ? fontScale : hostFontScale
      guard density != hostDensity || fontScale != hostFontScale else { return }
      hostDensity = density
      hostFontScale = fontScale
      if let documentData { render(documentData) }
    }

    /// Replace the opt-in network font source and retry the retained document when it changes.
    public func configureDownloadableFonts(
      resolver: (any RemoteComposeDownloadableFontResolving)?
    ) {
      let changed: Bool
      switch (downloadableFontResolver, resolver) {
      case (nil, nil): changed = false
      case (let current?, let next?): changed = current !== next
      default: changed = true
      }
      guard changed else { return }
      downloadableFontResolver = resolver
      if let documentData { render(documentData) }
    }

    /// Replace the host registry and retry the retained document against its declared names.
    public func configureCustomComponents(
      _ registry: RemoteComposeNativeCustomComponentRegistry
    ) {
      guard customComponents !== registry || customComponentsRevision != registry.revision else {
        return
      }
      customComponents = registry
      customComponentsRevision = registry.revision
      if let documentData { render(documentData) }
    }

    private func render(_ data: Data) {
      guard isApplicationActive else {
        needsForegroundRender = true
        return
      }
      needsRetry = false
      needsForegroundRender = false
      isRenderingDocument = true
      hasPendingScheduledFrame = false
      stopFrameDriver()
      loadTask?.cancel()
      inputTail = nil
      loadGeneration &+= 1
      inputGeneration &+= 1
      sessionEpoch &+= 1
      do {
        try NativeFrameBudget.validate(executionLimits)
        guard data.count <= executionLimits.maximumDocumentBytes else {
          throw RemoteComposeNativeLimitError.documentTooLarge(
            actual: data.count, maximum: executionLimits.maximumDocumentBytes)
        }
      } catch {
        show(error: error)
        return
      }
      let generation = loadGeneration
      pendingWork = .documentLoad
      let epoch = sessionEpoch
      let executionLimits = executionLimits
      let compatibilityPolicy = compatibilityPolicy
      let androidCompatibility = androidCompatibility
      let hostDensity = hostDensity
      let hostFontScale = hostFontScale
      let availableCustomComponents = customComponents.names
      let resourceLimits = resourceLimits
      let resourceResolver = resourceResolver
      let downloadableFontResolver = downloadableFontResolver
      let resourceCache = resourceCache
      let wallClock = clock.wallClock()
      loadTask = Task { [weak self] in
        do {
          let (session, frame) = try await NativeSnapshotSessionHandle.open(
            data: data, maximumDocumentBytes: executionLimits.maximumDocumentBytes,
            hostDensity: hostDensity, hostFontScale: hostFontScale,
            wallClock: wallClock)
          try Task.checkCancellation()
          let model = try NativeDocument(
            frame: frame, timeSeconds: 0, limits: executionLimits,
            androidCompatibility: androidCompatibility)
          guard generation == self?.loadGeneration else { return }
          let diagnostics = model.diagnostics(
            availableCustomComponents: availableCustomComponents)
          self?.onDiagnostics(diagnostics)
          if !RemoteComposeNativeCompatibilityDecision.shouldRender(
            policy: compatibilityPolicy, diagnostics: diagnostics)
          {
            throw RemoteComposeNativePlayerError.incompatible(diagnostics)
          }
          try Task.checkCancellation()
          let resources = try await Self.prepareResources(
            for: model, limits: resourceLimits, resolver: resourceResolver,
            downloadableFontResolver: downloadableFontResolver,
            cache: resourceCache)
          try Task.checkCancellation()
          guard let self, generation == self.loadGeneration else { return }
          self.retainedSession = session
          self.retainedSessionData = data
          self.retainedSessionEpoch = epoch
          self.currentFrameTime = 0
          try self.install(model, resources: resources)
        } catch let error as CancellationError {
          guard !Task.isCancelled, let self, generation == self.loadGeneration else { return }
          self.show(error: error)
        } catch {
          guard !Task.isCancelled, let self, generation == self.loadGeneration else { return }
          self.show(error: error)
        }
      }
    }

    /// Resolve another immutable frame from the retained runtime without decoding the document.
    public func renderFrame(at timeSeconds: TimeInterval) {
      guard isApplicationActive else { return }
      guard loadTask == nil else { return }
      guard !needsRetry, retainedSessionData == documentData else { return }
      guard serializedInputCount == 0 else {
        deferredFrameTime = timeSeconds
        return
      }
      guard let retainedSession, let retainedResources else { return }
      let frameTime = animationTimeline.advance(to: timeSeconds, at: clock.now())
      let wallClock = clock.wallClock()
      loadTask?.cancel()
      loadGeneration &+= 1
      let generation = loadGeneration
      pendingWork = .frame
      loadTask = Task { [weak self] in
        do {
          let frame = try await retainedSession.frame(
            at: frameTime, wallClock: wallClock)
          try Task.checkCancellation()
          guard let self, generation == self.loadGeneration else { return }
          let model = try NativeDocument(
            frame: frame, timeSeconds: frameTime, limits: self.executionLimits,
            androidCompatibility: self.androidCompatibility)
          try self.validate(model)
          try Task.checkCancellation()
          guard generation == self.loadGeneration else { return }
          self.currentFrameTime = frameTime
          try self.install(model, resources: retainedResources)
        } catch let error as CancellationError {
          guard !Task.isCancelled, let self, generation == self.loadGeneration else { return }
          self.show(error: error)
        } catch {
          guard !Task.isCancelled, let self, generation == self.loadGeneration else { return }
          self.show(error: error)
        }
      }
    }

    @discardableResult
    public func setFloat(_ value: Float, for name: String) async -> Bool {
      await updateSession { session, time in
        try await session.setFloat(value, for: name, at: time)
      }
    }

    @discardableResult
    public func setString(_ value: String, for name: String) async -> Bool {
      await updateSession { session, time in
        try await session.setString(value, for: name, at: time)
      }
    }

    @discardableResult
    public func setColor(_ argb: UInt32, for name: String) async -> Bool {
      await updateSession { session, time in
        try await session.setColor(argb, for: name, at: time)
      }
    }

    private func updateSession(
      _ operation:
        @escaping @Sendable (NativeSnapshotSessionHandle, TimeInterval) async throws ->
        NativeSnapshotSessionHandle.Update
    ) async -> Bool {
      guard
        isApplicationActive, retainedSessionEpoch == sessionEpoch,
        let retainedSession
      else { return false }
      serializedInputCount += 1
      defer { finishSerializedInput() }
      if let pendingFrame = loadTask { await pendingFrame.value }
      guard
        isApplicationActive, retainedSessionEpoch == sessionEpoch,
        self.retainedSession === retainedSession, let retainedResources
      else { return false }
      let epoch = sessionEpoch
      let lifecycle = lifecycleGeneration
      let time = currentFrameTime
      let (input, task) = enqueueInput(lifecycle: lifecycle) {
        try await operation(retainedSession, time)
      }
      switch await task.value {
      case .success(let update):
        guard
          isApplicationActive, lifecycle == lifecycleGeneration,
          epoch == sessionEpoch, retainedSessionEpoch == epoch,
          self.retainedSession === retainedSession
        else { return update.accepted }
        do {
          let model = try NativeDocument(
            frame: update.frame, timeSeconds: time, limits: executionLimits,
            androidCompatibility: androidCompatibility)
          try validateExecution(model, events: update.events)
          guard
            isApplicationActive, lifecycle == lifecycleGeneration,
            epoch == sessionEpoch, retainedSessionEpoch == epoch,
            self.retainedSession === retainedSession
          else { return update.accepted }
          guard input == inputGeneration else {
            dispatch(
              update.events, from: retainedSession, epoch: epoch, lifecycle: lifecycle)
            return update.accepted
          }
          try validate(model)
          guard
            input == inputGeneration, epoch == sessionEpoch, retainedSessionEpoch == epoch,
            lifecycle == lifecycleGeneration, isApplicationActive,
            self.retainedSession === retainedSession
          else { return update.accepted }
          try install(model, resources: retainedResources)
          dispatch(
            update.events, from: retainedSession, epoch: epoch, lifecycle: lifecycle)
          return update.accepted
        } catch {
          guard
            isApplicationActive, lifecycle == lifecycleGeneration,
            epoch == sessionEpoch, retainedSessionEpoch == epoch,
            self.retainedSession === retainedSession
          else { return false }
          show(error: error)
          return false
        }
      case .failure(let error):
        guard
          isApplicationActive, lifecycle == lifecycleGeneration, epoch == sessionEpoch
        else { return false }
        if !(error is CancellationError) { show(error: error) }
        return false
      }
    }

    private func finishSerializedInput() {
      serializedInputCount -= 1
      guard serializedInputCount == 0 else { return }
      if let deferredFrameTime {
        self.deferredFrameTime = nil
        renderFrame(at: deferredFrameTime)
      } else if hasPendingScheduledFrame {
        requestScheduledFrame()
      }
    }

    private func enqueueInput(
      lifecycle: UInt64,
      _ operation: @escaping @Sendable () async throws -> NativeSnapshotSessionHandle.Update
    ) -> (UInt64, Task<Result<NativeSnapshotSessionHandle.Update, any Error>, Never>) {
      inputGeneration &+= 1
      let input = inputGeneration
      let previous = inputTail
      let task = Task<Result<NativeSnapshotSessionHandle.Update, any Error>, Never> { [weak self] in
        await previous?.value
        do {
          try Task.checkCancellation()
          guard
            self?.isApplicationActive == true, lifecycle == self?.lifecycleGeneration
          else { throw CancellationError() }
          return .success(try await operation())
        } catch {
          return .failure(error)
        }
      }
      inputTail = Task { _ = await task.value }
      return (input, task)
    }

    private func dispatch(
      _ events: [RemoteComposeNativePlayerEvent],
      from session: NativeSnapshotSessionHandle,
      epoch: UInt64,
      lifecycle: UInt64
    ) {
      for event in events {
        guard
          isApplicationActive, lifecycle == lifecycleGeneration,
          epoch == sessionEpoch, retainedSessionEpoch == epoch,
          retainedSession === session
        else { return }
        onEvent(event)
      }
    }

    private func validate(_ model: NativeDocument) throws {
      let diagnostics = model.diagnostics(availableCustomComponents: customComponents.names)
      onDiagnostics(diagnostics)
      if !RemoteComposeNativeCompatibilityDecision.shouldRender(
        policy: compatibilityPolicy, diagnostics: diagnostics)
      {
        throw RemoteComposeNativePlayerError.incompatible(diagnostics)
      }
    }

    private func validateExecution(
      _ model: NativeDocument, events: [RemoteComposeNativePlayerEvent]
    ) throws {
      var budget = model.executionBudget
      for event in events {
        switch event {
        case .action:
          try budget.recordEvent(limits: executionLimits)
        case .actionWithMetadata(_, let metadata):
          try budget.recordEvent(strings: [metadata], limits: executionLimits)
        case .namedAction(let name, let value):
          switch value {
          case .none, .float, .integer:
            try budget.recordEvent(strings: [name], limits: executionLimits)
          case .text(let text):
            try budget.recordEvent(strings: [name, text], limits: executionLimits)
          case .floatList(let values):
            try budget.recordEvent(
              additionalWork: values.count, strings: [name], limits: executionLimits)
          }
        case .debug(let message, _, _):
          try budget.recordEvent(strings: [message], limits: executionLimits)
        }
      }
    }

    private static func prepareResources(
      for model: NativeDocument,
      limits: RemoteComposeNativeResourceLimits,
      resolver: (any RemoteComposeNativeResourceResolving)?,
      downloadableFontResolver: (any RemoteComposeDownloadableFontResolving)?,
      cache: NativeImageCache
    ) async throws -> NativeResourceStore {
      let resources = try NativeResourceStore(
        resources: model.images,
        fonts: model.fonts,
        limits: limits,
        cache: cache)
      if !resources.unresolvedImages.isEmpty {
        guard let resolver else {
          throw RemoteComposeNativeResourceError.unresolvedReference(
            id: resources.unresolvedImages[0].id)
        }
        for request in resources.unresolvedImages {
          let resolved = try await resolver.resolve(request)
          try Task.checkCancellation()
          try resources.insertResolved(data: resolved, for: request)
        }
      }
      if !model.downloadableFonts.isEmpty {
        if let downloadableFontResolver {
          for request in model.downloadableFonts {
            let font = try await downloadableFontResolver.resolve(
              RemoteComposeDownloadableFontRequest(family: request.family))
            guard font.family.caseInsensitiveCompare(request.family) == .orderedSame else {
              throw RemoteComposeDownloadableFontError.familyMismatch(
                expected: request.family, actual: font.family)
            }
            try Task.checkCancellation()
            try resources.insertDownloadedFont(data: font.data, id: request.id)
          }
        }
      }
      return resources
    }

    private func install(_ model: NativeDocument, resources: NativeResourceStore) throws {
      try resources.activateFonts(replacing: retainedResources)
      isRenderingDocument = false
      if documentView?.update(
        document: model, resources: resources, customComponents: customComponents) != true
      {
        let nextView = NativeDocumentView(
          document: model,
          resources: resources,
          customComponents: customComponents,
          onGesture: { [weak self] componentID, gesture, sample in
            self?.performGesture(gesture, componentID: componentID, sample: sample)
          },
          onCustomReturn: { [weak self] componentID, propertyID, value in
            self?.performCustomReturn(
              componentID: componentID, propertyID: propertyID, value: value)
          })
        replaceDocumentView(with: nextView)
      }
      retainedResources = resources
      frameSchedule = model.frameSchedule
      wakeCountdown.reset(after: frameSchedule.wakeAfter)
      needsRetry = false
      errorLabel.isHidden = true
      loadTask = nil
      pendingWork = nil
      updateFrameDriver()
      if hasPendingScheduledFrame { requestScheduledFrame() }
    }

    private func show(error: Error) {
      isRenderingDocument = false
      stopFrameDriver()
      needsRetry = true
      errorLabel.text = error.localizedDescription
      errorLabel.isHidden = false
      loadTask = nil
      pendingWork = nil
      hasPendingScheduledFrame = false
    }

    private func performGesture(
      _ gesture: NativeSwiftGestureKind, componentID: Int,
      sample: NativeSwiftPointerSample?
    ) {
      Task { [weak self] in
        guard let self else { return }
        _ = await self.updateSession { session, time in
          try await session.gesture(
            gesture, componentID: componentID, sample: sample, at: time)
        }
      }
    }

    private func performCustomReturn(
      componentID: Int, propertyID: Int, value: NativeCustomReturnValue
    ) {
      Task { [weak self] in
        guard let self else { return }
        _ = await self.updateSession { session, time in
          switch value {
          case .float(let value):
            try await session.returnCustomFloat(
              value, componentID: componentID, propertyID: propertyID, at: time)
          case .text(let value):
            try await session.returnCustomText(
              value, componentID: componentID, propertyID: propertyID, at: time)
          }
        }
      }
    }

    public override func layoutSubviews() {
      super.layoutSubviews()
      documentView?.frame = bounds
      errorLabel.frame = bounds.insetBy(dx: 24, dy: 24)
    }

    public override func didMoveToWindow() {
      super.didMoveToWindow()
      if window == nil {
        animationTimeline.pause(at: clock.now())
      } else if isApplicationActive {
        if UIAccessibility.isReduceMotionEnabled {
          animationTimeline.pause(at: clock.now())
        } else {
          animationTimeline.resume(at: clock.now())
        }
      }
      updateFrameDriver()
    }

    private func replaceDocumentView(with nextView: NativeDocumentView) {
      documentView?.removeFromSuperview()
      insertSubview(nextView, belowSubview: errorLabel)
      documentView = nextView
      setNeedsLayout()
    }

    private func configureErrorLabel() {
      errorLabel.numberOfLines = 0
      errorLabel.textAlignment = .center
      errorLabel.textColor = .secondaryLabel
      errorLabel.isHidden = true
      addSubview(errorLabel)
    }

    private func applyBackground() {
      let opaque = playerBackground == .opaque
      isOpaque = opaque
      layer.isOpaque = opaque
      backgroundColor = opaque ? .systemBackground : .clear
    }

    private func updateFrameDriver() {
      let now = clock.now()
      wakeCountdown.pause(at: now)
      frameDriverGeneration &+= 1
      delayedWakeTask?.cancel()
      delayedWakeTask = nil
      let mode = frameSchedule.driverMode(
        isActive: isApplicationActive,
        isVisible: window != nil,
        reduceMotion: UIAccessibility.isReduceMotionEnabled)
      switch mode {
      case .idle:
        displayLink?.invalidate()
        displayLink = nil
      case .displayLink:
        guard displayLink == nil else { return }
        let link = CADisplayLink(
          target: displayLinkTarget, selector: #selector(NativeDisplayLinkTarget.fire))
        displayLinkTarget.displayLink = link
        link.add(to: .main, forMode: .common)
        displayLink = link
      case .wake(let delay):
        displayLink?.invalidate()
        displayLink = nil
        let generation = frameDriverGeneration
        let remainingDelay = wakeCountdown.start(after: delay, at: now)
        delayedWakeTask = Task { [weak self] in
          let maximumDelay = TimeInterval(UInt64.max / 1_000_000_000)
          let nanoseconds = UInt64(min(remainingDelay, maximumDelay) * 1_000_000_000)
          try? await Task.sleep(nanoseconds: nanoseconds)
          guard !Task.isCancelled, let self, generation == self.frameDriverGeneration else {
            return
          }
          self.delayedWakeTask = nil
          self.requestScheduledFrame()
        }
      }
    }

    private func stopFrameDriver() {
      wakeCountdown.pause(at: clock.now())
      frameDriverGeneration &+= 1
      delayedWakeTask?.cancel()
      delayedWakeTask = nil
      displayLink?.invalidate()
      displayLink = nil
    }

    fileprivate func displayLinkDidFire() {
      if frameSchedule.requestsNextFrame, !frameSchedule.needsContinuousFrames {
        displayLink?.invalidate()
        displayLink = nil
      }
      requestScheduledFrame()
    }

    private func requestScheduledFrame() {
      guard isApplicationActive, window != nil else { return }
      guard serializedInputCount == 0 else {
        hasPendingScheduledFrame = true
        return
      }
      guard loadTask == nil else {
        hasPendingScheduledFrame = true
        return
      }
      hasPendingScheduledFrame = false
      let now = clock.now()
      let time: TimeInterval
      if UIAccessibility.isReduceMotionEnabled {
        time = animationTimeline.sampleFunctional(at: now)
      } else {
        time = animationTimeline.sample(at: now)
      }
      renderFrame(at: time)
    }

    @objc private func reduceMotionStatusDidChange() {
      if UIAccessibility.isReduceMotionEnabled {
        animationTimeline.pause(at: clock.now())
      } else if isApplicationActive, window != nil {
        animationTimeline.resume(at: clock.now())
      }
      updateFrameDriver()
    }

    @objc private func applicationDidEnterBackground() {
      isApplicationActive = false
      needsForegroundRender =
        (isRenderingDocument || serializedInputCount > 0) && documentData != nil
      isRenderingDocument = false
      animationTimeline.pause(at: clock.now())
      stopFrameDriver()
      loadTask?.cancel()
      loadTask = nil
      pendingWork = nil
      loadGeneration &+= 1
      inputGeneration &+= 1
      lifecycleGeneration &+= 1
      hasPendingScheduledFrame = false
    }

    @objc private func applicationDidBecomeActive() {
      isApplicationActive = true
      if UIAccessibility.isReduceMotionEnabled {
        animationTimeline.pause(at: clock.now())
      } else {
        animationTimeline.resume(at: clock.now())
      }
      if needsForegroundRender, let documentData {
        render(documentData)
      } else {
        updateFrameDriver()
      }
    }
  }
#endif
