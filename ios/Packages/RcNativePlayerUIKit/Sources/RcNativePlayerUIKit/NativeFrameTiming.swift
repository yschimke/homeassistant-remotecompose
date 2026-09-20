import Foundation

#if canImport(RcNativePlayerCore)
  import RcNativePlayerCore
#endif

/// Injectable monotonic time source used by the native player's animation timeline.
public protocol RemoteComposeNativePlayerClock: Sendable {
  func now() -> TimeInterval

  /// The host's absolute time, for a document that reads a calendar or time-of-day variable.
  ///
  /// Defaulted to nil so a test clock stays a test clock: a document that never reads those
  /// variables is unaffected, and a test that wants them supplies a fixed instant rather than
  /// inheriting the wall clock and becoming time-dependent.
  func wallClock() -> NativeSwiftWallClock?
}

extension RemoteComposeNativePlayerClock {
  public func wallClock() -> NativeSwiftWallClock? { nil }
}

public struct RemoteComposeNativeSystemClock: RemoteComposeNativePlayerClock, Sendable {
  public init() {}

  public func now() -> TimeInterval { ProcessInfo.processInfo.systemUptime }

  public func wallClock() -> NativeSwiftWallClock? {
    let date = Date()
    let instant = Int64((date.timeIntervalSince1970 * 1000).rounded())
    return NativeSwiftWallClock(
      epochMillis: instant,
      offsetSeconds: TimeZone.current.secondsFromGMT(for: date))
  }
}

enum NativeFrameDriverMode: Equatable, Sendable {
  case idle
  case displayLink
  case wake(after: TimeInterval)
}

struct NativeFrameSchedule: Equatable, Sendable {
  var needsContinuousFrames: Bool
  var requestsNextFrame: Bool
  var wakeAfter: TimeInterval?

  static let idle = NativeFrameSchedule(
    needsContinuousFrames: false, requestsNextFrame: false, wakeAfter: nil)

  func driverMode(isActive: Bool, isVisible: Bool, reduceMotion: Bool) -> NativeFrameDriverMode {
    guard isActive, isVisible else { return .idle }
    if requestsNextFrame { return .displayLink }
    if needsContinuousFrames, !reduceMotion { return .displayLink }
    if let wakeAfter { return wakeAfter <= 0 ? .displayLink : .wake(after: wakeAfter) }
    return .idle
  }
}

/// Retains the remaining active delay while a view is hidden or the application is suspended.
struct NativeWakeCountdown: Equatable, Sendable {
  private(set) var remaining: TimeInterval?
  private var startedAt: TimeInterval?

  mutating func reset(after delay: TimeInterval?) {
    remaining = delay.map { max($0, 0) }
    startedAt = nil
  }

  mutating func pause(at now: TimeInterval) {
    guard let startedAt, let remaining else { return }
    self.remaining = max(remaining - max(now - startedAt, 0), 0)
    self.startedAt = nil
  }

  mutating func start(after fallbackDelay: TimeInterval, at now: TimeInterval) -> TimeInterval {
    pause(at: now)
    if remaining == nil { remaining = max(fallbackDelay, 0) }
    startedAt = now
    return remaining ?? 0
  }

  mutating func complete() {
    remaining = nil
    startedAt = nil
  }
}

/// Accumulates only active monotonic time, so suspension never causes a resumed animation to jump.
struct NativeAnimationTimeline: Equatable, Sendable {
  private(set) var elapsed: TimeInterval = 0
  private var lastActiveTime: TimeInterval?
  private var lastPausedTime: TimeInterval?

  mutating func reset(at now: TimeInterval, active: Bool) {
    elapsed = 0
    lastActiveTime = active ? now : nil
    lastPausedTime = active ? nil : now
  }

  mutating func sample(at now: TimeInterval) -> TimeInterval {
    if let lastActiveTime {
      elapsed += max(now - lastActiveTime, 0)
      self.lastActiveTime = max(now, lastActiveTime)
    } else {
      self.lastActiveTime = now
    }
    return elapsed
  }

  /// Advances logical time for functional one-shot work while decorative animation is paused.
  mutating func sampleFunctional(at now: TimeInterval) -> TimeInterval {
    guard let lastPausedTime else {
      self.lastPausedTime = now
      return elapsed
    }
    elapsed += max(now - lastPausedTime, 0)
    self.lastPausedTime = max(now, lastPausedTime)
    return elapsed
  }

  mutating func advance(to time: TimeInterval, at now: TimeInterval) -> TimeInterval {
    guard time.isFinite else { return elapsed }
    elapsed = max(elapsed, time)
    if lastActiveTime != nil {
      lastActiveTime = now
    } else {
      lastPausedTime = now
    }
    return elapsed
  }

  mutating func pause(at now: TimeInterval) {
    if lastActiveTime != nil { _ = sample(at: now) }
    lastActiveTime = nil
    lastPausedTime = now
  }

  mutating func resume(at now: TimeInterval) {
    guard lastActiveTime == nil else { return }
    lastActiveTime = now
    lastPausedTime = nil
  }
}
