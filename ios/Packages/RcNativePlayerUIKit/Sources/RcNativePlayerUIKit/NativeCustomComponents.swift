#if canImport(UIKit)
  import Combine
  import UIKit
  #if canImport(SwiftUI)
    import SwiftUI
  #endif

  public protocol RemoteComposeNativeCustomPropertyKey: Sendable {
    var id: Int { get }
  }

  public struct RemoteComposeNativeFloatProperty: RemoteComposeNativeCustomPropertyKey {
    public let id: Int
    public let defaultValue: Float

    public init(_ id: Int, default defaultValue: Float = 0) {
      self.id = id
      self.defaultValue = defaultValue
    }
  }

  public struct RemoteComposeNativeIntegerProperty: RemoteComposeNativeCustomPropertyKey {
    public let id: Int
    public let defaultValue: Int

    public init(_ id: Int, default defaultValue: Int = 0) {
      self.id = id
      self.defaultValue = defaultValue
    }
  }

  public struct RemoteComposeNativeTextProperty: RemoteComposeNativeCustomPropertyKey {
    public let id: Int
    public let defaultValue: String

    public init(_ id: Int, default defaultValue: String = "") {
      self.id = id
      self.defaultValue = defaultValue
    }
  }

  public struct RemoteComposeNativeColorProperty: RemoteComposeNativeCustomPropertyKey {
    public let id: Int
    public let defaultValue: UInt32

    public init(_ id: Int, default defaultValue: UInt32 = 0) {
      self.id = id
      self.defaultValue = defaultValue
    }
  }

  public struct RemoteComposeNativeFloatReturnProperty: RemoteComposeNativeCustomPropertyKey {
    public let id: Int
    public init(_ id: Int) { self.id = id }
  }

  public struct RemoteComposeNativeTextReturnProperty: RemoteComposeNativeCustomPropertyKey {
    public let id: Int
    public init(_ id: Int) { self.id = id }
  }

  public struct RemoteComposeNativeCustomProperty: Equatable, Sendable {
    public enum Kind: Int, Sendable {
      case integer = 0
      case float = 1
      case text = 2
      case floatReturn = 3
      case textReturn = 4
      case colorReference = 7
      case color = 8
      case integerReference = 9
    }

    public let id: Int
    public let dataType: Int
    public let floatValue: Float
    public let integerValue: Int
    public let textValue: String?

    public var kind: Kind? { Kind(rawValue: dataType) }
  }

  enum NativeCustomReturnValue {
    case float(Float)
    case text(String)
  }

  /// Live, observable values for one document-authored custom component.
  ///
  /// Keep this object as the model for a UIKit or hosted SwiftUI view. Its identity is stable while
  /// the Remote Compose component remains in the tree; `objectWillChange` fires before a new
  /// resolved snapshot is applied.
  @MainActor
  public final class RemoteComposeNativeCustomComponent: ObservableObject {
    public let config: String
    public let componentID: Int
    public private(set) var properties: [RemoteComposeNativeCustomProperty]
    private let returnValue: (Int, NativeCustomReturnValue) -> Void

    init(
      config: String,
      componentID: Int,
      properties: [RemoteComposeNativeCustomProperty],
      returnValue: @escaping (Int, NativeCustomReturnValue) -> Void
    ) {
      self.config = config
      self.componentID = componentID
      self.properties = properties
      self.returnValue = returnValue
    }

    public func hasProperty(_ key: some RemoteComposeNativeCustomPropertyKey) -> Bool {
      property(key.id) != nil
    }

    public func float(_ key: RemoteComposeNativeFloatProperty) -> Float {
      property(key.id).flatMap { $0.dataType == 1 ? $0.floatValue : nil } ?? key.defaultValue
    }

    public func integer(_ key: RemoteComposeNativeIntegerProperty) -> Int {
      property(key.id).flatMap { [0, 9].contains($0.dataType) ? $0.integerValue : nil }
        ?? key.defaultValue
    }

    public func text(_ key: RemoteComposeNativeTextProperty) -> String {
      property(key.id).flatMap { $0.dataType == 2 ? $0.textValue : nil } ?? key.defaultValue
    }

    public func color(_ key: RemoteComposeNativeColorProperty) -> UInt32 {
      property(key.id).flatMap {
        [0, 7, 8].contains($0.dataType) ? UInt32(bitPattern: Int32($0.integerValue)) : nil
      }
        ?? key.defaultValue
    }

    /// Enqueue a value for a declared float return channel.
    @discardableResult
    public func send(_ value: Float, to key: RemoteComposeNativeFloatReturnProperty) -> Bool {
      guard value.isFinite, property(key.id)?.dataType == 3 else { return false }
      returnValue(key.id, .float(value))
      return true
    }

    /// Enqueue a value for a declared text return channel.
    @discardableResult
    public func send(_ value: String, to key: RemoteComposeNativeTextReturnProperty) -> Bool {
      guard property(key.id)?.dataType == 4 else { return false }
      returnValue(key.id, .text(value))
      return true
    }

    public func floatReturnHandler(_ key: RemoteComposeNativeFloatReturnProperty) -> (Float) -> Void
    {
      { [weak self] value in self?.send(value, to: key) }
    }

    public func textReturnHandler(_ key: RemoteComposeNativeTextReturnProperty) -> (String) -> Void
    {
      { [weak self] value in self?.send(value, to: key) }
    }

    fileprivate func update(properties: [RemoteComposeNativeCustomProperty]) {
      guard properties != self.properties else { return }
      objectWillChange.send()
      self.properties = properties
    }

    private func property(_ id: Int) -> RemoteComposeNativeCustomProperty? {
      properties.first { $0.id == id }
    }
  }

  /// A custom component with the same make/update/dismantle lifecycle as SwiftUI's representables.
  @MainActor
  public protocol RemoteComposeNativeCustomComponentPlugin {
    associatedtype ComponentView: UIView
    var name: String { get }
    func makeUIView(component: RemoteComposeNativeCustomComponent) -> ComponentView
    func updateUIView(_ view: ComponentView, component: RemoteComposeNativeCustomComponent)
    func dismantleUIView(_ view: ComponentView)
  }

  extension RemoteComposeNativeCustomComponentPlugin {
    public func dismantleUIView(_ view: ComponentView) {}
  }

  @MainActor
  public final class RemoteComposeNativeCustomComponentRegistry {
    fileprivate struct Entry {
      let make: (RemoteComposeNativeCustomComponent) -> UIView
      let update: (UIView, RemoteComposeNativeCustomComponent) -> Void
      let dismantle: (UIView) -> Void
    }

    fileprivate var entries: [String: Entry] = [:]
    private(set) var revision: UInt = 0

    public init() {}

    public convenience init<P: RemoteComposeNativeCustomComponentPlugin>(_ plugin: P) {
      self.init()
      register(plugin)
    }

    public var names: Set<String> { Set(entries.keys) }

    @discardableResult
    public func register<P: RemoteComposeNativeCustomComponentPlugin>(_ plugin: P) -> Self {
      register(
        plugin.name,
        makeUIView: plugin.makeUIView,
        updateUIView: plugin.updateUIView,
        dismantleUIView: plugin.dismantleUIView)
    }

    /// Register closure-based UIKit content without defining a plugin type.
    @discardableResult
    public func register<View: UIView>(
      _ name: String,
      makeUIView: @escaping (RemoteComposeNativeCustomComponent) -> View,
      updateUIView: @escaping (View, RemoteComposeNativeCustomComponent) -> Void = { _, _ in },
      dismantleUIView: @escaping (View) -> Void = { _ in }
    ) -> Self {
      precondition(!name.isEmpty, "Custom component name must not be empty")
      precondition(entries[name] == nil, "Duplicate custom component name: \(name)")
      entries[name] = Entry(
        make: makeUIView,
        update: { view, component in
          guard let view = view as? View else { return }
          updateUIView(view, component)
        },
        dismantle: { view in
          guard let view = view as? View else { return }
          dismantleUIView(view)
        })
      revision &+= 1
      return self
    }

    #if canImport(SwiftUI)
      /// Register SwiftUI content while the player retains a native UIView component hierarchy.
      @discardableResult
      public func registerSwiftUI<Content: View>(
        _ name: String,
        @ViewBuilder content: @escaping (RemoteComposeNativeCustomComponent) -> Content
      ) -> Self {
        register(
          name,
          makeUIView: { component in
            NativeSwiftUICustomComponentView(rootView: AnyView(content(component)))
          },
          updateUIView: { view, component in
            view.update(rootView: AnyView(content(component)))
          })
      }
    #endif
  }

  #if canImport(SwiftUI)
    @MainActor
    private final class NativeSwiftUICustomComponentView: UIView {
      private let hostingController: UIHostingController<AnyView>

      init(rootView: AnyView) {
        hostingController = UIHostingController(rootView: rootView)
        super.init(frame: .zero)
        isOpaque = false
        hostingController.view.backgroundColor = .clear
        addSubview(hostingController.view)
      }

      @available(*, unavailable)
      required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

      func update(rootView: AnyView) {
        hostingController.rootView = rootView
        invalidateIntrinsicContentSize()
      }

      override var intrinsicContentSize: CGSize { hostingController.view.intrinsicContentSize }

      override func sizeThatFits(_ size: CGSize) -> CGSize {
        hostingController.view.sizeThatFits(size)
      }

      override func layoutSubviews() {
        super.layoutSubviews()
        hostingController.view.frame = bounds
      }

      override func didMoveToWindow() {
        super.didMoveToWindow()
        if window != nil, hostingController.parent == nil {
          var responder: UIResponder? = next
          while let current = responder, !(current is UIViewController) {
            responder = current.next
          }
          if let parent = responder as? UIViewController {
            parent.addChild(hostingController)
            hostingController.didMove(toParent: parent)
          }
        } else if window == nil, hostingController.parent != nil {
          hostingController.willMove(toParent: nil)
          hostingController.removeFromParent()
        }
      }
    }
  #endif

  @MainActor
  final class NativeCustomComponentView: UIView {
    let config: String
    private let component: RemoteComposeNativeCustomComponent
    private let entry: RemoteComposeNativeCustomComponentRegistry.Entry
    private let contentView: UIView
    private var isDismantled = false

    init?(
      snapshot: NativeCustomComponent,
      componentID: Int,
      registry: RemoteComposeNativeCustomComponentRegistry,
      onReturn: @escaping (Int, Int, NativeCustomReturnValue) -> Void
    ) {
      guard let entry = registry.entries[snapshot.config] else { return nil }
      config = snapshot.config
      self.entry = entry
      component = RemoteComposeNativeCustomComponent(
        config: snapshot.config, componentID: componentID, properties: snapshot.properties,
        returnValue: { valueType, value in onReturn(componentID, valueType, value) })
      contentView = entry.make(component)
      super.init(frame: .zero)
      isOpaque = false
      addSubview(contentView)
      entry.update(contentView, component)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    func update(_ snapshot: NativeCustomComponent) {
      component.update(properties: snapshot.properties)
      entry.update(contentView, component)
      invalidateIntrinsicContentSize()
      setNeedsLayout()
    }

    override func willMove(toSuperview newSuperview: UIView?) {
      if newSuperview == nil, !isDismantled {
        isDismantled = true
        entry.dismantle(contentView)
      }
      super.willMove(toSuperview: newSuperview)
    }

    override var intrinsicContentSize: CGSize { contentView.intrinsicContentSize }

    override func sizeThatFits(_ size: CGSize) -> CGSize { contentView.sizeThatFits(size) }

    override func layoutSubviews() {
      super.layoutSubviews()
      contentView.frame = bounds
    }
  }
#endif
