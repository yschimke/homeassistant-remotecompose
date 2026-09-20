import CoreGraphics
import Foundation

/// Resource limits applied before UIKit or Core Graphics decodes document-controlled bytes.
public struct RemoteComposeNativeResourceLimits: Equatable, Sendable {
  public var maximumResourceBytes: Int
  public var maximumTotalBytes: Int
  public var maximumImageDimension: Int
  public var maximumDecodedPixels: Int
  public var maximumDecodedImageBytes: Int
  public var maximumResourceCount: Int

  public init(
    maximumResourceBytes: Int = 8 * 1024 * 1024,
    maximumTotalBytes: Int = 24 * 1024 * 1024,
    maximumImageDimension: Int = 4_096,
    maximumDecodedPixels: Int = 16_777_216,
    maximumDecodedImageBytes: Int = 128 * 1024 * 1024,
    maximumResourceCount: Int = 32
  ) {
    self.maximumResourceBytes = maximumResourceBytes
    self.maximumTotalBytes = maximumTotalBytes
    self.maximumImageDimension = maximumImageDimension
    self.maximumDecodedPixels = maximumDecodedPixels
    self.maximumDecodedImageBytes = maximumDecodedImageBytes
    self.maximumResourceCount = maximumResourceCount
  }

  public static let `default` = RemoteComposeNativeResourceLimits()
}

/// An external image reference from a document. The player never resolves it without a host.
public struct RemoteComposeNativeResourceRequest: Equatable, Sendable {
  public let id: Int
  public let reference: String
  public let declaredWidth: Int
  public let declaredHeight: Int
  public let type: Int
  public let encoding: Int

  public init(
    id: Int,
    reference: String,
    declaredWidth: Int,
    declaredHeight: Int,
    type: Int,
    encoding: Int
  ) {
    self.id = id
    self.reference = reference
    self.declaredWidth = declaredWidth
    self.declaredHeight = declaredHeight
    self.type = type
    self.encoding = encoding
  }
}

/// Opt-in host ownership for referenced resource bytes. No default implementation performs I/O.
public protocol RemoteComposeNativeResourceResolving: AnyObject, Sendable {
  func resolve(_ request: RemoteComposeNativeResourceRequest) async throws -> Data
}

public enum RemoteComposeNativeResourceError: Error, Equatable, LocalizedError, Sendable {
  case invalidLimits
  case tooManyResources(actual: Int, maximum: Int)
  case resourceTooLarge(id: Int, actual: Int, maximum: Int)
  case totalTooLarge(actual: Int, maximum: Int)
  case invalidDimensions(id: Int, width: Int, height: Int)
  case dimensionMismatch(
    id: Int, declaredWidth: Int, declaredHeight: Int, actualWidth: Int, actualHeight: Int)
  case decodedImageTooLarge(id: Int, pixels: Int, maximum: Int)
  case decodedImageBytesTooLarge(actual: Int, maximum: Int)
  case duplicateResource(id: Int)
  case invalidReference(id: Int)
  case corruptImage(id: Int)
  case corruptFont(id: Int)
  case unresolvedReference(id: Int)

  public var errorDescription: String? {
    switch self {
    case .invalidLimits: return "Native resource limits must be positive"
    case .tooManyResources(let actual, let maximum):
      return "Document declares \(actual) resources; the limit is \(maximum)"
    case .resourceTooLarge(let id, let actual, let maximum):
      return "Resource \(id) is \(actual) bytes; the limit is \(maximum)"
    case .totalTooLarge(let actual, let maximum):
      return "Document resources total \(actual) bytes; the limit is \(maximum)"
    case .invalidDimensions(let id, let width, let height):
      return "Image \(id) has invalid dimensions \(width)x\(height)"
    case .dimensionMismatch(
      let id, let declaredWidth, let declaredHeight, let actualWidth, let actualHeight):
      return
        "Image \(id) declares \(declaredWidth)x\(declaredHeight) but decodes as \(actualWidth)x\(actualHeight)"
    case .decodedImageTooLarge(let id, let pixels, let maximum):
      return "Image \(id) decodes to \(pixels) pixels; the limit is \(maximum)"
    case .decodedImageBytesTooLarge(let actual, let maximum):
      return "Document images decode to \(actual) bytes; the limit is \(maximum)"
    case .duplicateResource(let id): return "Resource id \(id) is declared more than once"
    case .invalidReference(let id): return "Image \(id) has an invalid external reference"
    case .corruptImage(let id): return "Image \(id) could not be decoded"
    case .corruptFont(let id): return "Font \(id) could not be decoded"
    case .unresolvedReference(let id): return "Image \(id) requires a host resource resolver"
    }
  }
}

