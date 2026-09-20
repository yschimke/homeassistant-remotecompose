import Foundation

/// Work limits applied before a native frame reaches UIKit or Core Graphics.
public struct RemoteComposeNativeExecutionLimits: Equatable, Sendable {
  public var maximumDocumentBytes: Int
  public var maximumNodeCount: Int
  public var maximumNestingDepth: Int
  public var maximumDrawCommandCount: Int
  public var maximumPathElementCount: Int
  public var maximumTextBytes: Int
  public var maximumCanvasDimension: Double
  public var maximumCoordinateMagnitude: Double
  public var maximumFrameWork: Int

  public init(
    maximumDocumentBytes: Int = 16 * 1024 * 1024,
    maximumNodeCount: Int = 20_000,
    maximumNestingDepth: Int = 256,
    maximumDrawCommandCount: Int = 50_000,
    maximumPathElementCount: Int = 100_000,
    maximumTextBytes: Int = 16 * 1024,
    maximumCanvasDimension: Double = 16_384,
    maximumCoordinateMagnitude: Double = 1_000_000,
    maximumFrameWork: Int = 200_000
  ) {
    self.maximumDocumentBytes = maximumDocumentBytes
    self.maximumNodeCount = maximumNodeCount
    self.maximumNestingDepth = maximumNestingDepth
    self.maximumDrawCommandCount = maximumDrawCommandCount
    self.maximumPathElementCount = maximumPathElementCount
    self.maximumTextBytes = maximumTextBytes
    self.maximumCanvasDimension = maximumCanvasDimension
    self.maximumCoordinateMagnitude = maximumCoordinateMagnitude
    self.maximumFrameWork = maximumFrameWork
  }

  public static let `default` = RemoteComposeNativeExecutionLimits()
}

public enum RemoteComposeNativeLimitError: Error, Equatable, LocalizedError, Sendable {
  case invalidLimits
  case documentTooLarge(actual: Int, maximum: Int)
  case tooManyNodes(actual: Int, maximum: Int)
  case nestingTooDeep(actual: Int, maximum: Int)
  case tooManyDrawCommands(actual: Int, maximum: Int)
  case tooManyPathElements(actual: Int, maximum: Int)
  case textTooLong(actual: Int, maximum: Int)
  case invalidCanvasDimension(actual: Double)
  case canvasTooLarge(actual: Double, maximum: Double)
  case nonFiniteValue(componentID: Int, field: String)
  case coordinateTooLarge(componentID: Int, field: String, actual: Double, maximum: Double)
  case invalidDimensionValue(componentID: Int, field: String, actual: Double)
  case invalidGradientStop(componentID: Int, actual: Double)
  case frameWorkExceeded(actual: Int, maximum: Int)

  public var errorDescription: String? {
    switch self {
    case .invalidLimits: return "Native execution limits must be finite and positive"
    case .documentTooLarge(let actual, let maximum):
      return "Document is \(actual) bytes; the native limit is \(maximum)"
    case .tooManyNodes(let actual, let maximum):
      return "Native frame contains \(actual) nodes; the limit is \(maximum)"
    case .nestingTooDeep(let actual, let maximum):
      return "Native frame nesting is \(actual) levels; the limit is \(maximum)"
    case .tooManyDrawCommands(let actual, let maximum):
      return "Native frame contains \(actual) draw commands; the limit is \(maximum)"
    case .tooManyPathElements(let actual, let maximum):
      return "Native frame contains \(actual) path elements; the limit is \(maximum)"
    case .textTooLong(let actual, let maximum):
      return "Native frame text is \(actual) UTF-8 bytes; the limit is \(maximum)"
    case .invalidCanvasDimension(let actual):
      return "Native canvas dimension must be finite and positive; got \(actual)"
    case .canvasTooLarge(let actual, let maximum):
      return "Native canvas dimension is \(actual); the limit is \(maximum)"
    case .nonFiniteValue(let componentID, let field):
      return "Component \(componentID) has a non-finite \(field) value"
    case .coordinateTooLarge(let componentID, let field, let actual, let maximum):
      return "Component \(componentID) has \(field)=\(actual); the magnitude limit is \(maximum)"
    case .invalidDimensionValue(let componentID, let field, let actual):
      return "Component \(componentID) has invalid \(field)=\(actual)"
    case .invalidGradientStop(let componentID, let actual):
      return "Component \(componentID) has gradient stop \(actual); stops must be between 0 and 1"
    case .frameWorkExceeded(let actual, let maximum):
      return "Native frame requires \(actual) work units; the limit is \(maximum)"
    }
  }
}

struct NativeFrameBudget: Equatable, Sendable {
  private(set) var nodes = 0
  private(set) var drawCommands = 0
  private(set) var pathElements = 0
  private(set) var textBytes = 0
  private(set) var work = 0

  static func validate(_ limits: RemoteComposeNativeExecutionLimits) throws {
    guard
      limits.maximumDocumentBytes > 0,
      limits.maximumNodeCount > 0,
      limits.maximumNestingDepth > 0,
      limits.maximumDrawCommandCount > 0,
      limits.maximumPathElementCount > 0,
      limits.maximumTextBytes > 0,
      limits.maximumCanvasDimension.isFinite,
      limits.maximumCanvasDimension > 0,
      limits.maximumCoordinateMagnitude.isFinite,
      limits.maximumCoordinateMagnitude > 0,
      limits.maximumFrameWork > 0
    else { throw RemoteComposeNativeLimitError.invalidLimits }
  }

