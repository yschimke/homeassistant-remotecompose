#if canImport(UIKit)
  import Foundation
  #if canImport(RcNativePlayerCore)
    import RcNativePlayerCore
  #endif

  public enum RemoteComposeNativePlayerActionValue: Equatable, Sendable {
    case none
    case float(Float)
    case integer(Int)
    case text(String)
    case floatList([Float])
  }

  public enum RemoteComposeNativePlayerEvent: Equatable, Sendable {
    case action(id: Int)
    case actionWithMetadata(id: Int, metadata: String)
    case namedAction(name: String, value: RemoteComposeNativePlayerActionValue)
    case debug(message: String, value: Float, flags: Int)
  }

  /// Serial ownership boundary for the pure-Swift decoder and retained document state.
  actor NativeSnapshotSessionHandle {
    struct Frame: Sendable {
      let snapshot: NativeSwiftDocumentSnapshot
      /// The host's absolute time at the instant this frame was resolved, so a refinement of the
      /// frame resolves its calendar fields against the same instant rather than a later one.
      let wallClock: NativeSwiftWallClock?

      /// A detached session for re-resolving this frame once the host knows its real geometry.
      ///
      /// Carried per frame rather than held once because its state is frozen when taken, and a
      /// document's state moves — a gesture, a set value, the clock. Nil when the document binds no
      /// component geometry, which is almost all of them, so the copy is not paid for unnecessarily.
      let refiner: NativeSwiftDocumentSession?

      init(
        session: NativeSwiftDocumentSession, snapshot: NativeSwiftDocumentSnapshot,
        wallClock: NativeSwiftWallClock?
      ) {
        self.snapshot = snapshot
        self.wallClock = wallClock
        refiner = snapshot.boundComponents.isEmpty ? nil : session.detachedCopy()
      }
    }

    struct Update: Sendable {
      let accepted: Bool
      let frame: Frame
      let events: [RemoteComposeNativePlayerEvent]
    }

    private let session: NativeSwiftDocumentSession
    /// The host's wall clock, as of the last frame. Input updates re-resolve the document, and a
    /// geometry that reads a calendar field has to resolve against the same instant a frame did.
    private var wallClock: NativeSwiftWallClock?

    private init(session: NativeSwiftDocumentSession, wallClock: NativeSwiftWallClock?) {
      self.session = session
      self.wallClock = wallClock
    }

    /// - Parameters:
    ///   - hostDensity: what a deferred-density capture resolves against. Supplied at open rather
    ///     than set afterwards because the first frame is produced here, and a document that reads
    ///     `ID_DENSITY` would otherwise resolve its very first geometry against the wrong value and
    ///     only correct itself on the next frame.
    ///   - wallClock: the host's absolute time, for the first frame. See `NativeSwiftWallClock`.
    static func open(
      data: Data, maximumDocumentBytes: Int, hostDensity: Float = 1, hostFontScale: Float = 1,
      wallClock: NativeSwiftWallClock? = nil
    ) async throws -> (NativeSnapshotSessionHandle, Frame) {
      guard data.count <= maximumDocumentBytes, data.count <= Int(Int32.max) else {
        throw RemoteComposeNativeLimitError.documentTooLarge(
          actual: data.count, maximum: min(maximumDocumentBytes, Int(Int32.max)))
      }
      do {
        try Task.checkCancellation()
        let session = try NativeSwiftDocumentSession.open(data: data)
        session.setHostDensity(hostDensity, fontScale: hostFontScale)
        let frame = Frame(
          session: session,
          snapshot: try session.snapshot(wallClock: wallClock), wallClock: wallClock)
        try Task.checkCancellation()
        return (
          NativeSnapshotSessionHandle(session: session, wallClock: wallClock), frame
        )
      } catch is CancellationError {
        throw CancellationError()
      } catch let error as NativeSwiftCoreError {
        throw RemoteComposeNativePlayerError.decode(error.description)
      } catch {
        throw RemoteComposeNativePlayerError.decode(error.localizedDescription)
      }
    }

    /// Forwards the host's density to the retained document; see `setHostDensity` on the core.
    func setHostDensity(_ density: Float, fontScale: Float) {
      session.setHostDensity(density, fontScale: fontScale)
    }

    func frame(at timeSeconds: TimeInterval, wallClock: NativeSwiftWallClock?) async throws
      -> Frame
    {
      do {
        self.wallClock = wallClock
        return Frame(
          session: session,
          snapshot: try session.snapshot(timeSeconds: timeSeconds, wallClock: wallClock),
          wallClock: wallClock)
      } catch {
        throw RemoteComposeNativePlayerError.decode(error.localizedDescription)
      }
    }

    func click(componentID: Int, at timeSeconds: TimeInterval) throws -> Update {
      guard
        let nativeEvents = try session.click(
          componentID: componentID, timeSeconds: timeSeconds)
      else { return try unchangedUpdate(timeSeconds: timeSeconds) }
      return try eventUpdate(nativeEvents, timeSeconds: timeSeconds)
    }

    func gesture(
      _ kind: NativeSwiftGestureKind, componentID: Int,
      sample: NativeSwiftPointerSample? = nil, at timeSeconds: TimeInterval
    ) throws -> Update {
      guard
        let nativeEvents = try session.gesture(
          kind, componentID: componentID, sample: sample, timeSeconds: timeSeconds)
      else { return try unchangedUpdate(timeSeconds: timeSeconds) }
      return try eventUpdate(nativeEvents, timeSeconds: timeSeconds)
    }

    private func eventUpdate(
      _ nativeEvents: [NativeSwiftEvent], timeSeconds: TimeInterval
    ) throws -> Update {
      let events = nativeEvents.map { event -> RemoteComposeNativePlayerEvent in
        switch event {
        case .namedAction(let name, let value):
          let publicValue: RemoteComposeNativePlayerActionValue
          switch value {
          case .none: publicValue = .none
          case .float(let value): publicValue = .float(value)
          case .integer(let value): publicValue = .integer(value)
          case .text(let value): publicValue = .text(value)
          }
          return .namedAction(name: name, value: publicValue)
        }
      }
      return Update(
        accepted: true,
        frame: Frame(
          session: session,
          snapshot: try session.snapshot(timeSeconds: timeSeconds, wallClock: wallClock),
          wallClock: wallClock),
        events: events)
    }

    func setFloat(_ value: Float, for name: String, at timeSeconds: TimeInterval) throws -> Update {
      return try update(
        accepted: session.setFloat(value, for: name), timeSeconds: timeSeconds)
    }

    func setString(_ value: String, for name: String, at timeSeconds: TimeInterval) throws -> Update
    {
      return try update(
        accepted: session.setString(value, for: name), timeSeconds: timeSeconds)
    }

    func setColor(_ argb: UInt32, for name: String, at timeSeconds: TimeInterval) throws -> Update {
      return try update(
        accepted: session.setColor(argb, for: name), timeSeconds: timeSeconds)
    }

    func returnCustomFloat(
      _ value: Float, componentID: Int, propertyID: Int, at timeSeconds: TimeInterval
    ) throws -> Update {
      let accepted = try session.returnCustomFloat(
        value, componentID: componentID, propertyID: propertyID)
      return try update(accepted: accepted, timeSeconds: timeSeconds)
    }

    func returnCustomText(
      _ value: String, componentID: Int, propertyID: Int, at timeSeconds: TimeInterval
    ) throws -> Update {
      let accepted = try session.returnCustomText(
        value, componentID: componentID, propertyID: propertyID)
      return try update(accepted: accepted, timeSeconds: timeSeconds)
    }

    private func unchangedUpdate(timeSeconds: TimeInterval = 0) throws -> Update {
      try update(accepted: false, timeSeconds: timeSeconds)
    }

    private func update(accepted: Bool, timeSeconds: TimeInterval) throws -> Update {
      Update(
        accepted: accepted,
        frame: Frame(
          session: session,
          snapshot: try session.snapshot(timeSeconds: timeSeconds, wallClock: wallClock),
          wallClock: wallClock),
        events: [])
    }
  }
#endif