enum NativeResourcePolicy {
  static func validate(limits: RemoteComposeNativeResourceLimits) throws {
    guard
      limits.maximumResourceBytes > 0,
      limits.maximumTotalBytes > 0,
      limits.maximumImageDimension > 0,
      limits.maximumDecodedPixels > 0,
      limits.maximumDecodedImageBytes > 0,
      limits.maximumResourceCount > 0
    else { throw RemoteComposeNativeResourceError.invalidLimits }
  }

  static func validate(
    id: Int,
    byteCount: Int,
    width: Int,
    height: Int,
    runningTotal: inout Int,
    limits: RemoteComposeNativeResourceLimits
  ) throws {
    try validate(limits: limits)
    guard
      width > 0,
      height > 0,
      width <= limits.maximumImageDimension,
      height <= limits.maximumImageDimension
    else {
      throw RemoteComposeNativeResourceError.invalidDimensions(
        id: id, width: width, height: height)
    }
    let (pixels, overflowed) = width.multipliedReportingOverflow(by: height)
    guard !overflowed, pixels <= limits.maximumDecodedPixels else {
      throw RemoteComposeNativeResourceError.decodedImageTooLarge(
        id: id, pixels: overflowed ? Int.max : pixels, maximum: limits.maximumDecodedPixels)
    }
    try validateBytes(
      id: id, byteCount: byteCount, runningTotal: &runningTotal, limits: limits)
  }

  static func validateBytes(
    id: Int,
    byteCount: Int,
    runningTotal: inout Int,
    limits: RemoteComposeNativeResourceLimits
  ) throws {
    try validate(limits: limits)
    guard byteCount <= limits.maximumResourceBytes else {
      throw RemoteComposeNativeResourceError.resourceTooLarge(
        id: id, actual: byteCount, maximum: limits.maximumResourceBytes)
    }
    let (nextTotal, totalOverflowed) = runningTotal.addingReportingOverflow(byteCount)
    guard !totalOverflowed, nextTotal <= limits.maximumTotalBytes else {
      throw RemoteComposeNativeResourceError.totalTooLarge(
        actual: totalOverflowed ? Int.max : nextTotal, maximum: limits.maximumTotalBytes)
    }
    runningTotal = nextTotal
  }

  static func reference(from data: Data, id: Int) throws -> String {
    guard
      let value = String(data: data, encoding: .utf8)?.trimmingCharacters(
        in: .whitespacesAndNewlines),
      !value.isEmpty
    else { throw RemoteComposeNativeResourceError.invalidReference(id: id) }
    return value
  }

  static func validateUniqueIDs(_ ids: [Int]) throws {
    var seen = Set<Int>()
    for id in ids where !seen.insert(id).inserted {
      throw RemoteComposeNativeResourceError.duplicateResource(id: id)
    }
  }
}

enum NativeImageGeometry {
  static func destination(
    source: CGRect,
    destination: CGRect,
    scaleType: Int,
    scaleFactor: CGFloat
  ) -> CGRect {
    let sourceWidth = source.width
    let sourceHeight = source.height
    let destinationWidth = destination.width
    let destinationHeight = destination.height
    guard
      source.minX.isFinite, source.minY.isFinite, sourceWidth.isFinite, sourceHeight.isFinite,
      destination.minX.isFinite, destination.minY.isFinite,
      destinationWidth.isFinite, destinationHeight.isFinite, scaleFactor.isFinite,
      sourceWidth > 0, sourceHeight > 0, destinationWidth >= 0, destinationHeight >= 0
    else { return .zero }
    var width = destinationWidth
    var height = destinationHeight
    switch scaleType {
    case 0:
      width = sourceWidth
      height = sourceHeight
    case 1:
      if !(destinationHeight > sourceHeight && destinationWidth > sourceWidth) {
        if sourceWidth * destination.height > destination.width * sourceHeight {
          height = destinationWidth * sourceHeight / sourceWidth
        } else {
          width = destinationHeight * sourceWidth / sourceHeight
        }
      } else {
        width = sourceWidth
        height = sourceHeight
      }
    case 2: height = destinationWidth * sourceHeight / sourceWidth
    case 3: width = destinationHeight * sourceWidth / sourceHeight
    case 4:
      if sourceWidth * destination.height > destination.width * sourceHeight {
        height = destinationWidth * sourceHeight / sourceWidth
      } else {
        width = destinationHeight * sourceWidth / sourceHeight
      }
    case 5:
      if sourceWidth * destination.height < destination.width * sourceHeight {
        height = destinationWidth * sourceHeight / sourceWidth
      } else {
        width = destinationHeight * sourceWidth / sourceHeight
      }
    case 6: break
    case 7:
      width = sourceWidth * scaleFactor
      height = sourceHeight * scaleFactor
    default: return .zero
    }
    let result = CGRect(
      x: destination.minX + (destinationWidth - width) / 2,
      y: destination.minY + (destinationHeight - height) / 2,
      width: width,
      height: height)
    guard
      result.minX.isFinite, result.minY.isFinite, result.width.isFinite, result.height.isFinite
    else { return .zero }
    return result
  }
}