  mutating func recordNode(
    depth: Int, limits: RemoteComposeNativeExecutionLimits
  ) throws {
    nodes += 1
    work += 1
    guard nodes <= limits.maximumNodeCount else {
      throw RemoteComposeNativeLimitError.tooManyNodes(
        actual: nodes, maximum: limits.maximumNodeCount)
    }
    guard depth <= limits.maximumNestingDepth else {
      throw RemoteComposeNativeLimitError.nestingTooDeep(
        actual: depth, maximum: limits.maximumNestingDepth)
    }
    try validateWork(limits)
  }

  mutating func recordCommand(
    pathElementCount: Int, additionalWork: Int = 0, strings: [String?] = [],
    limits: RemoteComposeNativeExecutionLimits
  ) throws {
    drawCommands += 1
    pathElements += pathElementCount
    work += 1 + pathElementCount + additionalWork
    guard drawCommands <= limits.maximumDrawCommandCount else {
      throw RemoteComposeNativeLimitError.tooManyDrawCommands(
        actual: drawCommands, maximum: limits.maximumDrawCommandCount)
    }
    guard pathElements <= limits.maximumPathElementCount else {
      throw RemoteComposeNativeLimitError.tooManyPathElements(
        actual: pathElements, maximum: limits.maximumPathElementCount)
    }
    try recordStrings(strings, limits: limits)
    try validateWork(limits)
  }

  mutating func recordStrings(
    _ strings: [String?], limits: RemoteComposeNativeExecutionLimits
  ) throws {
    let addedBytes = strings.compactMap { $0 }.reduce(0) { $0 + $1.utf8.count }
    textBytes += addedBytes
    work += addedBytes
    guard textBytes <= limits.maximumTextBytes else {
      throw RemoteComposeNativeLimitError.textTooLong(
        actual: textBytes, maximum: limits.maximumTextBytes)
    }
    try validateWork(limits)
  }

  mutating func recordEvent(
    additionalWork: Int = 0, strings: [String?] = [],
    limits: RemoteComposeNativeExecutionLimits
  ) throws {
    work += 1 + additionalWork
    try recordStrings(strings, limits: limits)
    try validateWork(limits)
  }

  mutating func recordWork(
    _ additionalWork: Int, limits: RemoteComposeNativeExecutionLimits
  ) throws {
    work += additionalWork
    try validateWork(limits)
  }

  func validateNumbers(
    _ values: [Double], componentID: Int, field: String,
    limits: RemoteComposeNativeExecutionLimits
  ) throws {
    for value in values {
      guard value.isFinite else {
        throw RemoteComposeNativeLimitError.nonFiniteValue(
          componentID: componentID, field: field)
      }
      guard abs(value) <= limits.maximumCoordinateMagnitude else {
        throw RemoteComposeNativeLimitError.coordinateTooLarge(
          componentID: componentID, field: field, actual: value,
          maximum: limits.maximumCoordinateMagnitude)
      }
    }
  }

  func validateFinite(_ values: [Double], componentID: Int, field: String) throws {
    for value in values where !value.isFinite {
      throw RemoteComposeNativeLimitError.nonFiniteValue(
        componentID: componentID, field: field)
    }
  }

  func validateGradientStops(_ values: [Double], componentID: Int) throws {
    try validateFinite(values, componentID: componentID, field: "gradient stop")
    for value in values where !(0...1).contains(value) {
      throw RemoteComposeNativeLimitError.invalidGradientStop(
        componentID: componentID, actual: value)
    }
  }

  func validateCanvasDimensions(
    _ values: [Double], limits: RemoteComposeNativeExecutionLimits
  ) throws {
    for value in values {
      guard value.isFinite else {
        throw RemoteComposeNativeLimitError.nonFiniteValue(componentID: 0, field: "canvas")
      }
      guard value >= 0, value <= limits.maximumCanvasDimension else {
        throw RemoteComposeNativeLimitError.canvasTooLarge(
          actual: value, maximum: limits.maximumCanvasDimension)
      }
    }
  }

  func validateLayoutDimension(
    value: Double, type: Int, componentID: Int, field: String,
    limits: RemoteComposeNativeExecutionLimits
  ) throws {
    switch type {
    case 0, 6:
      try validateNumbers([value], componentID: componentID, field: field, limits: limits)
      try validateCanvasDimensions([abs(value)], limits: limits)
    case 1, 7, 8:
      if value.isNaN { return }
      guard value.isFinite, value >= 0 else {
        throw RemoteComposeNativeLimitError.invalidDimensionValue(
          componentID: componentID, field: field, actual: value)
      }
    case 3:
      guard value.isFinite, value >= 0 else {
        throw RemoteComposeNativeLimitError.invalidDimensionValue(
          componentID: componentID, field: field, actual: value)
      }
    default:
      try validateFinite([value], componentID: componentID, field: field)
    }
  }

  func validateDocumentDimensions(
    _ values: [Double], limits: RemoteComposeNativeExecutionLimits
  ) throws {
    for value in values {
      guard value.isFinite, value > 0 else {
        throw RemoteComposeNativeLimitError.invalidCanvasDimension(actual: value)
      }
    }
    try validateCanvasDimensions(values, limits: limits)
  }

  private func validateWork(_ limits: RemoteComposeNativeExecutionLimits) throws {
    guard work <= limits.maximumFrameWork else {
      throw RemoteComposeNativeLimitError.frameWorkExceeded(
        actual: work, maximum: limits.maximumFrameWork)
    }
  }
}
