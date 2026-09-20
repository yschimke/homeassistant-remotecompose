#if canImport(UIKit)
  import CoreText
  #if canImport(RcNativePlayerCore)
    import RcNativePlayerCore
  #endif
  #if canImport(RcPlayerAppleFonts)
    import RcPlayerAppleFonts
  #endif
  import UIKit

  struct NativeDocument {
    let size: CGSize
    let density: CGFloat
    let densityBehavior: Int
    let androidCompatibility: RemoteComposeNativePlayerAndroidCompatibility
    let root: NativeNode
    let images: [NativeImageResource]
    let fonts: [NativeFontResource]
    let downloadableFonts: [NativeDownloadableFont]
    let diagnostics: RemoteComposeNativePlayerDiagnostics
    let rootSizing: Int
    let rootMode: Int
    let rootAlignment: Int
    let frameSchedule: NativeFrameSchedule
    let executionBudget: NativeFrameBudget
    /// Components whose measured geometry the document binds to a float. Empty for almost every
    /// document; when it is not, the root view reports their real sizes back for a second pass.
    let boundComponents: Set<Int>
    /// Set only when the document binds component geometry. See `refined(measuredComponents:)`.
    private(set) var refiner: NativeSwiftDocumentSession?
    /// The frame time this document was resolved at.
    ///
    /// A refinement re-resolves the session against real geometry, and it must do so at the same
    /// instant the frame it is refining was taken at — otherwise an animated document's refinement
    /// snaps back to zero, and a player that refines after every layout pass then alternates
    /// between the animated frame and a static one.
    let timeSeconds: TimeInterval
    /// The host's absolute time at that instant, for the same reason: a calendar field must not
    /// jump when the refinement re-resolves.
    let wallClock: NativeSwiftWallClock?
    private let limits: RemoteComposeNativeExecutionLimits

    init(
      frame: NativeSnapshotSessionHandle.Frame, timeSeconds: TimeInterval,
      limits: RemoteComposeNativeExecutionLimits,
      androidCompatibility: RemoteComposeNativePlayerAndroidCompatibility = .disabled
    ) throws {
      try self.init(
        swiftSnapshot: frame.snapshot, timeSeconds: timeSeconds, wallClock: frame.wallClock,
        limits: limits, androidCompatibility: androidCompatibility)
      refiner = frame.refiner
    }

    /// This document re-resolved against what the host actually laid its components out at.
    ///
    /// Returns nil when there is nothing to re-resolve, or when the refined snapshot fails a budget
    /// check the first one passed — a refinement is an improvement, never a reason to fail a
    /// document that already rendered.
    func refined(measuredComponents: [Int: NativeSwiftMeasuredSize]) -> NativeDocument? {
      guard let refiner, !measuredComponents.isEmpty else { return nil }
      guard
        let snapshot = try? refiner.snapshot(
          timeSeconds: timeSeconds, wallClock: wallClock, measuredComponents: measuredComponents),
        var document = try? NativeDocument(
          swiftSnapshot: snapshot, timeSeconds: timeSeconds, wallClock: wallClock, limits: limits,
          androidCompatibility: androidCompatibility)
      else { return nil }
      document.refiner = refiner
      return document
    }

    private init(
      swiftSnapshot: NativeSwiftDocumentSnapshot, timeSeconds: TimeInterval,
      wallClock: NativeSwiftWallClock?, limits: RemoteComposeNativeExecutionLimits,
      androidCompatibility: RemoteComposeNativePlayerAndroidCompatibility
    ) throws {
      self.timeSeconds = timeSeconds
      self.wallClock = wallClock
      try NativeFrameBudget.validate(limits)
      var budget = NativeFrameBudget()
      var downloadableByID: [Int: NativeDownloadableFont] = [:]
      try budget.validateDocumentDimensions(
        [Double(swiftSnapshot.width), Double(swiftSnapshot.height)], limits: limits)
      var pending: [(NativeSwiftNodeSnapshot, Int)] = [(swiftSnapshot.root, 1)]
      while let (node, depth) = pending.popLast() {
        try budget.recordNode(depth: depth, limits: limits)
        try budget.validateLayoutDimension(
          value: Double(node.widthValue), type: node.widthType, componentID: node.componentID,
          field: "width", limits: limits)
        try budget.validateLayoutDimension(
          value: Double(node.heightValue), type: node.heightType, componentID: node.componentID,
          field: "height", limits: limits)
        try budget.validateNumbers(
          [
            node.padding.left, node.padding.top, node.padding.right, node.padding.bottom,
            node.spacing,
          ]
          .map(Double.init), componentID: node.componentID, field: "layout", limits: limits)
        if let text = node.text {
          try budget.recordCommand(pathElementCount: 0, strings: [text.value], limits: limits)
          try budget.validateFinite(
            [text.size, text.weight].map(Double.init), componentID: node.componentID,
            field: "text")
          if let family = text.familyName?.trimmingCharacters(in: .whitespacesAndNewlines),
            family.lowercased().hasPrefix("google:")
          {
            let name = String(family.dropFirst("google:".count)).trimmingCharacters(
              in: .whitespacesAndNewlines)
            if !name.isEmpty {
              downloadableByID[text.familyID] = NativeDownloadableFont(id: text.familyID, family: name)
            }
          }
        }
        for command in node.commands {
          try budget.recordCommand(pathElementCount: command.path.count, limits: limits)
          try budget.validateNumbers(
            command.values.map(Double.init) + [Double(command.strokeWidth), Double(command.alpha)]
              + command.path.flatMap { $0.values.map(Double.init) },
            componentID: node.componentID, field: "draw", limits: limits)
        }
        if let custom = node.custom {
          try budget.recordWork(custom.properties.count, limits: limits)
          try budget.recordStrings(
            [Optional(custom.config)] + custom.properties.map(\.textValue), limits: limits)
        }
        pending.append(contentsOf: node.children.map { ($0, depth + 1) })
      }
      executionBudget = budget
      boundComponents = swiftSnapshot.boundComponents
      self.limits = limits
      density = CGFloat(swiftSnapshot.density)
      guard density.isFinite, density > 0 else {
        throw RemoteComposeNativePlayerError.decode("Document density must be finite and positive")
      }
      densityBehavior = swiftSnapshot.densityBehavior
      self.androidCompatibility = androidCompatibility
      size = CGSize(width: swiftSnapshot.width, height: swiftSnapshot.height)
      root = NativeNode(
        swiftSnapshot: swiftSnapshot.root,
        densityBehavior: swiftSnapshot.densityBehavior)
      images = swiftSnapshot.images.map {
        NativeImageResource(
          id: $0.id, width: $0.width, height: $0.height, type: $0.type,
          encoding: $0.encoding, data: $0.data)
      }
      fonts = []
      downloadableFonts = downloadableByID.values.sorted { $0.id < $1.id }
      diagnostics = RemoteComposeNativePlayerDiagnostics(
        issues: NativeDensityPolicy.diagnostics(
          density: swiftSnapshot.density,
          densityBehavior: swiftSnapshot.densityBehavior,
          androidCompatibility: androidCompatibility,
          componentID: swiftSnapshot.root.componentID),
        unsupportedOpcodes: [], notes: [])
      rootSizing = 2
      rootMode = 4
      rootAlignment = 34
      frameSchedule = NativeFrameSchedule(
        needsContinuousFrames: swiftSnapshot.needsContinuousFrames,
        requestsNextFrame: false,
        // A document that reads a discrete wall-clock field has to be re-resolved at least once a
        // second, or its clock freezes on the first frame; the driver re-arms this after each wake.
        wakeAfter: swiftSnapshot.needsWallClockRefresh ? 1 : nil)
    }

    func diagnostics(availableCustomComponents: Set<String>)
      -> RemoteComposeNativePlayerDiagnostics
    {
      var issues = diagnostics.issues
      var pending = [root]
      while let node = pending.popLast() {
        if let custom = node.custom, !availableCustomComponents.contains(custom.config) {
          issues.append(
            RemoteComposeNativePlayerDiagnostic(
              severity: .unsupported, opcode: 93, operationName: "Custom",
              componentID: node.componentID,
              reason: "Host custom component '\(custom.config)' is not registered"))
        }
        pending.append(contentsOf: node.children)
      }
      return RemoteComposeNativePlayerDiagnostics(
        issues: issues,
        unsupportedOpcodes: Array(
          Set(
            diagnostics.unsupportedOpcodes + (issues.count > diagnostics.issues.count ? [93] : []))
        ).sorted(),
        notes: diagnostics.notes)
    }

  }

  struct NativeImageResource {
    let id: Int
    let width: Int
    let height: Int
    let type: Int
    let encoding: Int
    let data: Data

  }

  struct NativeFontResource {
    let id: Int
    let type: Int
    let data: Data

  }

  struct NativeDownloadableFont {
    let id: Int
    let family: String
  }

  private struct NativeSemanticBehavior {
    let descriptor: NativeAccessibilityDescriptor
    let componentID: Int
    let clickActionTypes: [Int]
    let acceptsPointerAction: Bool
  }

  struct NativeNode {
    enum Kind: Equatable {
      case root
      case content
      case canvas
      case group
      case box
      case row
      case column
      case text
      case image
      case custom

      init(rawValue: Int32) {
        switch rawValue {
        case 0: self = .root
        case 1: self = .content
        case 2: self = .canvas
        case 4: self = .box
        case 5: self = .row
        case 6: self = .column
        case 7: self = .text
        case 8: self = .image
        case 9: self = .custom
        default: self = .group
        }
      }
    }

    let kind: Kind
    let componentID: Int
    let commands: [NativeDrawCommand]
    let children: [NativeNode]
    let semanticRole: Int
    let isClickable: Bool
    let isEnabled: Bool
    let accessibilityLabel: String?
    let accessibilityText: String?
    let accessibilityValue: String?
    let accessibilityMode: NativeAccessibilityMode
    let hasAccessibilitySemantics: Bool
    let clickActionTypes: [Int]
    let gestureTypes: [NativeSwiftGestureKind]
    let widthType: Int
    let widthValue: CGFloat
    let heightType: Int
    let heightValue: CGFloat
    let minimumHeight: CGFloat
    let minimumWidth: CGFloat
    let maximumWidth: CGFloat?
    let maximumHeight: CGFloat?
    let padding: UIEdgeInsets
    let cornerRadius: CGFloat
    let clipsToBounds: Bool
    let graphicsLayer: NativeSwiftGraphicsLayerSnapshot?
    let backgroundColor: UIColor?
    let horizontalPositioning: Int
    let verticalPositioning: Int
    let spacing: CGFloat
    /// Set on `FlowLayout`: children wrap onto further lines, bounded by these. Nil for every other
    /// container.
    let flowMaximumItems: Int?
    let flowMaximumLines: Int?
    /// Set on the collapsible row/column family; see `NativeSwiftCollapsible`.
    let isCollapsible: Bool
    let collapsiblePriority: CGFloat?
    let collapsiblePriorityOrientation: Int?
    let offset: CGPoint
    let zIndex: CGFloat
    let visibility: Int
    let custom: NativeCustomComponent?
    let densityBehavior: Int
    /// The AndroidX class name of the operation that produced this node — `BoxLayout`, `CoreText`,
    /// `FitBoxLayout`. Read by the `FitBox` layout, which is the one class that does not behave like
    /// the box it decodes as; empty for a structural content wrapper.
    let componentKind: String
    /// Set on a component with a scroll modifier: 0 vertical, 1 horizontal. The children of a
    /// scrolled container are laid out against their content, not against the viewport that clips
    /// them, so this is what decides a container's layout space.
    let scrollDirection: Int?

    init(swiftSnapshot snapshot: NativeSwiftNodeSnapshot, densityBehavior: Int) {
      switch snapshot.kind {
      case .root: kind = .root
      case .content: kind = .content
      case .canvas: kind = .canvas
      case .box: kind = .box
      case .row: kind = .row
      case .column: kind = .column
      case .text: kind = .text
      case .image: kind = .image
      case .custom: kind = .custom
      }
      componentID = snapshot.componentID
      componentKind = snapshot.componentKind
      scrollDirection = snapshot.scrollDirection
      commands =
        snapshot.commands.map(NativeDrawCommand.init)
        + (snapshot.text.map { [NativeDrawCommand(text: $0)] } ?? [])
      children = snapshot.children.map {
        NativeNode(swiftSnapshot: $0, densityBehavior: densityBehavior)
      }
      semanticRole = snapshot.accessibility?.role ?? (snapshot.isClickable ? 0 : -1)
      isClickable = snapshot.isClickable || snapshot.accessibility?.isClickable == true
      isEnabled = snapshot.accessibility?.isEnabled ?? true
      accessibilityLabel = snapshot.accessibility?.contentDescription
      accessibilityText = snapshot.accessibility?.text ?? snapshot.text?.value
      accessibilityValue = snapshot.accessibility?.stateDescription
      accessibilityMode =
        snapshot.accessibility.flatMap { NativeAccessibilityMode(rawValue: $0.mode) } ?? .set
      hasAccessibilitySemantics = snapshot.accessibility != nil
      clickActionTypes = isEnabled ? snapshot.supportedGestures.map(\.rawValue) : []
      gestureTypes = snapshot.supportedGestures
      widthType = snapshot.widthType
      widthValue = CGFloat(snapshot.widthValue)
      heightType = snapshot.heightType
      heightValue = CGFloat(snapshot.heightValue)
      minimumHeight = CGFloat(snapshot.minimumHeight)
      minimumWidth = CGFloat(snapshot.minimumWidth)
      maximumWidth = snapshot.maximumWidth < 0 ? nil : CGFloat(snapshot.maximumWidth)
      maximumHeight = snapshot.maximumHeight < 0 ? nil : CGFloat(snapshot.maximumHeight)
      padding = UIEdgeInsets(
        top: CGFloat(snapshot.padding.top), left: CGFloat(snapshot.padding.left),
        bottom: CGFloat(snapshot.padding.bottom), right: CGFloat(snapshot.padding.right))
      cornerRadius = CGFloat(snapshot.cornerRadius)
      clipsToBounds = snapshot.clipsToBounds
      graphicsLayer = snapshot.graphicsLayer
      backgroundColor = snapshot.backgroundARGB.map(UIColor.init(remoteComposeARGB:))
      horizontalPositioning = snapshot.horizontalPositioning
      verticalPositioning = snapshot.verticalPositioning
      spacing = CGFloat(snapshot.spacing)
      flowMaximumItems = snapshot.flowMaximumItems
      flowMaximumLines = snapshot.flowMaximumLines
      isCollapsible = snapshot.isCollapsible
      collapsiblePriority = snapshot.collapsiblePriority.map(CGFloat.init)
      collapsiblePriorityOrientation = snapshot.collapsiblePriorityOrientation
      offset = CGPoint(x: CGFloat(snapshot.offsetX), y: CGFloat(snapshot.offsetY))
      zIndex = CGFloat(snapshot.zIndex)
      visibility = snapshot.visibility
      custom = snapshot.custom.map(NativeCustomComponent.init)
      self.densityBehavior = densityBehavior
    }

    var firstText: String? {
      commands.lazy.compactMap(\.text).first ?? children.lazy.compactMap(\.firstText).first
    }

    var localAccessibilityLabels: [String] {
      [accessibilityLabel, accessibilityText].compactMap { $0 }
        + commands.flatMap { command in
          [command.text, command.image?.contentDescription].compactMap { $0 }
        }
    }

    var accessibilityDescriptor: NativeAccessibilityDescriptor? {
      guard hasAccessibilitySemantics || isClickable else { return nil }
      return NativeAccessibilityDescriptor(
        role: NativeAccessibilityRole(rawValue: semanticRole),
        mode: accessibilityMode,
        contentDescription: accessibilityLabel,
        text: accessibilityText,
        stateDescription: accessibilityValue,
        isEnabled: isEnabled,
        isClickable: isClickable)
    }

    var effectiveAccessibilityLabels: [String] {
      guard visibility == 1 else { return [] }
      let descendants = children.flatMap(\.effectiveAccessibilityLabels)
      guard let descriptor = accessibilityDescriptor else {
        return localAccessibilityLabels + descendants
      }
      switch descriptor.mode {
      case .clearAndSet:
        return [descriptor.resolvedLabel(descendantLabels: [])].compactMap { $0 }
      case .merge:
        return [
          descriptor.resolvedLabel(
            descendantLabels: localAccessibilityLabels + descendants)
        ].compactMap { $0 }
      case .set:
        return [descriptor.resolvedLabel(descendantLabels: [])].compactMap { $0 }
          + localAccessibilityLabels + descendants
      }
    }

    var descendantAccessibilityLabels: [String] {
      children.flatMap(\.effectiveAccessibilityLabels)
    }

    fileprivate var semanticBehavior: NativeSemanticBehavior? {
      guard let own = accessibilityDescriptor else { return nil }
      guard own.mode == .merge else {
        return NativeSemanticBehavior(
          descriptor: own, componentID: componentID, clickActionTypes: clickActionTypes,
          acceptsPointerAction: !clickActionTypes.isEmpty)
      }
      let descendants = children.flatMap(\.effectiveSemanticBehaviors)
      let mergedDescriptor = descendants.reduce(own) { descriptor, descendant in
        descriptor.mergingBehavior(from: descendant.descriptor)
      }
      let ownsAction = !clickActionTypes.isEmpty
      let descendantAction = descendants.first { !$0.clickActionTypes.isEmpty }
      return NativeSemanticBehavior(
        descriptor: mergedDescriptor,
        componentID: ownsAction ? componentID : descendantAction?.componentID ?? componentID,
        clickActionTypes: ownsAction ? clickActionTypes : descendantAction?.clickActionTypes ?? [],
        acceptsPointerAction: ownsAction)
    }

    private var effectiveSemanticBehaviors: [NativeSemanticBehavior] {
      guard visibility == 1 else { return [] }
      if let semanticBehavior {
        if semanticBehavior.descriptor.mode == .set {
          return [semanticBehavior] + children.flatMap(\.effectiveSemanticBehaviors)
        }
        return [semanticBehavior]
      }
      return children.flatMap(\.effectiveSemanticBehaviors)
    }
  }

  struct NativeCustomComponent: Equatable {
    let config: String
    let properties: [RemoteComposeNativeCustomProperty]

    init(_ snapshot: NativeSwiftCustomSnapshot) {
      config = snapshot.config
      properties = snapshot.properties.map {
        RemoteComposeNativeCustomProperty(
          id: $0.id, dataType: $0.dataType, floatValue: $0.floatValue,
          integerValue: $0.integerValue, textValue: $0.textValue)
      }
    }
  }

  struct NativeDrawCommand {
    let kind: Int
    let values: [CGFloat]
    let color: UIColor
    let alpha: CGFloat
    let strokeWidth: CGFloat
    let isStroke: Bool
    let strokeCap: Int
    let strokeJoin: Int
    let blendMode: Int
    let textSize: CGFloat
    let textWeight: CGFloat
    let text: String?
    let path: [NativePathElement]
    let pathWinding: Int
    let gradient: NativeGradient?
    let textStyle: NativeTextStyle
    let image: NativeImageDraw?
    let textureImageID: Int?
    let textureTileModeX: Int
    let textureTileModeY: Int
    let shaderMatrix: [Float]?
    let filterQuality: Int?
    let usesComponentGeometry: Bool

    init(_ snapshot: NativeSwiftDrawCommandSnapshot) {
      kind = snapshot.kind
      values = snapshot.values.map(CGFloat.init)
      color = UIColor(remoteComposeARGB: snapshot.colorARGB)
      alpha = CGFloat(snapshot.alpha)
      strokeWidth = CGFloat(snapshot.strokeWidth)
      isStroke = snapshot.isStroke
      strokeCap = snapshot.strokeCap
      strokeJoin = snapshot.strokeJoin
      blendMode = snapshot.blendMode
      textSize = 16
      textWeight = 400
      text = nil
      path = snapshot.path.map {
        NativePathElement(kind: $0.kind, values: $0.values.map(CGFloat.init))
      }
      pathWinding = snapshot.pathWinding
      gradient = snapshot.gradient.map {
        NativeGradient(
          kind: $0.kind,
          colors: $0.colorsARGB.map { UIColor(remoteComposeARGB: $0).cgColor },
          stops: $0.stops.map(CGFloat.init),
          values: $0.values.map(CGFloat.init),
          tileMode: $0.tileMode)
      }
      textStyle = NativeTextStyle.default
      image = snapshot.image.map {
        NativeImageDraw(
          imageID: $0.imageID,
          source: CGRect(
            x: CGFloat($0.sourceLeft), y: CGFloat($0.sourceTop),
            width: CGFloat($0.sourceRight - $0.sourceLeft),
            height: CGFloat($0.sourceBottom - $0.sourceTop)),
          destination: CGRect(
            x: CGFloat($0.destinationLeft), y: CGFloat($0.destinationTop),
            width: CGFloat($0.destinationRight - $0.destinationLeft),
            height: CGFloat($0.destinationBottom - $0.destinationTop)),
          scaleType: $0.scaleType, scaleFactor: CGFloat($0.scaleFactor),
          contentDescription: $0.contentDescription)
      }
      textureImageID = snapshot.textureImageID
      textureTileModeX = snapshot.textureTileModeX
      textureTileModeY = snapshot.textureTileModeY
      shaderMatrix = snapshot.shaderMatrix
      filterQuality = snapshot.filterQuality
      usesComponentGeometry = snapshot.usesComponentGeometry
    }

    init(text snapshot: NativeSwiftTextSnapshot) {
      kind = 17
      values = [0, CGFloat(snapshot.size), -1, -1, 0, 0]
      color = UIColor(remoteComposeARGB: snapshot.colorARGB)
      alpha = 1
      strokeWidth = 1
      isStroke = false
      strokeCap = 0
      strokeJoin = 0
      blendMode = 3
      textSize = CGFloat(snapshot.size)
      textWeight = CGFloat(snapshot.weight)
      text = snapshot.value
      path = []
      pathWinding = 0
      gradient = nil
      textStyle = NativeTextStyle(swiftSnapshot: snapshot)
      image = nil
      textureImageID = nil
      textureTileModeX = 0
      textureTileModeY = 0
      shaderMatrix = nil
      filterQuality = nil
      usesComponentGeometry = false
    }

    /// The shader's local matrix as a Core Graphics affine transform, or identity when the paint
    /// never named one.
    var textureTransform: CGAffineTransform {
      NativeTexturePolicy.transform(shaderMatrix)
    }

    /// How Core Graphics should sample this paint's image or texture.
    ///
    /// Nil when the paint never said, which leaves the context's own default in place rather than
    /// this renderer inventing a preference.
    var interpolationQuality: CGInterpolationQuality? {
      NativeTexturePolicy.interpolationQuality(forFilterQuality: filterQuality)
    }
  }

  struct NativeImageDraw {
    let imageID: Int
    let source: CGRect
    let destination: CGRect
    let scaleType: Int
    let scaleFactor: CGFloat
    let contentDescription: String?

  }

  struct NativeTextStyle {
    let fontStyle: Int
    let fontFamilyID: Int
    let fontFamilyName: String?
    let alignment: Int
    let overflow: Int
    let maxLines: Int
    let letterSpacing: CGFloat
    let lineHeightAdd: CGFloat
    let lineHeightMultiplier: CGFloat
    let breakStrategy: Int
    let hyphenation: Int
    let isJustified: Bool
    let isUnderlined: Bool
    let isStruckThrough: Bool

    static let `default` = NativeTextStyle(
      swiftSnapshot: NativeSwiftTextSnapshot(
        value: "", colorARGB: 0xff00_0000, size: 16, style: 0, weight: 400,
        familyID: -1, alignment: 1, overflow: 1, maximumLines: Int.max))

    init(swiftSnapshot snapshot: NativeSwiftTextSnapshot) {
      fontStyle = snapshot.style
      fontFamilyID = snapshot.familyID
      fontFamilyName = snapshot.familyName
      alignment = snapshot.alignment
      overflow = snapshot.overflow
      maxLines = snapshot.maximumLines
      letterSpacing = 0
      lineHeightAdd = 0
      lineHeightMultiplier = 1
      breakStrategy = 0
      hyphenation = 0
      isJustified = false
      isUnderlined = false
      isStruckThrough = false
    }
  }

  final class NativeDocumentView: UIView {
    private var document: NativeDocument
    private var resources: NativeResourceStore
    /// The measurements the current tree was refined from, so a settled layout stops re-resolving.
    private var appliedMeasurements: [Int: NativeSwiftMeasuredSize] = [:]
    private let customComponents: RemoteComposeNativeCustomComponentRegistry
    private let customComponentsRevision: UInt
    private let componentView: NativeComponentView

    init(
      document: NativeDocument,
      resources: NativeResourceStore,
      customComponents: RemoteComposeNativeCustomComponentRegistry,
      onGesture: @escaping (Int, NativeSwiftGestureKind, NativeSwiftPointerSample?) -> Void,
      onCustomReturn: @escaping (Int, Int, NativeCustomReturnValue) -> Void
    ) {
      self.document = document
      self.resources = resources
      self.customComponents = customComponents
      customComponentsRevision = customComponents.revision
      componentView = NativeComponentView(
        node: document.root,
        images: resources.images,
        fontNames: resources.fontNames,
        customComponents: customComponents,
        onGesture: onGesture,
        onCustomReturn: onCustomReturn)
      super.init(frame: .zero)
      isOpaque = false
      backgroundColor = .clear
      addSubview(componentView)
      isAccessibilityElement = false
      accessibilityElements = componentView.accessibilityOrder
      accessibilityIdentifier = "rc-native-document"
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
      fatalError("init(coder:) is not supported")
    }

    func update(
      document: NativeDocument,
      resources: NativeResourceStore,
      customComponents: RemoteComposeNativeCustomComponentRegistry
    ) -> Bool {
      guard
        self.customComponents === customComponents,
        customComponentsRevision == customComponents.revision
      else { return false }
      guard
        componentView.canUpdate(
          with: document.root, images: resources.images, fontNames: resources.fontNames)
      else { return false }
      self.document = document
      self.resources = resources
      appliedMeasurements = [:]
      componentView.update(
        node: document.root, images: resources.images, fontNames: resources.fontNames)
      accessibilityElements = componentView.accessibilityOrder
      setNeedsLayout()
      return true
    }

    override func layoutSubviews() {
      super.layoutSubviews()
      guard document.size.width > 0, document.size.height > 0 else {
        componentView.transform = .identity
        componentView.frame = bounds
        return
      }
      let root = NativeRootTransform.resolve(
        document: document.size,
        viewport: bounds.size,
        sizing: document.rootSizing,
        mode: document.rootMode,
        alignment: document.rootAlignment)
      componentView.transform = .identity
      componentView.layoutDensityScale = NativeDensityPolicy.layoutDensityScale(
        androidCompatibility: document.androidCompatibility,
        playbackDensityScale: root.scaleX > 0 ? 1 / root.scaleX : 1)
      componentView.bounds = CGRect(origin: .zero, size: document.size)
      componentView.center = CGPoint(
        x: root.translateX + document.size.width * root.scaleX / 2,
        y: root.translateY + document.size.height * root.scaleY / 2)
      componentView.transform = CGAffineTransform(scaleX: root.scaleX, y: root.scaleY)
      componentView.documentScale = 1
      componentView.layoutDirection =
        effectiveUserInterfaceLayoutDirection == .rightToLeft ? .rightToLeft : .leftToRight
      refineBoundGeometry()
      if var path = ProcessInfo.processInfo.environment["RC_NATIVE_DUMP_TREE"] {
        if !path.hasPrefix("/") {
          let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)
          path = documents.first.map { $0.appendingPathComponent(path).path } ?? path
        }
        var lines: [String] = []
        componentView.dumpTree(depth: 0, into: &lines)
        let text = lines.joined(separator: "\n") + "\n"
        if let existing = FileHandle(forWritingAtPath: path) {
          existing.seekToEndOfFile()
          existing.write(Data(text.utf8))
          try? existing.close()
        } else {
          try? text.write(toFile: path, atomically: true, encoding: .utf8)
        }
      }
    }

    /// Re-resolves the document against the geometry its components were just laid out at.
    ///
    /// A document may bind a component's measured size to a float and derive geometry from it — an
    /// icon scaled by `componentWidth / 24`, say. The core has to resolve that binding before layout
    /// exists, and its estimate cannot match a host that applies density scaling and layout rules
    /// the core does not model. So the first pass draws from an estimate and this corrects it from
    /// the real thing.
    ///
    /// Once per layout, and only while the sizes keep changing. A refinement can change a size —
    /// that is the point — so this would otherwise oscillate; comparing against the sizes the
    /// current tree was built from is what terminates it.
    private func refineBoundGeometry() {
      guard !document.boundComponents.isEmpty, document.refiner != nil else { return }
      var measured: [Int: NativeSwiftMeasuredSize] = [:]
      componentView.collectMeasuredSizes(of: document.boundComponents, into: &measured)
      guard measured != appliedMeasurements else { return }
      guard let refined = document.refined(measuredComponents: measured),
        componentView.canUpdate(
          with: refined.root, images: resources.images, fontNames: resources.fontNames)
      else {
        // Remember what was measured even when the refinement is refused, so a document whose
        // refinement cannot be applied is not re-measured on every single layout pass.
        appliedMeasurements = measured
        return
      }
      appliedMeasurements = measured
      document = refined
      componentView.update(
        node: refined.root, images: resources.images, fontNames: resources.fontNames)
      setNeedsLayout()
    }
  }

  /// One UIView per Remote Compose component, with a deliberately small frame-based implementation
  /// of Box, Row, and Column. Structural content wrappers remain visible in the UIKit hierarchy but
  /// are transparent to layout.
  final class NativeComponentView: UIView, UIGestureRecognizerDelegate {
    private var node: NativeNode
    private var canvasView: NativeCanvasView?
    private var textLabels: [NativeTextLabel]
    private var imageViews: [NativeImageView]
    private var customView: NativeCustomComponentView?
    private var componentChildren: [NativeComponentView]
    private var semanticView: UIView?
    private let onGesture: (Int, NativeSwiftGestureKind, NativeSwiftPointerSample?) -> Void
    var documentScale: CGFloat = 1 {
      didSet {
        guard documentScale != oldValue else { return }
        canvasView?.documentScale = documentScale
        componentChildren.forEach { $0.documentScale = documentScale }
        setNeedsLayout()
      }
    }
    var layoutDensityScale: CGFloat = 1 {
      didSet {
        guard layoutDensityScale != oldValue else { return }
        componentChildren.forEach { $0.layoutDensityScale = layoutDensityScale }
        setNeedsLayout()
      }
    }
    var layoutDirection: NativeLayoutDirection = .leftToRight {
      didSet {
        guard layoutDirection != oldValue else { return }
        componentChildren.forEach { $0.layoutDirection = layoutDirection }
        setNeedsLayout()
      }
    }

    init(
      node: NativeNode,
      images: [Int: UIImage],
      fontNames: [Int: String],
      customComponents: RemoteComposeNativeCustomComponentRegistry,
      onGesture: @escaping (Int, NativeSwiftGestureKind, NativeSwiftPointerSample?) -> Void,
      onCustomReturn: @escaping (Int, Int, NativeCustomReturnValue) -> Void
    ) {
      self.node = node
      self.onGesture = onGesture
      let promotesText = node.kind == .text
      let promotesImage = node.kind == .image
      let drawingCommands = node.commands.filter {
        !(promotesText && $0.kind == 17) && !(promotesImage && $0.kind == 19)
      }
      canvasView =
        drawingCommands.isEmpty
        ? nil : NativeCanvasView(commands: drawingCommands, images: images, fontNames: fontNames)
      textLabels =
        promotesText
        ? node.commands.enumerated().compactMap { index, command in
          guard command.kind == 17 else { return nil }
          return NativeTextLabel(
            componentID: node.componentID, commandIndex: index, command: command,
            fontNames: fontNames)
        } : []
      imageViews =
        promotesImage
        ? node.commands.compactMap { command in
          guard command.kind == 19, let draw = command.image, let image = images[draw.imageID]
          else {
            return nil
          }
          return NativeImageView(
            image: image, draw: draw, alpha: command.alpha,
            filterQuality: command.filterQuality)
        } : []
      customView = node.custom.flatMap {
        NativeCustomComponentView(
          snapshot: $0, componentID: node.componentID, registry: customComponents,
          onReturn: onCustomReturn)
      }
      componentChildren = node.children.map {
        NativeComponentView(
          node: $0, images: images, fontNames: fontNames,
          customComponents: customComponents, onGesture: onGesture,
          onCustomReturn: onCustomReturn)
      }
      semanticView = Self.makeSemanticView(for: node, onGesture: onGesture)
      super.init(frame: .zero)
      isOpaque = false
      backgroundColor = node.backgroundColor ?? .clear
      isHidden = node.visibility == 0
      alpha = node.visibility == 2 ? 0 : 1
      // A scrolled container's children are laid out against their content, which is larger than the
      // viewport by design, so the viewport has to clip them or the overflow paints outside it.
      clipsToBounds = node.cornerRadius > 0 || node.clipsToBounds || node.scrollDirection != nil
      accessibilityIdentifier = "rc-native-component-\(node.componentID)"
      if let semanticView { addSubview(semanticView) }
      if let canvasView { addSubview(canvasView) }
      textLabels.forEach(addSubview)
      imageViews.forEach(addSubview)
      if let customView { addSubview(customView) }
      componentChildren.forEach(addSubview)
      componentChildren.forEach { $0.layer.zPosition = $0.node.zIndex }
      installGestureRecognizers()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
      fatalError("init(coder:) is not supported")
    }

    private func installGestureRecognizers() {
      var doubleTapRecognizer: UITapGestureRecognizer?
      if node.gestureTypes.contains(.doubleTap) {
        let recognizer = UITapGestureRecognizer(target: self, action: #selector(handleDoubleTap(_:)))
        recognizer.numberOfTapsRequired = 2
        recognizer.delegate = self
        addGestureRecognizer(recognizer)
        doubleTapRecognizer = recognizer
      }
      if node.gestureTypes.contains(.tap) {
        let recognizer = UITapGestureRecognizer(target: self, action: #selector(handleTap(_:)))
        recognizer.delegate = self
        if let doubleTapRecognizer { recognizer.require(toFail: doubleTapRecognizer) }
        addGestureRecognizer(recognizer)
      }
      if node.gestureTypes.contains(.longPress) {
        let recognizer = UILongPressGestureRecognizer(
          target: self, action: #selector(handleLongPress(_:)))
        recognizer.delegate = self
        addGestureRecognizer(recognizer)
      }
      let pointerGestures: Set<NativeSwiftGestureKind> = [.touchDown, .touchUp, .touchCancel]
      if node.gestureTypes.contains(where: pointerGestures.contains) {
        let recognizer = UILongPressGestureRecognizer(
          target: self, action: #selector(handlePointerLifecycle(_:)))
        recognizer.minimumPressDuration = 0
        recognizer.allowableMovement = .greatestFiniteMagnitude
        recognizer.delegate = self
        addGestureRecognizer(recognizer)
      }
    }

    @objc private func handleTap(_ recognizer: UITapGestureRecognizer) {
      guard recognizer.state == .recognized else { return }
      onGesture(node.componentID, .tap, pointerSample(recognizer))
    }

    func gestureRecognizer(
      _ gestureRecognizer: UIGestureRecognizer,
      shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
      true
    }

    @objc private func handleLongPress(_ recognizer: UILongPressGestureRecognizer) {
      guard recognizer.state == .began else { return }
      onGesture(node.componentID, .longPress, pointerSample(recognizer))
    }

    @objc private func handleDoubleTap(_ recognizer: UITapGestureRecognizer) {
      guard recognizer.state == .recognized else { return }
      onGesture(node.componentID, .doubleTap, pointerSample(recognizer))
    }

    @objc private func handlePointerLifecycle(_ recognizer: UILongPressGestureRecognizer) {
      let sample = pointerSample(recognizer)
      switch recognizer.state {
      case .began:
        if node.gestureTypes.contains(.touchDown) {
          onGesture(node.componentID, .touchDown, sample)
        }
      case .ended:
        if node.gestureTypes.contains(.touchUp) { onGesture(node.componentID, .touchUp, sample) }
      case .cancelled, .failed:
        if node.gestureTypes.contains(.touchCancel) {
          onGesture(node.componentID, .touchCancel, sample)
        }
      default: break
      }
    }

    private func pointerSample(
      _ recognizer: UIGestureRecognizer, velocity: CGPoint = .zero
    ) -> NativeSwiftPointerSample {
      let point = recognizer.location(in: self)
      return NativeSwiftPointerSample(
        x: Float(point.x), y: Float(point.y), velocityX: Float(velocity.x),
        velocityY: Float(velocity.y))
    }

    func canUpdate(
      with next: NativeNode,
      images: [Int: UIImage],
      fontNames: [Int: String]
    ) -> Bool {
      guard node.componentID == next.componentID, node.kind == next.kind else { return false }
      let local = Self.localContent(for: next, images: images)
      guard
        (canvasView != nil) == !local.drawing.isEmpty,
        node.gestureTypes == next.gestureTypes,
        textLabels.count == local.text.count,
        imageViews.count == local.images.count,
        node.custom?.config == next.custom?.config,
        Self.semanticView(semanticView, matches: next),
        componentChildren.count == next.children.count
      else { return false }
      return zip(componentChildren, next.children).allSatisfy { child, childNode in
        child.canUpdate(with: childNode, images: images, fontNames: fontNames)
      }
    }

    func update(node next: NativeNode, images: [Int: UIImage], fontNames: [Int: String]) {
      precondition(canUpdate(with: next, images: images, fontNames: fontNames))
      let local = Self.localContent(for: next, images: images)
      node = next
      canvasView?.update(commands: local.drawing, images: images, fontNames: fontNames)
      zip(textLabels, local.text).forEach { label, command in
        label.update(command: command, fontNames: fontNames)
      }
      zip(imageViews, local.images).forEach { imageView, item in
        imageView.update(
          image: item.image, draw: item.draw, alpha: item.alpha,
          filterQuality: item.filterQuality)
      }
      if let custom = next.custom { customView?.update(custom) }
      zip(componentChildren, next.children).forEach { child, childNode in
        child.update(node: childNode, images: images, fontNames: fontNames)
        child.layer.zPosition = childNode.zIndex
      }
      updateSemanticView(semanticView, from: next)
      backgroundColor = next.backgroundColor ?? .clear
      isHidden = next.visibility == 0
      alpha = next.visibility == 2 ? 0 : 1
      clipsToBounds =
        next.cornerRadius > 0 || next.clipsToBounds || next.scrollDirection != nil
      setNeedsLayout()
    }

    private static func localContent(
      for node: NativeNode,
      images: [Int: UIImage]
    ) -> (
      drawing: [NativeDrawCommand], text: [NativeDrawCommand],
      images: [(image: UIImage, draw: NativeImageDraw, alpha: CGFloat, filterQuality: Int?)]
    ) {
      let promotesText = node.kind == .text
      let promotesImage = node.kind == .image
      let drawing = node.commands.filter {
        !(promotesText && $0.kind == 17) && !(promotesImage && $0.kind == 19)
      }
      let text = promotesText ? node.commands.filter { $0.kind == 17 } : []
      let imageItems =
        promotesImage
        ? node.commands.compactMap {
          command -> (UIImage, NativeImageDraw, CGFloat, Int?)? in
          guard command.kind == 19, let draw = command.image, let image = images[draw.imageID]
          else { return nil }
          return (image, draw, command.alpha, command.filterQuality)
        } : []
      return (drawing, text, imageItems)
    }

    private static func semanticView(_ view: UIView?, matches node: NativeNode) -> Bool {
      guard let descriptor = node.semanticBehavior?.descriptor else { return view == nil }
      switch descriptor.elementKind {
      case .button: return view is NativeSemanticButton
      case .toggle: return view is NativeSemanticSwitch
      case .image: return view is NativeSemanticImageView
      default:
        return (view as? NativeSemanticControl)?.kind == descriptor.elementKind
      }
    }

    var accessibilityOrder: [Any] {
      // The *effective* state, not the document's field: a FitBox displays an alternative whose own
      // visibility modifier is GONE, and a displayed button has to be reachable by VoiceOver rather
      // than filtered out by the modifier the box deliberately ignored.
      guard !isHidden, alpha > 0.01 else { return [] }
      let descendants = componentChildren.flatMap(\.accessibilityOrder)
      let local: [Any] =
        textLabels.filter(\.isAccessibilityElement).map { $0 as Any }
        + imageViews.filter(\.isAccessibilityElement).map { $0 as Any }
        + (customView.map { [$0 as Any] } ?? [])
      guard let semanticView, let descriptor = node.semanticBehavior?.descriptor else {
        return local + descendants
      }
      let owner = semanticView.isAccessibilityElement ? [semanticView] : []
      return descriptor.hidesDescendants ? owner : owner + local + descendants
    }

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
      guard isUserInteractionEnabled, !isHidden, alpha > 0.01 else { return nil }
      let isInside = self.point(inside: point, with: event)
      if clipsToBounds, !isInside { return nil }
      if clipsToBounds, layer.cornerRadius > 0,
        !UIBezierPath(roundedRect: bounds, cornerRadius: layer.cornerRadius).contains(point)
      {
        return nil
      }

      // Core Animation zPosition controls painting, while UIView.hitTest normally only observes
      // subview insertion order. Follow the rendered ordering explicitly so overlapping Remote
      // Compose components activate the same top-most component that the user sees.
      let frontToBack = componentChildren.enumerated().sorted { left, right in
        if left.element.layer.zPosition == right.element.layer.zPosition {
          return left.offset > right.offset
        }
        return left.element.layer.zPosition > right.element.layer.zPosition
      }
      for (_, child) in frontToBack {
        if let hit = child.hitTest(convert(point, to: child), with: event) { return hit }
      }
      if isInside, let customView,
        let hit = customView.hitTest(convert(point, to: customView), with: event)
      {
        return hit
      }
      if isInside, let semanticView,
        let hit = semanticView.hitTest(convert(point, to: semanticView), with: event)
      {
        return hit
      }
      return isInside && !node.gestureTypes.isEmpty ? self : nil
    }

    /// MODIFIER_GRAPHICS_LAYER's 2D attributes.
    ///
    /// The origin is the part that needed deciding. No document on the catalog sheet carries
    /// `TRANSFORM_ORIGIN`, so the default decides every transform on it, and the declared
    /// `remote-core` default -- 0, the top-left -- is not what any AndroidX lane actually draws for
    /// an absent attribute. This player centres, and `graphicsLayerTransformOrigin` below carries
    /// the derivation and the measurement.
    ///
    /// CALayer applies `transform` about its own anchor point, so an origin of (0, 0) has to be
    /// folded into the matrix rather than assumed. For a linear part S and an anchor at A, the
    /// transform that behaves as if applied about p is S with a translation of (I - S)(p - A);
    /// with p = (0, 0) that is -(I - S)A. Recomputed from `bounds` on every layout pass, so it
    /// stays correct through resizes and never compounds.
    /// The transform origin used when a document does not state one.
    ///
    /// `TRANSFORM_ORIGIN` looked contested — `remote-core` declares a default of `0f` while
    /// `remote-creation-compose` omitted the attribute at `0.5f` (#155, #153) — and this centred
    /// on measured evidence while the disagreement stood. The disagreement has since resolved, in
    /// favour of centring, and for a reason rather than a preference:
    ///
    /// * `GraphicsLayerModifierOperation.fillInAttributes` records an attribute only when its value
    ///   differs from the declared default, and `PaintContext.setGraphicsLayer` applies only the
    ///   keys it is handed. An absent origin therefore never reaches the layer, which keeps its own
    ///   pivot — its centre. The declared `0f` is what makes the attribute absent; it is not a
    ///   value any lane applies.
    /// * androidx-main `4969cdd96c6` then fixed the writer to omit `0f` rather than `0.5f`, so a
    ///   document that means top-left now says so explicitly and arrives with the attribute
    ///   present. Nothing is lost by defaulting to the centre.
    ///
    /// The measurement that first forced this remains the check on it. The only attribute any
    /// catalog document sets is `SCALE_X = -1`, a horizontal mirror, on the 50
    /// `pageindicator-vertical__ideal__left-*` variants:
    ///
    /// | origin | those 50 documents |
    /// | --- | --- |
    /// | `0` | 0 opaque pixels — content mirrors to negative x, off-canvas |
    /// | centre | x 10..26, the exact mirror of the untransformed sibling's 359..373 |
    ///
    /// Worse than wrong, `0` is *invisible*: those documents score 0.26% different while drawing
    /// nothing, because the indicator is ~518 pixels on a 384x384 canvas. No lane output flags it.
    ///
    /// ### The one thing that would change this
    ///
    /// An AndroidX lane that pivots an absent origin at the top-left. `remote-player-compose`'s
    /// embedded player now reads the attribute's source directly and does pivot there — it does
    /// not consult `needsToWrite` — so it and the `RenderNode` lanes disagree on documents captured
    /// before `4969cdd96c6`. If that reading becomes the one AndroidX settles on, delete this
    /// function and use `.zero` for `origin` in `applyGraphicsLayer`; `rc-player-compose` carries
    /// the same default, in `RcGraphicsLayerValues`, and would move with it.
    private static func graphicsLayerTransformOrigin(of size: CGSize) -> CGPoint {
      CGPoint(x: size.width / 2, y: size.height / 2)
    }

    private func applyGraphicsLayer() {
      guard let graphicsLayer = node.graphicsLayer, !graphicsLayer.isIdentity else { return }
      let anchor = CGPoint(
        x: layer.anchorPoint.x * bounds.width, y: layer.anchorPoint.y * bounds.height)
      let linear = CGAffineTransform(
        rotationAngle: CGFloat(graphicsLayer.rotationZ) * .pi / 180
      ).scaledBy(x: CGFloat(graphicsLayer.scaleX), y: CGFloat(graphicsLayer.scaleY))
      let origin = Self.graphicsLayerTransformOrigin(of: bounds.size)
      let dx = origin.x - anchor.x, dy = origin.y - anchor.y
      let originX = dx - (linear.a * dx + linear.c * dy)
      let originY = dy - (linear.b * dx + linear.d * dy)
      layer.transform = CATransform3DMakeAffineTransform(
        linear.concatenating(
          CGAffineTransform(
            translationX: originX + CGFloat(graphicsLayer.translationX),
            y: originY + CGFloat(graphicsLayer.translationY))))
      if graphicsLayer.alpha != 1, node.visibility != 2 {
        alpha = CGFloat(max(0, min(1, graphicsLayer.alpha)))
      }
    }

    /// The laid-out size of each named component, gathered from this subtree.
    /// This view's size, when layout actually gave it one.
    ///
    /// Empty bounds mean layout never sized this view — a structural view is flattened into its
    /// parent's arrangement and never given a frame. Real bounds are trusted whether the view is
    /// structural or not: distrusting them cost twelve exact matches when tried, because plenty of
    /// structural views are sized perfectly well and only the unsized ones need an ancestor's size.
    private var laidOutSize: CGSize? {
      bounds.isEmpty ? nil : bounds.size
    }

    /// Prints this subtree's laid-out geometry, for comparing against the reference player's.
    ///
    /// Gated on `RC_NATIVE_DUMP_TREE` because it is a diagnostic, not a feature: the reference
    /// publishes the same information through Compose semantics, and the two dumps side by side are
    /// how a layout divergence gets localised to one component.
    func dumpTree(depth: Int, into lines: inout [String]) {
      let indent = String(repeating: "  ", count: depth)
      let f = frame
      lines.append(
        "\(indent)id=\(node.componentID) \(node.kind) "
          + "pos=(\(Int(f.minX)),\(Int(f.minY))) size=\(Int(f.width))x\(Int(f.height))"
          + " w=\(node.widthType)/\(node.widthValue) h=\(node.heightType)/\(node.heightValue)"
          + " wIn=\(node.minimumWidth)..\(String(describing: node.maximumWidth))"
          + " hIn=\(node.minimumHeight)..\(String(describing: node.maximumHeight))"
          + " structural=\(isStructural)")
      for child in componentChildren { child.dumpTree(depth: depth + 1, into: &lines) }
    }

    func collectMeasuredSizes(
      of wanted: Set<Int>, into result: inout [Int: NativeSwiftMeasuredSize],
      inherited: CGSize? = nil
    ) {
      if wanted.contains(node.componentID) {
        // A structural view is transparent to layout: its children are flattened into its parent's
        // arrangement and it is never given a frame of its own, so its bounds are not a size. The
        // size such a component *has* is the area it was flattened into, which is the nearest
        // ancestor that layout did size. Reporting its own empty bounds instead resolves the binding
        // to zero, and a scale of zero draws exactly as little as the pre-layout estimate did.
        let measurable = laidOutSize ?? inherited
        if let measurable {
          result[node.componentID] = NativeSwiftMeasuredSize(
            width: Float(measurable.width), height: Float(measurable.height))
        }
      }
      let forChildren = laidOutSize ?? inherited
      for child in componentChildren {
        child.collectMeasuredSizes(of: wanted, into: &result, inherited: forChildren)
      }
    }

    override func layoutSubviews() {
      super.layoutSubviews()
      applyGraphicsLayer()
      layer.cornerRadius = min(
        node.cornerRadius * layoutUnitScale,
        max(min(bounds.width, bounds.height) / 2, 0))
      canvasView?.frame = bounds
      prepareStructuralChildren()
      if isStructural {
        updateStructuralSemanticFrames()
        return
      }

      switch node.kind {
      case .column: layoutColumn()
      case .row: node.flowMaximumItems == nil ? layoutRow() : layoutFlow()
      // The root arranges its children the way a box does: a child that fills still covers the
      // canvas, and a child that wraps takes its own size instead of being stretched to the frame.
      case .box, .root:
        // A FitBox is the one box that does not stack what it holds: it shows the first alternative
        // whose natural size fits, and nothing at all when none does.
        if isFitBox {
          layoutFitBox()
        } else {
          layoutOverlay(aligned: true)
        }
      case .text:
        textLabels.forEach {
          $0.layoutInComponent(
            bounds: bounds, documentScale: documentScale, layoutDirection: layoutDirection)
        }
      case .image: imageViews.forEach { $0.frame = bounds }
      case .custom: customView?.frame = bounds
      default: layoutOverlay(aligned: false)
      }
      semanticView?.frame = bounds
      updateStructuralSemanticFrames()
    }

    func preferredSize(in available: CGSize) -> CGSize {
      let insets = scaledPadding
      // Every width type, `WRAP` included. A wrapping component still carries its `widthIn`
      // bounds, and passing children the full available width instead let an edge button's label
      // measure 209px wide against a 161px bound. `resolve` with the available width as the
      // intrinsic degrades to exactly that width when there are no bounds to apply.
      let widthConstraint =
        NativeLayoutDimension(
          type: node.widthType,
          value: node.widthValue
            * (node.widthType == 6 ? layoutDensityScale : documentScale),
          minimum: node.minimumWidth * dimensionConstraintScale,
          maximum: node.maximumWidth.map { $0 * dimensionConstraintScale }
        ).resolve(intrinsic: available.width, available: available.width)
      // The container's own height, resolved the same way, because a collapsible container's
      // retention decision is about *its* bound and not about the space its parent offered: a
      // 50-point collapsible column holding a 60-point child keeps nothing, whatever the parent
      // has to spare.
      let heightConstraint =
        NativeLayoutDimension(
          type: node.heightType,
          value: node.heightValue
            * (node.heightType == 6 ? layoutDensityScale : documentScale),
          minimum: node.minimumHeight * dimensionConstraintScale,
          maximum: node.maximumHeight.map { $0 * dimensionConstraintScale }
        ).resolve(intrinsic: available.height, available: available.height)
      let contentAvailable = CGSize(
        width: max(min(available.width, widthConstraint) - insets.left - insets.right, 0),
        height: max(min(available.height, heightConstraint) - insets.top - insets.bottom, 0))
      // A FitBox wraps to the alternative it shows, not to the largest of everything it holds.
      if isFitBox {
        let fitting = fitBoxAlternativesAndSizes(in: contentAvailable).first {
          $0.size.width <= contentAvailable.width + 0.5
            && $0.size.height <= contentAvailable.height + 0.5
        }
        // Nothing fits: the box is GONE and takes no space, the way a collapsible container that
        // keeps nothing does, so its parent does not reserve a box the reference hides.
        let intrinsic = fitting?.size ?? .zero
        return applyDimensions(
          to: CGSize(
            width: intrinsic.width + insets.left + insets.right,
            height: intrinsic.height + insets.top + insets.bottom),
          available: available)
      }
      let allItems = flattenedLayoutItems
      // A collapsible container wraps to what it keeps, not to everything it holds — and reports
      // nothing at all when it keeps nothing, so a parent does not reserve a box the reference
      // treats as GONE.
      let items: [NativeComponentView]
      if node.isCollapsible,
        let kept = collapsibleKeptFlags(
          items: allItems, available: contentAvailable,
          axis: node.kind == .column ? .vertical : .horizontal)
      {
        let keptItems = allItems.enumerated().filter { kept[$0.offset] }.map(\.element)
        // A collapsible container that keeps nothing is the reference's GONE container: it takes no
        // space at all, so its parent does not reserve a box for it.
        if keptItems.isEmpty { return .zero }
        items = keptItems
      } else {
        items = allItems
      }
      let intrinsic: CGSize
      switch node.kind {
      case .text:
        intrinsic =
          textLabels.first?.preferredSize(
            maximumWidth: contentAvailable.width, documentScale: documentScale) ?? .zero
      case .image:
        intrinsic = imageViews.first?.image?.size ?? .zero
      case .custom:
        intrinsic = customView?.sizeThatFits(contentAvailable) ?? .zero
      case .column:
        let sizes = items.map { $0.preferredSize(in: contentAvailable) }
        intrinsic = CGSize(
          width: sizes.map(\.width).max() ?? 0,
          height: sizes.reduce(0) { $0 + $1.height }
            + scaledSpacing * CGFloat(max(sizes.count - 1, 0)))
      case .row:
        if node.flowMaximumItems != nil {
          intrinsic = wrappedFlowSize(items, in: contentAvailable)
        } else {
          let sizes = items.map { $0.preferredSize(in: contentAvailable) }
          intrinsic = CGSize(
            width: sizes.reduce(0) { $0 + $1.width }
              + scaledSpacing * CGFloat(max(sizes.count - 1, 0)),
            height: sizes.map(\.height).max() ?? 0)
        }
      default:
        let sizes = items.map { $0.preferredSize(in: contentAvailable) }
        intrinsic = CGSize(
          width: sizes.map(\.width).max() ?? 0,
          height: sizes.map(\.height).max() ?? 0)
      }
      return applyDimensions(
        to: CGSize(
          width: intrinsic.width + insets.left + insets.right,
          height: intrinsic.height + insets.top + insets.bottom),
        available: available)
    }

    private var isStructural: Bool {
      (node.kind == .content || node.kind == .group || node.kind == .canvas)
        && (canvasView == nil || node.kind == .content || node.kind == .group)
        && textLabels.isEmpty && imageViews.isEmpty
        && customView == nil
        && node.semanticBehavior?.acceptsPointerAction != true
        && node.backgroundColor == nil
        && node.visibility == 1 && node.widthType == 2 && node.heightType == 2
        && node.minimumWidth == 0 && node.minimumHeight == 0
        && node.maximumWidth == nil && node.maximumHeight == nil
        && node.padding == .zero && node.offset == .zero && node.zIndex == 0
    }

    private var flattenedLayoutItems: [NativeComponentView] {
      componentChildren.filter { $0.node.visibility != 0 }.flatMap { child in
        child.isStructural ? child.flattenedLayoutItems : [child]
      }
    }

    private var scaledPadding: UIEdgeInsets {
      UIEdgeInsets(
        top: node.padding.top * layoutUnitScale,
        left: node.padding.left * layoutUnitScale,
        bottom: node.padding.bottom * layoutUnitScale,
        right: node.padding.right * layoutUnitScale)
    }

    private var layoutUnitScale: CGFloat {
      node.densityBehavior == 2 ? layoutDensityScale : documentScale
    }

    /// What `widthIn`/`heightIn` bounds are measured in — see `NativeDensityPolicy`.
    private var dimensionConstraintScale: CGFloat {
      NativeDensityPolicy.dimensionConstraintScale(
        densityBehavior: node.densityBehavior,
        layoutDensityScale: layoutDensityScale,
        documentScale: documentScale)
    }

    private var scaledSpacing: CGFloat { node.spacing * layoutUnitScale }

    private func prepareStructuralChildren() {
      componentChildren.forEach { child in
        if child.isStructural {
          child.frame = bounds
          child.prepareStructuralChildren()
        }
      }
    }

    private func updateStructuralSemanticFrames() {
      componentChildren.forEach { $0.updateStructuralSemanticFrames() }
      guard isStructural, let semanticView else { return }
      let renderedBounds =
        flattenedLayoutItems
        .filter { !$0.isHidden && $0.alpha > 0.01 }
        .map { convert($0.bounds, from: $0) }
        .filter { !$0.isEmpty && !$0.isNull }
        .reduce(CGRect.null) { $0.union($1) }
      let clippedBounds = renderedBounds.intersection(bounds)
      semanticView.frame = clippedBounds.isNull ? .zero : clippedBounds
    }

    /// The size a child contributes to its container.
    ///
    /// Inside a **scrolled** container the axis that scrolls is measured unbounded: the viewport
    /// clips the content, it does not size it. A child that fills that axis is the exception — it has
    /// no natural size, so it keeps the viewport's bound.
    private func measuredSize(
      of child: NativeComponentView, in available: CGSize, axis: CollapsibleAxis
    ) -> CGSize {
      guard node.scrollDirection != nil else { return child.preferredSize(in: available) }
      let type = axis == .vertical ? child.node.heightType : child.node.widthType
      guard NativeSwiftCollapsible.measuresUnbounded(mainAxisType: Int(type)) else {
        return child.preferredSize(in: available)
      }
      let space =
        axis == .vertical
        ? CGSize(width: available.width, height: .greatestFiniteMagnitude)
        : CGSize(width: .greatestFiniteMagnitude, height: available.height)
      return child.preferredSize(in: space)
    }

    /// The extent a scrolled container arranges its children in along one axis, or nil when it does
    /// not scroll.
    ///
    /// A scrolled container's children are laid out against the content they make, not against the
    /// viewport that clips them: the corpus's `collapsible_column_scroll` records its survivors
    /// centred in the pre-collapse 240 points even though the viewport is 200 — the collapse frees no
    /// space, it only moves the content — and `box_child_scroll` records a 250-point child in a
    /// 150-point viewport. The viewport decides what is *visible*; the content decides where things
    /// sit. Every child counts towards it, including the ones a collapse dropped.
    ///
    /// A row or column **stacks** its children, so the content is their sum; a box overlays them, so
    /// it is the largest. Summing an overlay's children inflated the extent and misaligned every
    /// centred or end-aligned child in it.
    private func scrolledExtent(
      of items: [NativeComponentView], in viewport: CGSize, axis: CollapsibleAxis, stacking: Bool
    ) -> CGFloat? {
      guard node.scrollDirection != nil else { return nil }
      let extents = items.map { child -> CGFloat in
        let size = measuredSize(of: child, in: viewport, axis: axis)
        return axis == .vertical ? size.height : size.width
      }
      guard stacking else { return extents.max() ?? 0 }
      return extents.reduce(0, +) + scaledSpacing * CGFloat(max(extents.count - 1, 0))
    }

    private func layoutOverlay(aligned: Bool) {
      let insets = scaledPadding
      let content = bounds.inset(by: insets)
      let items = flattenedLayoutItems
      let axis: CollapsibleAxis = node.scrollDirection == 1 ? .horizontal : .vertical
      let extent = scrolledExtent(of: items, in: content.size, axis: axis, stacking: false)
      let space =
        node.scrollDirection == 1
        ? CGRect(
          x: content.minX, y: content.minY, width: extent ?? content.width, height: content.height)
        : CGRect(
          x: content.minX, y: content.minY, width: content.width, height: extent ?? content.height)
      items.forEach { child in
        let size = measuredSize(of: child, in: content.size, axis: axis)
        child.frame = child.offsetFrame(
          aligned
            ? alignedFrame(size: size, in: space)
            : CGRect(origin: content.origin, size: content.size))
      }
    }

    /// A `FitBox`: it shows the first alternative whose natural size fits, and shows nothing at all
    /// when none does.
    private var isFitBox: Bool { node.componentKind == "FitBoxLayout" }

    /// Whether this view is a `FitBox`'s own content node — the wrapper the box looks through.
    ///
    /// `isStructural` is a *layout* classification and it requires the wrapper to be visible, so a
    /// content switched off is not structural by that test. A FitBox has to recognise its content by
    /// kind instead: otherwise a GONE wrapper is treated as an alternative, selected, and unhidden,
    /// and content that is meant to switch the whole box off is rendered.
    private var isFitBoxContent: Bool {
      node.kind == .content || (node.kind == .canvas && node.commands.isEmpty)
    }

    /// A `FitBox`'s alternatives, in document order.
    ///
    /// Unlike every other container this does **not** drop the children whose own visibility
    /// modifier hides them: a document switches between alternatives with that modifier, and the
    /// reference ignores it — the fit test sees the alternative's real size and the winner is drawn.
    /// Taking the modifier at face value measured a hidden child as 0x0, so it "fitted" and displaced
    /// the alternative that actually fits (`fitbox_child_visibility`).
    private var fitBoxAlternatives: [NativeComponentView] {
      componentChildren.flatMap { child in
        child.isFitBoxContent ? child.fitBoxAlternatives : [child]
      }
    }

    /// Whether the box's *content* is switched on. An alternative's own modifier is ignored, but the
    /// content's is the box's own switch: a GONE content shows nothing.
    private var fitBoxContentVisible: Bool {
      !componentChildren.contains { $0.isFitBoxContent && $0.node.visibility == 0 }
    }

    /// An alternative's natural size: the size it asks for when nothing forces it to fill.
    private func fitBoxNaturalSize(_ child: NativeComponentView, in available: CGSize) -> CGSize {
      // The rule the collapsible fit test also uses: a dimension that fills has no natural size, so
      // it keeps the box's bound rather than resolving to infinity.
      child.preferredSize(
        in: CGSize(
          width: NativeSwiftCollapsible.measuresUnbounded(mainAxisType: Int(child.node.widthType))
            ? .greatestFiniteMagnitude : available.width,
          height: NativeSwiftCollapsible.measuresUnbounded(mainAxisType: Int(child.node.heightType))
            ? .greatestFiniteMagnitude : available.height))
    }

    private func fitBoxAlternativesAndSizes(in available: CGSize) -> [(
      view: NativeComponentView, size: CGSize
    )] {
      fitBoxAlternatives.map { ($0, fitBoxNaturalSize($0, in: available)) }
    }

    /// Lays a `FitBox` out: the first alternative whose natural size fits is drawn, aligned inside
    /// the box; the others are hidden. When none fits the box shows nothing at all — the box itself
    /// is GONE, which is what the reference does and what `fitbox_fit` asserts.
    private func layoutFitBox() {
      let insets = scaledPadding
      let content = bounds.inset(by: insets)
      isHidden = node.visibility == 0
      let measured = fitBoxAlternativesAndSizes(in: content.size)
      for (alternative, _) in measured {
        // The reference ignores an alternative's own visibility modifier: it is the document's
        // switch between alternatives, not something the box obeys.
        alternative.isHidden = true
        if alternative.node.visibility != 0 { alternative.alpha = 1 }
      }
      let fitting = measured.first {
        $0.size.width <= content.width + 0.5 && $0.size.height <= content.height + 0.5
      }
      // A content that is switched off is the box's own switch: the box stays, its content does not.
      guard fitBoxContentVisible, let (winner, size) = fitting else {
        // Nothing fits: the reference hides the box, background included. The alternative keeps the
        // geometry it would have had, which the corpus does not compare for a gone node.
        if let (first, size) = measured.first {
          placeFitBoxAlternative(first, size: size, in: content)
        }
        if fitting == nil { isHidden = true }
        return
      }
      winner.isHidden = false
      winner.alpha = 1
      placeFitBoxAlternative(winner, size: size, in: content)
    }

    private func placeFitBoxAlternative(
      _ view: NativeComponentView, size: CGSize, in content: CGRect
    ) {
      view.frame = view.offsetFrame(alignedFrame(size: size, in: content))
    }

    /// The children this container lays out.
    ///
    /// A collapsible container hides the children that do not fit, in the order their
    /// `CollapsiblePriority` modifiers give, and is itself hidden when nothing fits — the
    /// reference's container GONE. Every other container returns its children unchanged.
    private func collapsibleItems(in content: CGRect, axis: CollapsibleAxis) -> [NativeComponentView]
    {
      let items = flattenedLayoutItems
      guard let kept = collapsibleKeptFlags(items: items, available: content.size, axis: axis)
      else { return items }
      for (index, child) in items.enumerated() { child.isHidden = !kept[index] }
      let visible = items.enumerated().filter { kept[$0.offset] }.map(\.element)
      isHidden = node.visibility == 0 || visible.isEmpty
      return visible
    }

    /// Which of `items` a collapsible container keeps, or nil when it is not collapsible.
    private func collapsibleKeptFlags(
      items: [NativeComponentView], available: CGSize, axis: CollapsibleAxis
    ) -> [Bool]? {
      guard node.isCollapsible else { return nil }
      let orientation = axis == .vertical ? 1 : 0
      let children = items.map { child -> NativeSwiftCollapsible.Child in
        // The fit test measures each child with its *main* axis unbounded: the reference measures
        // with the constraints the container received from its parent, so a child taller than the
        // container is measured at its natural size and then dropped, rather than clamped to fit and
        // kept. A child that *fills* that axis is the exception — it has no natural size, so an
        // unbounded measurement resolves it to infinity and it would be dropped from any container.
        let mainAxisType = axis == .vertical ? child.node.heightType : child.node.widthType
        let measuring: CGSize
        if NativeSwiftCollapsible.measuresUnbounded(mainAxisType: mainAxisType) {
          measuring =
            axis == .vertical
            ? CGSize(width: available.width, height: .greatestFiniteMagnitude)
            : CGSize(width: .greatestFiniteMagnitude, height: available.height)
        } else {
          measuring = available
        }
        let size = child.preferredSize(in: measuring)
        let weightType = axis == .vertical ? child.node.heightType : child.node.widthType
        let weightValue = axis == .vertical ? child.node.heightValue : child.node.widthValue
        let priority =
          child.node.collapsiblePriorityOrientation == orientation
          ? child.node.collapsiblePriority.map(Float.init) : nil
        return NativeSwiftCollapsible.Child(
          mainSize: Float(axis == .vertical ? size.height : size.width),
          weight: weightType == 3 ? Float(max(weightValue, 0)) : 0,
          priority: priority)
      }
      let extent = axis == .vertical ? available.height : available.width
      return NativeSwiftCollapsible.keptChildren(
        children, available: Float(extent), spacing: Float(scaledSpacing))
    }

    private enum CollapsibleAxis {
      case horizontal, vertical
    }

    private func layoutColumn() {
      let content = bounds.inset(by: scaledPadding)
      let items = collapsibleItems(in: content, axis: .vertical)
      // A scrolled column arranges against its content rather than its viewport, and the extent
      // counts every child — including the ones a collapse dropped, which is why the survivors are
      // centred in the pre-collapse total. The axis that scrolls is the *modifier's*, which need not
      // be the arrangement axis: a horizontally scrolled column still stacks, but each child is
      // measured unbounded in width.
      let scrollAxis: CollapsibleAxis = node.scrollDirection == 1 ? .horizontal : .vertical
      let extent = node.scrollDirection == 0
        ? (scrolledExtent(
          of: flattenedLayoutItems, in: content.size, axis: .vertical, stacking: true)
          ?? content.height)
        : content.height
      let sizes = items.map { measuredSize(of: $0, in: content.size, axis: scrollAxis) }
      let weightedHeights = NativeLinearLayout.allocateWeighted(
        available: NativeLinearLayout.collapsibleWeightSpace(
          extent: extent, count: items.count,
          spacing: node.isCollapsible ? scaledSpacing : 0),
        naturalSizes: sizes.map(\.height),
        weights: items.map { child in
          guard child.node.heightType == 3 else { return nil }
          return max(child.node.heightValue, .leastNonzeroMagnitude)
        })
      let heights = zip(items, zip(sizes, weightedHeights)).map { child, values in
        child.applyDimensions(
          to: values.0,
          available: CGSize(width: content.width, height: values.1)
        ).height
      }
      let positions = NativeLinearLayout.positions(
        total: extent,
        sizes: heights,
        positioning: node.verticalPositioning,
        spacing: scaledSpacing)
      for (index, child) in items.enumerated() {
        let size = CGSize(width: sizes[index].width, height: heights[index])
        let x = horizontalOrigin(for: size.width, in: content)
        child.frame = child.offsetFrame(
          CGRect(
            x: x, y: content.minY + positions[index], width: size.width, height: size.height))
      }
    }

    /// A flow container's children: left to right, wrapping onto a further line when the next one
    /// does not fit, bounded by the layout's maximum items per line and maximum lines.
    ///
    /// Two passes, because an item is aligned within *its line* and the block of lines is aligned
    /// within the container: the first measures the lines, the second places each item at the
    /// line's top, centre or bottom according to the container's vertical positioning.
    ///
    /// A weighted child does not force a wrap — its main-axis size is the row's leftover, not the
    /// whole container — so it is measured with its siblings and allocated a share of the space
    /// they left, exactly as the reference's `FlowRow` does.
    private func layoutFlow() {
      let content = bounds.inset(by: scaledPadding)
      let maximumItems = node.flowMaximumItems.flatMap { $0 > 0 ? $0 : nil } ?? Int.max
      let maximumLines = node.flowMaximumLines.flatMap { $0 > 0 ? $0 : nil } ?? Int.max
      let wrapped = wrappedFlow(
        items: flattenedLayoutItems, in: content.size, maximumItems: maximumItems,
        maximumLines: maximumLines)
      for child in wrapped.discarded { child.isHidden = true }
      // Lines are stacked without a gap, matching the reference's `FlowRow`, which passes 0 as its
      // vertical arrangement spacing while the horizontal `spacedBy` separates items on a line.
      let blockHeight = wrapped.lines.map(\.height).reduce(0, +)
      var y = content.minY
      switch node.verticalPositioning {
      case 2: y += (content.height - blockHeight) / 2
      case 5: y += content.height - blockHeight
      default: break
      }
      for line in wrapped.lines {
        let positions = NativeLinearLayout.positions(
          total: content.width, sizes: line.items.map(\.size.width),
          positioning: node.horizontalPositioning, spacing: scaledSpacing,
          direction: layoutDirection)
        for (index, item) in line.items.enumerated() {
          let offset: CGFloat
          switch node.verticalPositioning {
          case 2: offset = (line.height - item.size.height) / 2
          case 5: offset = line.height - item.size.height
          default: offset = 0
          }
          item.view.isHidden = false
          item.view.frame = item.view.offsetFrame(
            CGRect(
              x: content.minX + positions[index], y: y + offset, width: item.size.width,
              height: item.size.height))
        }
        y += line.height
      }
    }

    /// The block a flow's children occupy when wrapped within `available`, for wrap sizing.
    private func wrappedFlowSize(_ items: [NativeComponentView], in available: CGSize) -> CGSize {
      let maximumItems = node.flowMaximumItems.flatMap { $0 > 0 ? $0 : nil } ?? Int.max
      let maximumLines = node.flowMaximumLines.flatMap { $0 > 0 ? $0 : nil } ?? Int.max
      let wrapped = wrappedFlow(
        items: items, in: available, maximumItems: maximumItems, maximumLines: maximumLines)
      // No vertical gap between lines: the horizontal `spacedBy` is not a line height.
      return CGSize(
        width: wrapped.lines.map(\.width).max() ?? 0,
        height: wrapped.lines.map(\.height).reduce(0, +))
    }

    private typealias FlowLine = (
      items: [(view: NativeComponentView, size: CGSize)], width: CGFloat, height: CGFloat
    )

    /// Wraps `items` into lines, measuring weighted children against their row's leftover space.
    ///
    /// The line breaks come from `NativeSwiftFlow`, which reserves a weighted child's `widthIn`
    /// minimum; a weighted child is then measured at its share of what the row left, never below
    /// that minimum.
    private func wrappedFlow(
      items: [NativeComponentView], in available: CGSize, maximumItems: Int, maximumLines: Int
    ) -> (lines: [FlowLine], discarded: [NativeComponentView]) {
      let children = items.map { child in
        NativeSwiftFlow.Child(
          measuredWidth: Float(child.preferredSize(in: available).width),
          weight: child.node.widthType == 3 ? Float(max(child.node.widthValue, 0)) : 0,
          minimumWidth: Float(child.node.minimumWidth * child.dimensionConstraintScale))
      }
      let segmented = NativeSwiftFlow.segment(
        children, available: Float(available.width), spacing: Float(scaledSpacing),
        maximumItems: maximumItems, maximumLines: maximumLines)
      var lines: [FlowLine] = []
      for indices in segmented.lines {
        var lineItems: [(view: NativeComponentView, size: CGSize)] = []
        let gaps = scaledSpacing * CGFloat(max(indices.count - 1, 0))
        let weights = indices.map { index -> CGFloat? in
          children[index].weight > 0
            ? max(CGFloat(children[index].weight), .leastNonzeroMagnitude) : nil
        }
        let allocated = NativeLinearLayout.allocateWeighted(
          available: max(available.width - gaps, 0),
          naturalSizes: indices.map { CGFloat(children[$0].measuredWidth) },
          weights: weights)
        var width = gaps
        for (position, index) in indices.enumerated() {
          let child = items[index]
          let size: CGSize
          if let weight = weights[position] {
            // The allocator splits the leftover; the child's own minimum still bounds it, because a
            // weighted child that cannot reach its minimum has to overflow its row rather than be
            // drawn narrower than the document allows.
            let minimum = CGFloat(children[index].minimumWidth)
            let share = max(max(allocated[position], minimum), 0)
            let measured = child.preferredSize(
              in: CGSize(width: share, height: available.height))
            size = CGSize(width: max(share, weight * 0), height: measured.height)
          } else {
            size = child.preferredSize(in: available)
          }
          child.isHidden = false
          lineItems.append((child, size))
          width += size.width
        }
        let height = lineItems.map(\.size.height).max() ?? 0
        lines.append((items: lineItems, width: width, height: height))
      }
      return (lines, segmented.discarded.map { items[$0] })
    }

    private func layoutRow() {
      let content = bounds.inset(by: scaledPadding)
      let items = collapsibleItems(in: content, axis: .horizontal)
      // As in `layoutColumn`: a scrolled row arranges against its content, not its viewport, and the
      // axis that scrolls is the modifier's — a vertically scrolled row still places left to right.
      let scrollAxis: CollapsibleAxis = node.scrollDirection == 1 ? .horizontal : .vertical
      let extent = node.scrollDirection == 1
        ? (scrolledExtent(
          of: flattenedLayoutItems, in: content.size, axis: .horizontal, stacking: true)
          ?? content.width)
        : content.width
      let natural = items.map { measuredSize(of: $0, in: content.size, axis: scrollAxis) }
      let allocatedWidths = NativeLinearLayout.allocateWeighted(
        available: NativeLinearLayout.collapsibleWeightSpace(
          extent: extent, count: items.count,
          spacing: node.isCollapsible ? scaledSpacing : 0),
        naturalSizes: natural.map(\.width),
        weights: items.map { child in
          guard child.node.widthType == 3 else { return nil }
          return max(child.node.widthValue, .leastNonzeroMagnitude)
        })
      let widths = zip(items, zip(natural, allocatedWidths)).map { child, values in
        child.applyDimensions(
          to: values.0,
          available: CGSize(width: values.1, height: content.height)
        ).width
      }
      let positions = NativeLinearLayout.positions(
        total: extent,
        sizes: widths,
        positioning: node.horizontalPositioning,
        spacing: scaledSpacing,
        direction: layoutDirection)
      for (index, child) in items.enumerated() {
        // Weighted children must see their final main-axis constraint before cross-axis placement;
        // wrapping text can be taller at its allocated width than at the row's full width.
        let remeasured =
          child.preferredSize(in: CGSize(width: widths[index], height: content.height))
        let size = CGSize(width: widths[index], height: remeasured.height)
        let y: CGFloat
        switch node.verticalPositioning {
        case 2: y = content.midY - size.height / 2
        case 5: y = content.maxY - size.height
        default: y = content.minY
        }
        child.frame = child.offsetFrame(
          CGRect(
            x: content.minX + positions[index], y: y, width: size.width, height: size.height))
      }
    }

    private func alignedFrame(size: CGSize, in rect: CGRect) -> CGRect {
      let x = horizontalOrigin(for: size.width, in: rect)
      let y: CGFloat
      switch node.verticalPositioning {
      case 2: y = rect.midY - size.height / 2
      case 5: y = rect.maxY - size.height
      default: y = rect.minY
      }
      return CGRect(origin: CGPoint(x: x, y: y), size: size)
    }

    private func horizontalOrigin(for width: CGFloat, in rect: CGRect) -> CGFloat {
      switch node.horizontalPositioning {
      case 2: return rect.midX - width / 2
      case 3: return layoutDirection == .rightToLeft ? rect.minX : rect.maxX - width
      default: return layoutDirection == .rightToLeft ? rect.maxX - width : rect.minX
      }
    }

    private func applyDimensions(to intrinsic: CGSize, available: CGSize) -> CGSize {
      return CGSize(
        width: NativeLayoutDimension(
          type: node.widthType,
          value: node.widthValue * (node.widthType == 6 ? layoutDensityScale : documentScale),
          minimum: node.minimumWidth * dimensionConstraintScale,
          maximum: node.maximumWidth.map { $0 * dimensionConstraintScale }
        ).resolve(intrinsic: intrinsic.width, available: available.width),
        height: NativeLayoutDimension(
          type: node.heightType,
          value: node.heightValue * (node.heightType == 6 ? layoutDensityScale : documentScale),
          minimum: node.minimumHeight * dimensionConstraintScale,
          maximum: node.maximumHeight.map { $0 * dimensionConstraintScale }
        ).resolve(intrinsic: intrinsic.height, available: available.height))
    }

    private func offsetFrame(_ frame: CGRect) -> CGRect {
      frame.offsetBy(
        dx: node.offset.x * documentScale,
        dy: node.offset.y * documentScale)
    }

    private static func makeSemanticView(
      for node: NativeNode,
      onGesture: @escaping (Int, NativeSwiftGestureKind, NativeSwiftPointerSample?) -> Void
    ) -> UIView? {
      guard let behavior = node.semanticBehavior else { return nil }
      let descriptor = behavior.descriptor
      let view: UIView
      switch descriptor.elementKind {
      case .button: view = NativeSemanticButton(componentID: behavior.componentID)
      case .toggle: view = NativeSemanticSwitch(componentID: behavior.componentID)
      case .image: view = NativeSemanticImageView(componentID: behavior.componentID)
      default:
        view = NativeSemanticControl(
          componentID: behavior.componentID,
          kind: descriptor.elementKind)
      }
      configureSemanticView(view, node: node, behavior: behavior, onGesture: onGesture)
      return view
    }

    private func updateSemanticView(_ view: UIView?, from node: NativeNode) {
      guard let view, let behavior = node.semanticBehavior else { return }
      Self.configureSemanticView(view, node: node, behavior: behavior, onGesture: onGesture)
    }

    private static func configureSemanticView(
      _ view: UIView,
      node: NativeNode,
      behavior: NativeSemanticBehavior,
      onGesture: @escaping (Int, NativeSwiftGestureKind, NativeSwiftPointerSample?) -> Void
    ) {
      let descriptor = behavior.descriptor
      let action =
        descriptor.isEnabled && behavior.clickActionTypes.contains(NativeSwiftGestureKind.tap.rawValue)
        ? { componentID in onGesture(componentID, .tap, nil) } : nil
      if let activating = view as? any NativeSemanticActivating {
        activating.componentID = behavior.componentID
        activating.action = action
      }
      if let control = view as? UIControl { control.isEnabled = descriptor.isEnabled }
      view.backgroundColor = .clear
      // Semantic-only elements remain in the accessibility tree without swallowing pointer input.
      view.isUserInteractionEnabled = false
      let mergedLabels = node.localAccessibilityLabels + node.descendantAccessibilityLabels
      let label = descriptor.resolvedLabel(descendantLabels: mergedLabels)
      let traits = accessibilityTraits(for: descriptor)
      view.isAccessibilityElement =
        label != nil || descriptor.stateDescription != nil || !traits.isEmpty || action != nil
      view.accessibilityLabel = label
      view.accessibilityValue = descriptor.stateDescription
      view.accessibilityTraits = traits
      view.accessibilityIdentifier =
        "rc-native-\(String(describing: descriptor.elementKind))-\(node.componentID)"
    }

    private static func accessibilityTraits(
      for descriptor: NativeAccessibilityDescriptor
    ) -> UIAccessibilityTraits {
      var traits: UIAccessibilityTraits
      switch descriptor.elementKind {
      case .button, .checkbox, .toggle, .radioButton, .tab, .dropdownList: traits = .button
      case .image: traits = .image
      case .picker, .carousel, .generic: traits = []
      }
      if descriptor.isClickable { traits.insert(.button) }
      if !descriptor.isEnabled { traits.insert(.notEnabled) }
      return traits
    }
  }

  @MainActor
  private protocol NativeSemanticActivating: AnyObject {
    var componentID: Int { get set }
    var action: ((Int) -> Void)? { get set }
  }

  private final class NativeSemanticButton: UIButton, NativeSemanticActivating {
    var componentID: Int
    var action: ((Int) -> Void)?

    init(componentID: Int) {
      self.componentID = componentID
      action = nil
      super.init(frame: .zero)
      addTarget(self, action: #selector(activate), for: .touchUpInside)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
      fatalError("init(coder:) is not supported")
    }

    @objc private func activate() {
      guard isEnabled else { return }
      action?(componentID)
    }

    override func accessibilityActivate() -> Bool {
      guard isEnabled, let action else { return false }
      action(componentID)
      return true
    }
  }

  private final class NativeSemanticSwitch: UISwitch, NativeSemanticActivating {
    var componentID: Int
    var action: ((Int) -> Void)?

    init(componentID: Int) {
      self.componentID = componentID
      action = nil
      super.init(frame: .zero)
      // The document still owns pixels. The native switch owns identity, focus, value semantics,
      // and activation without drawing a second switch over the captured document appearance.
      layer.opacity = 0
      addTarget(self, action: #selector(activate), for: .valueChanged)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
      fatalError("init(coder:) is not supported")
    }

    @objc private func activate() {
      guard isEnabled else { return }
      action?(componentID)
    }

    override func accessibilityActivate() -> Bool {
      guard isEnabled, let action else { return false }
      action(componentID)
      return true
    }
  }

  private final class NativeSemanticImageView: UIImageView, NativeSemanticActivating {
    var componentID: Int
    var action: ((Int) -> Void)?

    init(componentID: Int) {
      self.componentID = componentID
      action = nil
      super.init(frame: .zero)
      addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(activate)))
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
      fatalError("init(coder:) is not supported")
    }

    @objc private func activate() {
      action?(componentID)
    }

    override func accessibilityActivate() -> Bool {
      guard let action else { return false }
      action(componentID)
      return true
    }
  }

  private final class NativeSemanticControl: UIControl, NativeSemanticActivating {
    var componentID: Int
    var action: ((Int) -> Void)?
    let kind: NativeAccessibilityElementKind

    init(componentID: Int, kind: NativeAccessibilityElementKind) {
      self.componentID = componentID
      self.kind = kind
      action = nil
      super.init(frame: .zero)
      addTarget(self, action: #selector(activate), for: .touchUpInside)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
      fatalError("init(coder:) is not supported")
    }

    @objc private func activate() {
      guard isEnabled else { return }
      action?(componentID)
    }

    override func accessibilityActivate() -> Bool {
      guard isEnabled, let action else { return false }
      action(componentID)
      return true
    }
  }

  final class NativeImageView: UIImageView {
    private var drawCommand: NativeImageDraw
    private var filterQuality: Int?

    init(image: UIImage, draw: NativeImageDraw, alpha: CGFloat, filterQuality: Int?) {
      drawCommand = draw
      self.filterQuality = filterQuality
      super.init(image: image)
      self.alpha = alpha
      clipsToBounds = true
      contentMode = .redraw
      isAccessibilityElement = draw.contentDescription != nil
      accessibilityLabel = draw.contentDescription
      accessibilityIdentifier = "rc-native-image-\(draw.imageID)"
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
      fatalError("init(coder:) is not supported")
    }

    func update(
      image: UIImage, draw: NativeImageDraw, alpha: CGFloat, filterQuality: Int?
    ) {
      self.image = image
      drawCommand = draw
      self.filterQuality = filterQuality
      self.alpha = alpha
      isAccessibilityElement = draw.contentDescription != nil
      accessibilityLabel = draw.contentDescription
      accessibilityIdentifier = "rc-native-image-\(draw.imageID)"
      setNeedsDisplay()
    }

    override func draw(_ rect: CGRect) {
      guard let image else { return }
      let source = CGRect(origin: .zero, size: image.size)
      let destination = NativeImageGeometry.destination(
        source: source,
        destination: bounds,
        scaleType: drawCommand.scaleType,
        scaleFactor: drawCommand.scaleFactor)
      if let quality = NativeTexturePolicy.interpolationQuality(forFilterQuality: filterQuality),
        let context = UIGraphicsGetCurrentContext()
      {
        context.interpolationQuality = quality
      }
      image.draw(in: destination)
    }
  }

  /// A Remote Compose text primitive promoted to a real UIKit text element. Geometry and font
  /// selection remain approximate until the native lane has a resolved layout/text profile.
  final class NativeTextLabel: UILabel {
    private var command: NativeDrawCommand
    private var fontNames: [Int: String]
    private var documentScale: CGFloat = 1
    private var layoutDirection: NativeLayoutDirection = .leftToRight

    init(
      componentID: Int, commandIndex: Int, command: NativeDrawCommand,
      fontNames: [Int: String]
    ) {
      self.command = command
      self.fontNames = fontNames
      super.init(frame: .zero)
      text = command.text
      textColor = command.color.withAlphaComponent(command.alpha)
      backgroundColor = .clear
      configureParagraph(layoutDirection: .leftToRight)
      adjustsFontForContentSizeCategory = true
      isAccessibilityElement = true
      accessibilityIdentifier = "rc-native-text-\(componentID)-\(commandIndex)"
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
      fatalError("init(coder:) is not supported")
    }

    func update(command: NativeDrawCommand, fontNames: [Int: String]) {
      self.command = command
      self.fontNames = fontNames
      text = command.text
      textColor = command.color.withAlphaComponent(command.alpha)
      configureParagraph(layoutDirection: layoutDirection)
      configureFont(documentScale: documentScale)
      setNeedsLayout()
    }

    func preferredSize(maximumWidth: CGFloat, documentScale: CGFloat) -> CGSize {
      self.documentScale = documentScale
      configureFont(documentScale: documentScale)
      guard let attributedText, maximumWidth > 0 else { return .zero }
      let storage = NSTextStorage(attributedString: attributedText)
      let manager = NSLayoutManager()
      let container = NSTextContainer(
        size: CGSize(width: maximumWidth, height: .greatestFiniteMagnitude))
      container.lineFragmentPadding = 0
      container.maximumNumberOfLines = numberOfLines
      container.lineBreakMode = lineBreakMode
      manager.addTextContainer(container)
      storage.addLayoutManager(manager)
      manager.ensureLayout(for: container)
      let measured = manager.usedRect(for: container)
      return CGSize(
        width: min(ceil(measured.width), maximumWidth),
        height: ceil(measured.height))
    }

    func layoutInComponent(
      bounds: CGRect, documentScale: CGFloat, layoutDirection: NativeLayoutDirection
    ) {
      configureParagraph(layoutDirection: layoutDirection)
      let preferred = preferredSize(maximumWidth: bounds.width, documentScale: documentScale)
      frame = CGRect(
        origin: .zero,
        size: CGSize(width: bounds.width, height: min(preferred.height, bounds.height)))
    }

    private func configureFont(documentScale: CGFloat) {
      font = NativeTextAttributes.font(
        for: command, scale: documentScale, fontNames: fontNames, scalesForDynamicType: true)
      attributedText = NativeTextAttributes.string(
        for: command, font: font, scale: documentScale,
        layoutDirection: effectiveUserInterfaceLayoutDirection)
    }

    private func configureParagraph(layoutDirection: NativeLayoutDirection) {
      self.layoutDirection = layoutDirection
      let style = command.textStyle
      numberOfLines = NativeTextPolicy.numberOfLines(
        overflow: style.overflow, maximum: style.maxLines)
      switch NativeTextPolicy.lineBreak(overflow: style.overflow) {
      case .clip: lineBreakMode = .byClipping
      case .wordWrap: lineBreakMode = .byWordWrapping
      case .tail: lineBreakMode = .byTruncatingTail
      case .head: lineBreakMode = .byTruncatingHead
      case .middle: lineBreakMode = .byTruncatingMiddle
      }
      clipsToBounds = style.overflow != 2
      semanticContentAttribute =
        layoutDirection == .rightToLeft ? .forceRightToLeft : .forceLeftToRight
    }
  }

  enum NativeTextAttributes {
    static func font(
      for command: NativeDrawCommand,
      scale: CGFloat,
      fontNames: [Int: String] = [:],
      scalesForDynamicType: Bool = false
    ) -> UIFont {
      let weightValue =
        command.textStyle.fontStyle & 1 != 0 ? max(command.textWeight, 700) : command.textWeight
      let normalizedWeight = min(max((weightValue - 400) / 500, -1), 1)
      let size = max(command.textSize * scale, 1)
      let base = UIFont.systemFont(ofSize: size, weight: UIFont.Weight(rawValue: normalizedWeight))
      var descriptor = base.fontDescriptor
      let familyName = command.textStyle.fontFamilyName
      switch familyName?.lowercased() {
      case "serif": descriptor = descriptor.withDesign(.serif) ?? descriptor
      case "monospace": descriptor = descriptor.withDesign(.monospaced) ?? descriptor
      case "sans-serif", "default", nil: break
      default: break
      }
      if let postScriptName = fontNames[command.textStyle.fontFamilyID],
        let embedded = UIFont(name: postScriptName, size: size)
      {
        descriptor = weightedDescriptor(
          embedded.fontDescriptor, weight: weightValue, size: size)
      }
      if command.textStyle.fontStyle & 2 != 0 {
        descriptor =
          descriptor.withSymbolicTraits(descriptor.symbolicTraits.union(.traitItalic)) ?? descriptor
      }
      let resolved = UIFont(descriptor: descriptor, size: size)
      return scalesForDynamicType ? UIFontMetrics.default.scaledFont(for: resolved) : resolved
    }

    /// A resolved face that keeps the run's weight.
    ///
    /// A document carries its weight on the text run, so a family resolved once is asked for several
    /// weights. Replacing the descriptor with the face wholesale discarded the weight, which made
    /// every run render at the face's default. A variable face expresses the weight through its
    /// `wght` axis; a static instance has no axis to set, so the weight is carried as the bold
    /// symbolic trait instead.
    private static func weightedDescriptor(
      _ descriptor: UIFontDescriptor, weight: CGFloat, size: CGFloat
    ) -> UIFontDescriptor {
      if let varied = RemoteComposeFontVariation.descriptor(
        descriptor as CTFontDescriptor, applyingWeight: weight)
      {
        return varied as UIFontDescriptor
      }
      let existing = CTFontSymbolicTraits(rawValue: descriptor.symbolicTraits.rawValue)
      let traits = RemoteComposeFontVariation.symbolicTraits(
        forWeight: weight, existing: existing)
      guard traits.rawValue != existing.rawValue else { return descriptor }
      return descriptor.withSymbolicTraits(
        UIFontDescriptor.SymbolicTraits(rawValue: traits.rawValue)) ?? descriptor
    }

    static func string(
      for command: NativeDrawCommand,
      font: UIFont,
      scale: CGFloat,
      layoutDirection: UIUserInterfaceLayoutDirection
    ) -> NSAttributedString {
      let paragraph = NSMutableParagraphStyle()
      paragraph.alignment = alignment(
        command.textStyle, layoutDirection: layoutDirection)
      paragraph.lineHeightMultiple = max(command.textStyle.lineHeightMultiplier, 0)
      paragraph.lineSpacing = command.textStyle.lineHeightAdd * scale
      paragraph.hyphenationFactor = command.textStyle.hyphenation > 0 ? 1 : 0
      // Foundation's truncating paragraph modes measure the entire value as one line. UIKit owns
      // last-line truncation through UILabel.lineBreakMode; the attributed paragraph must still
      // wrap when Remote Compose permits multiple lines.
      paragraph.lineBreakMode =
        command.textStyle.maxLines > 1
        ? .byWordWrapping : lineBreakMode(command.textStyle.overflow)
      var attributes: [NSAttributedString.Key: Any] = [
        .font: font,
        .foregroundColor: command.color.withAlphaComponent(command.alpha),
        .paragraphStyle: paragraph,
        .kern: command.textStyle.letterSpacing * font.pointSize,
      ]
      if command.textStyle.isUnderlined {
        attributes[.underlineStyle] = NSUnderlineStyle.single.rawValue
      }
      if command.textStyle.isStruckThrough {
        attributes[.strikethroughStyle] = NSUnderlineStyle.single.rawValue
      }
      if command.isStroke {
        attributes[.strokeColor] = command.color.withAlphaComponent(command.alpha)
        attributes[.strokeWidth] = max(command.strokeWidth / font.pointSize * 100, 0.1)
      }
      return NSAttributedString(string: command.text ?? "", attributes: attributes)
    }

    private static func alignment(
      _ style: NativeTextStyle, layoutDirection: UIUserInterfaceLayoutDirection
    ) -> NSTextAlignment {
      switch NativeTextPolicy.alignment(
        value: style.alignment,
        justified: style.isJustified,
        direction: layoutDirection == .rightToLeft ? .rightToLeft : .leftToRight
      ) {
      case .left: return .left
      case .right: return .right
      case .center: return .center
      case .justified: return .justified
      }
    }

    private static func lineBreakMode(_ overflow: Int) -> NSLineBreakMode {
      switch NativeTextPolicy.lineBreak(overflow: overflow) {
      case .clip: return .byClipping
      case .wordWrap: return .byWordWrapping
      case .tail: return .byTruncatingTail
      case .head: return .byTruncatingHead
      case .middle: return .byTruncatingMiddle
      }
    }
  }

  final class NativeCanvasView: UIView {
    private var commands: [NativeDrawCommand]
    private var images: [Int: UIImage]
    private var fontNames: [Int: String]
    private var renderSignature: Int
    var documentScale: CGFloat = 1 {
      didSet { if documentScale != oldValue { setNeedsDisplay() } }
    }

    init(commands: [NativeDrawCommand], images: [Int: UIImage], fontNames: [Int: String]) {
      self.commands = commands
      self.images = images
      self.fontNames = fontNames
      renderSignature = Self.signature(commands: commands, images: images, fontNames: fontNames)
      super.init(frame: .zero)
      isOpaque = false
      backgroundColor = .clear
      contentMode = .redraw
      isUserInteractionEnabled = false
      accessibilityIdentifier = "rc-native-canvas"
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
      fatalError("init(coder:) is not supported")
    }

    func update(
      commands: [NativeDrawCommand], images: [Int: UIImage], fontNames: [Int: String]
    ) {
      let nextSignature = Self.signature(commands: commands, images: images, fontNames: fontNames)
      self.commands = commands
      self.images = images
      self.fontNames = fontNames
      guard nextSignature != renderSignature else { return }
      renderSignature = nextSignature
      setNeedsDisplay()
    }

    private static func signature(
      commands: [NativeDrawCommand], images: [Int: UIImage], fontNames: [Int: String]
    ) -> Int {
      var hasher = Hasher()
      for command in commands {
        hasher.combine(command.kind)
        command.values.forEach { hasher.combine($0) }
        command.color.cgColor.components?.forEach { hasher.combine($0) }
        hasher.combine(command.alpha)
        hasher.combine(command.strokeWidth)
        hasher.combine(command.isStroke)
        hasher.combine(command.strokeCap)
        hasher.combine(command.strokeJoin)
        hasher.combine(command.blendMode)
        hasher.combine(command.usesComponentGeometry)
        hasher.combine(command.textSize)
        hasher.combine(command.textWeight)
        hasher.combine(command.text)
        hasher.combine(command.textStyle.fontStyle)
        hasher.combine(command.textStyle.fontFamilyID)
        hasher.combine(command.textStyle.fontFamilyName)
        hasher.combine(command.textStyle.alignment)
        hasher.combine(command.textStyle.overflow)
        hasher.combine(command.textStyle.maxLines)
        hasher.combine(command.textStyle.letterSpacing)
        hasher.combine(command.textStyle.lineHeightAdd)
        hasher.combine(command.textStyle.lineHeightMultiplier)
        hasher.combine(command.textStyle.breakStrategy)
        hasher.combine(command.textStyle.hyphenation)
        hasher.combine(command.textStyle.isJustified)
        hasher.combine(command.textStyle.isUnderlined)
        hasher.combine(command.textStyle.isStruckThrough)
        for segment in command.path {
          hasher.combine(segment.kind)
          segment.values.forEach { hasher.combine($0) }
        }
        hasher.combine(command.pathWinding)
        if let gradient = command.gradient {
          hasher.combine(gradient.kind)
          gradient.colors.forEach { $0.components?.forEach { hasher.combine($0) } }
          gradient.stops.forEach { hasher.combine($0) }
          gradient.values.forEach { hasher.combine($0) }
          hasher.combine(gradient.tileMode)
        }
        if let image = command.image {
          hasher.combine(image.imageID)
          hasher.combine(image.source.origin.x)
          hasher.combine(image.source.origin.y)
          hasher.combine(image.source.size.width)
          hasher.combine(image.source.size.height)
          hasher.combine(image.destination.origin.x)
          hasher.combine(image.destination.origin.y)
          hasher.combine(image.destination.size.width)
          hasher.combine(image.destination.size.height)
          hasher.combine(image.scaleType)
          hasher.combine(image.scaleFactor)
        }
        hasher.combine(command.textureImageID)
        hasher.combine(command.textureTileModeX)
        hasher.combine(command.textureTileModeY)
        command.shaderMatrix?.forEach { hasher.combine($0) }
        hasher.combine(command.filterQuality)
      }
      for id in commands.compactMap({ $0.image?.imageID ?? $0.textureImageID }).sorted() {
        hasher.combine(id)
        if let image = images[id] { hasher.combine(ObjectIdentifier(image)) }
      }
      for (id, name) in fontNames.sorted(by: { $0.key < $1.key }) {
        hasher.combine(id)
        hasher.combine(name)
      }
      return hasher.finalize()
    }

    override func draw(_ rect: CGRect) {
      guard let context = UIGraphicsGetCurrentContext() else { return }
      context.scaleBy(x: documentScale, y: documentScale)
      defaultInterpolationQuality = context.interpolationQuality
      commands.forEach { draw($0, in: context) }
    }

    /// The context's own interpolation, captured before any command changes it.
    private var defaultInterpolationQuality: CGInterpolationQuality = .default

    private func draw(_ command: NativeDrawCommand, in context: CGContext) {
      let v = command.values
      context.setAlpha(command.alpha)
      context.setStrokeColor(command.color.cgColor)
      context.setFillColor(command.color.cgColor)
      NativeGraphicsState.apply(
        to: context,
        strokeWidth: command.strokeWidth,
        strokeCap: command.strokeCap,
        strokeJoin: command.strokeJoin,
        blendMode: command.blendMode)
      // Filter quality is paint state, so a command that does not name one takes the context's
      // default rather than whatever the previous command left behind.
      context.interpolationQuality = command.interpolationQuality ?? defaultInterpolationQuality

      switch command.kind {
      case 0: context.saveGState()
      case 1: context.restoreGState()
      case 2: context.translateBy(x: v[0], y: v[1])
      case 3:
        let pivot = CGPoint(x: v[2].isNaN ? 0 : v[2], y: v[3].isNaN ? 0 : v[3])
        context.translateBy(x: pivot.x, y: pivot.y)
        context.scaleBy(x: v[0], y: v[1])
        context.translateBy(x: -pivot.x, y: -pivot.y)
      case 4:
        let pivot = CGPoint(x: v[1].isNaN ? 0 : v[1], y: v[2].isNaN ? 0 : v[2])
        context.translateBy(x: pivot.x, y: pivot.y)
        context.rotate(by: v[0] * .pi / 180)
        context.translateBy(x: -pivot.x, y: -pivot.y)
      case 5: context.concatenate(CGAffineTransform(a: 1, b: v[1], c: v[0], d: 1, tx: 0, ty: 0))
      case 6: context.clip(to: CGRect(x: v[0], y: v[1], width: v[2] - v[0], height: v[3] - v[1]))
      case 7:
        context.addPath(NativePathBuilder.make(command.path))
        context.clip(using: command.pathWinding == 1 ? .evenOdd : .winding)
      case 10:
        paint(CGRect(x: v[0], y: v[1], width: v[2] - v[0], height: v[3] - v[1]), command, context)
      case 11:
        paintEllipse(
          CGRect(x: v[0], y: v[1], width: v[2] - v[0], height: v[3] - v[1]), command, context)
      case 12:
        paintEllipse(
          CGRect(x: v[0] - v[2], y: v[1] - v[2], width: v[2] * 2, height: v[2] * 2), command,
          context)
      case 13:
        let path = CGMutablePath()
        path.move(to: CGPoint(x: v[0], y: v[1]))
        path.addLine(to: CGPoint(x: v[2], y: v[3]))
        paint(path, command, context)
      case 14:
        let path = UIBezierPath(
          roundedRect: CGRect(x: v[0], y: v[1], width: v[2] - v[0], height: v[3] - v[1]),
          cornerRadius: max(v[4], v[5]))
        paint(path.cgPath, command, context)
      case 15, 16: drawArc(command, context)
      case 17: drawText(command)
      case 18:
        paint(
          NativePathBuilder.make(command.path), command, context,
          fillRule: command.pathWinding == 1 ? .evenOdd : .winding)
      case 19: drawImage(command, context)
      default: break
      }
    }

    private func paint(_ rect: CGRect, _ command: NativeDrawCommand, _ context: CGContext) {
      paint(CGPath(rect: rect, transform: nil), command, context)
    }

    private func paintEllipse(_ rect: CGRect, _ command: NativeDrawCommand, _ context: CGContext) {
      paint(CGPath(ellipseIn: rect, transform: nil), command, context)
    }

    private func paint(
      _ path: CGPath,
      _ command: NativeDrawCommand,
      _ context: CGContext,
      fillRule: CGPathFillRule = .winding
    ) {
      // Destination leaves the existing buffer unchanged, but must not suppress ordered
      // transforms, clipping, or save/restore commands around the draw.
      guard command.blendMode != 2 else { return }
      // Component-value expressions are resolved before UIKit performs its intrinsic-size pass, so
      // a background path built from them is stale in whichever dimension layout later decided.
      // The owning component has the final bounds now; use them for this background case and let
      // the component's rounded clip preserve its shape.
      //
      // Either dimension can be the stale one, and both can be at once. This originally required
      // the width to match and only rescued a stale height, which covered a wrap-content component
      // whose height had not been measured yet. A component that is stale in width as well fell
      // straight through to the pre-layout path: on the catalog corpus that drew `button-filled`
      // and its filled siblings at roughly half size, anchored at the right origin, while the
      // label drawn over them sat at full scale.
      //
      // The origin still has to match. That is what separates a background whose size layout has
      // not yet decided from a shape genuinely inset within its component, which must be left
      // alone.
      let pathBounds = path.boundingBoxOfPath
      let isDeferredComponentBackground =
        !command.isStroke && command.usesComponentGeometry
        && abs(pathBounds.minX - bounds.minX) <= 1 && abs(pathBounds.minY - bounds.minY) <= 1
        && (abs(pathBounds.width - bounds.width) > 1 || abs(pathBounds.height - bounds.height) > 1)
      let isDeferredShaderBackground =
        !command.isStroke && (command.textureImageID != nil || command.gradient != nil)
        && (pathBounds.width <= 0 || pathBounds.height <= 0) && !bounds.isEmpty
      let effectivePath =
        isDeferredComponentBackground || isDeferredShaderBackground
        ? CGPath(rect: bounds, transform: nil) : path
      context.addPath(effectivePath)
      if let textureImageID = command.textureImageID, let image = images[textureImageID]?.cgImage {
        context.saveGState()
        if command.isStroke { context.replacePathWithStrokedPath() }
        context.clip(using: command.isStroke ? .winding : fillRule)
        NativeTexturePolicy.paint(
          image: image,
          transform: command.textureTransform,
          tileModeX: command.textureTileModeX,
          tileModeY: command.textureTileModeY,
          in: context)
        context.restoreGState()
        return
      }
      guard let gradient = command.gradient else {
        context.drawPath(
          using: command.isStroke ? .stroke : (fillRule == .evenOdd ? .eoFill : .fill))
        return
      }
      context.saveGState()
      if command.isStroke { context.replacePathWithStrokedPath() }
      context.clip(using: command.isStroke ? .winding : fillRule)
      NativeGradientRenderer.draw(gradient, in: context)
      context.restoreGState()
    }

    private func drawArc(_ command: NativeDrawCommand, _ context: CGContext) {
      let v = command.values
      let center = CGPoint(x: (v[0] + v[2]) / 2, y: (v[1] + v[3]) / 2)
      let radius = min(v[2] - v[0], v[3] - v[1]) / 2
      let start = v[4] * .pi / 180
      let end = (v[4] + v[5]) * .pi / 180
      if command.kind == 16 { context.move(to: center) }
      context.addArc(
        center: center, radius: radius, startAngle: start, endAngle: end, clockwise: false)
      if command.kind == 16 { context.closePath() }
      let path = context.path
      context.beginPath()
      if let path { paint(path, command, context) }
    }

    private func drawText(_ command: NativeDrawCommand) {
      guard command.blendMode != 2 else { return }
      guard command.text != nil, let context = UIGraphicsGetCurrentContext() else { return }
      let font = NativeTextAttributes.font(for: command, scale: 1, fontNames: fontNames)
      let attributed = NativeTextAttributes.string(
        for: command, font: font, scale: 1,
        layoutDirection: effectiveUserInterfaceLayoutDirection)
      let line = CTLineCreateWithAttributedString(attributed)
      var ascent: CGFloat = 0
      var descent: CGFloat = 0
      var leading: CGFloat = 0
      let width = CGFloat(
        CTLineGetTypographicBounds(line, &ascent, &descent, &leading))
      let height = ascent + descent + leading
      let panX = command.values[2]
      let panY = command.values[3]
      let x = command.values[0] - width * ((panX + 1) / 2)
      let baseline = command.values[1] - height * ((panY + 1) / 2)
      context.saveGState()
      context.textMatrix = .identity
      context.translateBy(x: 0, y: baseline * 2)
      context.scaleBy(x: 1, y: -1)
      context.textPosition = CGPoint(x: x, y: baseline)
      CTLineDraw(line, context)
      context.restoreGState()
    }

    private func drawImage(_ command: NativeDrawCommand, _ context: CGContext) {
      guard
        command.blendMode != 2,
        let draw = command.image,
        let source = images[draw.imageID]?.cgImage,
        let cropped = source.cropping(to: draw.source)
      else { return }
      let destination = NativeImageGeometry.destination(
        source: draw.source,
        destination: draw.destination,
        scaleType: draw.scaleType,
        scaleFactor: draw.scaleFactor)
      guard !destination.isEmpty else { return }
      context.saveGState()
      context.clip(to: draw.destination)
      UIImage(cgImage: cropped, scale: 1, orientation: .up).draw(
        in: destination,
        blendMode: NativeGraphicsState.blendMode(command.blendMode),
        alpha: 1)
      context.restoreGState()
    }

  }

  extension UIColor {
    fileprivate convenience init(remoteComposeARGB value: UInt32) {
      self.init(
        red: CGFloat((value >> 16) & 0xff) / 255,
        green: CGFloat((value >> 8) & 0xff) / 255,
        blue: CGFloat(value & 0xff) / 255,
        alpha: CGFloat((value >> 24) & 0xff) / 255)
    }
  }
#endif