#if canImport(UIKit)
  import CoreText
  import UIKit

  @MainActor
  final class NativeImageCache {
    private let cache = NSCache<NSString, UIImage>()

    init(countLimit: Int, totalCostLimit: Int) {
      cache.countLimit = countLimit
      cache.totalCostLimit = totalCostLimit
    }

    func image(for request: RemoteComposeNativeResourceRequest) -> UIImage? {
      cache.object(forKey: key(for: request))
    }

    func insert(_ image: UIImage, for request: RemoteComposeNativeResourceRequest) {
      cache.setObject(image, forKey: key(for: request), cost: imageCost(image))
    }

    private func key(for request: RemoteComposeNativeResourceRequest) -> NSString {
      "\(request.encoding)|\(request.type)|\(request.declaredWidth)x\(request.declaredHeight)|\(request.reference)"
        as NSString
    }

    private func imageCost(_ image: UIImage) -> Int {
      guard let cgImage = image.cgImage else { return 0 }
      let (cost, overflowed) = cgImage.bytesPerRow.multipliedReportingOverflow(by: cgImage.height)
      return overflowed ? Int.max : cost
    }
  }

  @MainActor
  final class NativeFontRegistry {
    private struct Registration {
      let data: Data
      let url: URL
      var ownerCount: Int
    }

    private static var processRegistrations: [String: Registration] = [:]
    private let countLimit: Int
    private var ownedNames = Set<String>()

    init(countLimit: Int) {
      self.countLimit = countLimit
    }

    func register(data: Data, id: Int) throws -> String {
      guard
        let provider = CGDataProvider(data: data as CFData),
        let cgFont = CGFont(provider),
        let postScriptName = cgFont.postScriptName as String?
      else { throw RemoteComposeNativeResourceError.corruptFont(id: id) }
      if ownedNames.contains(postScriptName) {
        guard Self.processRegistrations[postScriptName]?.data == data else {
          throw RemoteComposeNativeResourceError.corruptFont(id: id)
        }
        return postScriptName
      }
      guard ownedNames.count < countLimit else {
        throw RemoteComposeNativeResourceError.tooManyResources(
          actual: ownedNames.count + 1, maximum: countLimit)
      }
      if var existing = Self.processRegistrations[postScriptName] {
        guard existing.data == data else {
          throw RemoteComposeNativeResourceError.corruptFont(id: id)
        }
        existing.ownerCount += 1
        Self.processRegistrations[postScriptName] = existing
        ownedNames.insert(postScriptName)
        return postScriptName
      }
      let url = FileManager.default.temporaryDirectory
        .appendingPathComponent("rc-native-font-\(UUID().uuidString)")
        .appendingPathExtension("font")
      try data.write(to: url, options: [.atomic])
      var registrationError: Unmanaged<CFError>?
      if CTFontManagerRegisterFontsForURL(url as CFURL, .process, &registrationError) {
        Self.processRegistrations[postScriptName] = Registration(
          data: data, url: url, ownerCount: 1)
        ownedNames.insert(postScriptName)
      } else {
        try? FileManager.default.removeItem(at: url)
        throw RemoteComposeNativeResourceError.corruptFont(id: id)
      }
      return postScriptName
    }

    func reset() {
      Self.release(ownedNames)
      ownedNames.removeAll()
    }

    deinit {
      let names = ownedNames
      Task { @MainActor in Self.release(names) }
    }

    private static func release(_ names: Set<String>) {
      for name in names {
        guard var registration = Self.processRegistrations[name] else { continue }
        registration.ownerCount -= 1
        if registration.ownerCount == 0 {
          var error: Unmanaged<CFError>?
          CTFontManagerUnregisterFontsForURL(registration.url as CFURL, .process, &error)
          try? FileManager.default.removeItem(at: registration.url)
          Self.processRegistrations.removeValue(forKey: name)
        } else {
          Self.processRegistrations[name] = registration
        }
      }
    }
  }

  @MainActor
  final class NativeResourceStore {
    private(set) var images: [Int: UIImage] = [:]
    private(set) var fontNames: [Int: String] = [:]
    private(set) var unresolvedImages: [RemoteComposeNativeResourceRequest] = []
    private let resourcesByID: [Int: NativeImageResource]
    private let limits: RemoteComposeNativeResourceLimits
    private var totalBytes = 0
    private var totalDecodedImageBytes = 0
    private var retainedImages = Set<ObjectIdentifier>()
    private let fontResources: [NativeFontResource]
    private var downloadedFontResources: [NativeFontResource] = []

    private let cache: NativeImageCache
    private let fontRegistry: NativeFontRegistry

    init(
      resources: [NativeImageResource],
      fonts: [NativeFontResource],
      limits: RemoteComposeNativeResourceLimits,
      cache: NativeImageCache
    ) throws {
      try NativeResourcePolicy.validate(limits: limits)
      guard resources.count + fonts.count <= limits.maximumResourceCount else {
        throw RemoteComposeNativeResourceError.tooManyResources(
          actual: resources.count + fonts.count, maximum: limits.maximumResourceCount)
      }
      try NativeResourcePolicy.validateUniqueIDs(resources.map(\.id))
      try NativeResourcePolicy.validateUniqueIDs(fonts.map(\.id))
      self.limits = limits
      self.cache = cache
      fontResources = fonts
      fontRegistry = NativeFontRegistry(countLimit: limits.maximumResourceCount)
      resourcesByID = Dictionary(uniqueKeysWithValues: resources.map { ($0.id, $0) })
      var unresolved: [RemoteComposeNativeResourceRequest] = []
      for resource in resources {
        try NativeResourcePolicy.validate(
          id: resource.id,
          byteCount: resource.data.count,
          width: resource.width,
          height: resource.height,
          runningTotal: &totalBytes,
          limits: limits)
        if resource.encoding == 0 {
          try retain(
            try Self.decode(resource: resource, data: resource.data, limits: limits),
            id: resource.id)
        } else {
          let request = RemoteComposeNativeResourceRequest(
            id: resource.id,
            reference: try NativeResourcePolicy.reference(from: resource.data, id: resource.id),
            declaredWidth: resource.width,
            declaredHeight: resource.height,
            type: resource.type,
            encoding: resource.encoding)
          if let cached = cache.image(for: request) {
            try retain(cached, id: resource.id)
          } else {
            unresolved.append(request)
          }
        }
      }
      for font in fonts {
        try NativeResourcePolicy.validateBytes(
          id: font.id,
          byteCount: font.data.count,
          runningTotal: &totalBytes,
          limits: limits)
      }
      unresolvedImages = unresolved
    }

    func activateFonts(replacing previous: NativeResourceStore?) throws {
      guard previous !== self else { return }
      previous?.fontRegistry.reset()
      do {
        fontNames.removeAll(keepingCapacity: true)
        for font in fontResources + downloadedFontResources {
          fontNames[font.id] = try fontRegistry.register(data: font.data, id: font.id)
        }
      } catch {
        fontRegistry.reset()
        fontNames.removeAll()
        try? previous?.activateFonts(replacing: nil)
        throw error
      }
    }

    func insertDownloadedFont(data: Data, id: Int) throws {
      guard !fontResources.contains(where: { $0.id == id }),
        !downloadedFontResources.contains(where: { $0.id == id })
      else { throw RemoteComposeNativeResourceError.duplicateResource(id: id) }
      let count = resourcesByID.count + fontResources.count + downloadedFontResources.count + 1
      guard count <= limits.maximumResourceCount else {
        throw RemoteComposeNativeResourceError.tooManyResources(
          actual: count, maximum: limits.maximumResourceCount)
      }
      try NativeResourcePolicy.validateBytes(
        id: id, byteCount: data.count, runningTotal: &totalBytes, limits: limits)
      downloadedFontResources.append(NativeFontResource(id: id, type: 0, data: data))
    }

    func insertResolved(data: Data, for request: RemoteComposeNativeResourceRequest) throws {
      guard let resource = resourcesByID[request.id] else {
        throw RemoteComposeNativeResourceError.unresolvedReference(id: request.id)
      }
      var nextTotal = totalBytes
      try NativeResourcePolicy.validate(
        id: request.id,
        byteCount: data.count,
        width: request.declaredWidth,
        height: request.declaredHeight,
        runningTotal: &nextTotal,
        limits: limits)
      let resolved = NativeImageResource(
        id: resource.id,
        width: resource.width,
        height: resource.height,
        type: resource.type,
        encoding: 0,
        data: data)
      let image = try Self.decode(resource: resolved, data: data, limits: limits)
      try retain(image, id: request.id)
      cache.insert(image, for: request)
      totalBytes = nextTotal
    }

    private func retain(_ image: UIImage, id: Int) throws {
      let identity = ObjectIdentifier(image)
      if retainedImages.insert(identity).inserted, let cgImage = image.cgImage {
        let (cost, costOverflowed) = cgImage.bytesPerRow.multipliedReportingOverflow(
          by: cgImage.height)
        let (nextTotal, totalOverflowed) = totalDecodedImageBytes.addingReportingOverflow(
          costOverflowed ? Int.max : cost)
        guard
          !costOverflowed, !totalOverflowed, nextTotal <= limits.maximumDecodedImageBytes
        else {
          retainedImages.remove(identity)
          throw RemoteComposeNativeResourceError.decodedImageBytesTooLarge(
            actual: costOverflowed || totalOverflowed ? Int.max : nextTotal,
            maximum: limits.maximumDecodedImageBytes)
        }
        totalDecodedImageBytes = nextTotal
      }
      images[id] = image
    }

    private static func decode(
      resource: NativeImageResource,
      data: Data,
      limits: RemoteComposeNativeResourceLimits
    ) throws -> UIImage {
      let image: UIImage?
      switch resource.type {
      case 0, 1, 4:
        image = UIImage(data: data, scale: 1)
      case 2:
        image = rawImage(
          data: data, width: resource.width, height: resource.height, alphaOnly: true)
      case 3:
        image = rawImage(
          data: data, width: resource.width, height: resource.height, alphaOnly: false)
      default:
        image = nil
      }
      guard let image, let cgImage = image.cgImage else {
        throw RemoteComposeNativeResourceError.corruptImage(id: resource.id)
      }
      var ignoredTotal = 0
      try NativeResourcePolicy.validate(
        id: resource.id,
        byteCount: data.count,
        width: cgImage.width,
        height: cgImage.height,
        runningTotal: &ignoredTotal,
        limits: limits)
      guard cgImage.width == resource.width, cgImage.height == resource.height else {
        throw RemoteComposeNativeResourceError.dimensionMismatch(
          id: resource.id,
          declaredWidth: resource.width,
          declaredHeight: resource.height,
          actualWidth: cgImage.width,
          actualHeight: cgImage.height)
      }
      return image
    }

    private static func rawImage(
      data: Data,
      width: Int,
      height: Int,
      alphaOnly: Bool
    ) -> UIImage? {
      let bytesPerPixel = alphaOnly ? 1 : 4
      let (pixels, pixelOverflow) = width.multipliedReportingOverflow(by: height)
      let (expected, byteOverflow) = pixels.multipliedReportingOverflow(by: bytesPerPixel)
      guard !pixelOverflow, !byteOverflow, data.count == expected,
        let provider = CGDataProvider(data: data as CFData)
      else {
        return nil
      }
      let image: CGImage?
      if alphaOnly {
        image = CGImage(
          maskWidth: width,
          height: height,
          bitsPerComponent: 8,
          bitsPerPixel: 8,
          bytesPerRow: width,
          provider: provider,
          decode: nil,
          shouldInterpolate: true)
      } else {
        image = CGImage(
          width: width,
          height: height,
          bitsPerComponent: 8,
          bitsPerPixel: 32,
          bytesPerRow: width * 4,
          space: CGColorSpaceCreateDeviceRGB(),
          bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.last.rawValue),
          provider: provider,
          decode: nil,
          shouldInterpolate: true,
          intent: .defaultIntent)
      }
      guard let image else { return nil }
      return UIImage(cgImage: image, scale: 1, orientation: .up)
    }
  }

#endif
