import CoreGraphics
import CryptoKit
import Foundation

#if canImport(FoundationNetworking)
  import FoundationNetworking
#endif

/// A named font requested by a Remote Compose document.
public struct RemoteComposeDownloadableFontRequest: Hashable, Sendable {
  public let family: String

  public init(family: String) {
    self.family = family
  }
}

/// Validated font bytes and a stable identity suitable for a renderer font cache.
public struct RemoteComposeDownloadedFont: Sendable {
  public let family: String
  public let identity: String
  public let data: Data

  public init(family: String, identity: String, data: Data) {
    self.family = family
    self.identity = identity
    self.data = data
  }
}

/// Host-selected source for document-declared downloadable fonts.
public protocol RemoteComposeDownloadableFontResolving: AnyObject, Sendable {
  func resolve(_ request: RemoteComposeDownloadableFontRequest) async throws
    -> RemoteComposeDownloadedFont
}

public enum RemoteComposeDownloadableFontError: Error, Equatable, LocalizedError, Sendable {
  case invalidFamily
  case invalidResponse
  case responseTooLarge(actual: Int, maximum: Int)
  case noCompatibleFont(family: String)
  case untrustedFontURL
  case familyMismatch(expected: String, actual: String)
  case corruptFont(family: String)

  public var errorDescription: String? {
    switch self {
    case .invalidFamily: return "The downloadable font family name is invalid"
    case .invalidResponse: return "The downloadable font server returned an invalid response"
    case .responseTooLarge(let actual, let maximum):
      return "The downloadable font response is \(actual) bytes; the limit is \(maximum)"
    case .noCompatibleFont(let family):
      return "Google Fonts returned no Apple-compatible font for \(family)"
    case .untrustedFontURL: return "Google Fonts returned a font URL from an untrusted host"
    case .familyMismatch(let expected, let actual):
      return "The font resolver returned \(actual) for the requested family \(expected)"
    case .corruptFont(let family): return "The downloaded font for \(family) is invalid"
    }
  }
}

/// Google Fonts CSS API resolver using URLSession, bounded responses, and an in-memory cache.
///
/// Supplying this resolver is an explicit opt-in to network font loading. The player only sends
/// family names carrying the `google:` source marker in the document.
public actor RemoteComposeGoogleFontsResolver: RemoteComposeDownloadableFontResolving {
  public struct Limits: Equatable, Sendable {
    public var maximumStylesheetBytes: Int
    public var maximumFontBytes: Int
    public var maximumCachedFonts: Int

    public init(
      maximumStylesheetBytes: Int = 256 * 1024,
      maximumFontBytes: Int = 8 * 1024 * 1024,
      maximumCachedFonts: Int = 16
    ) {
      self.maximumStylesheetBytes = maximumStylesheetBytes
      self.maximumFontBytes = maximumFontBytes
      self.maximumCachedFonts = maximumCachedFonts
    }

    public static let `default` = Limits()
  }

  /// A browser-shaped agent, so the CSS API serves the modern WOFF2 faces.
  ///
  /// CoreText registers WOFF2 directly on the platforms this package supports, and the variable
  /// face it serves is the only one that can express the per-run weights a document carries. A
  /// legacy agent returns a static TrueType instance with no `wght` axis at all.
  static let modernUserAgent =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

  /// The agent the resolver originally used. Kept as the last candidate because it is the only one
  /// that returns sfnt/TrueType bytes a platform without WOFF2 support can still register.
  static let legacyUserAgent =
    "Mozilla/5.0 (Linux; Android 4.0.3; Galaxy Nexus Build/IML74K) "
    + "AppleWebKit/534.30 (KHTML, like Gecko) Version/4.0 Mobile Safari/534.30"

  /// The full variable weight range, so one registered face can render every run's weight.
  ///
  /// A family without a `wght` axis answers the axis syntax with an HTTP 400 rather than a
  /// stylesheet, which is why the plain family and the legacy agent follow it as candidates.
  static let variableWeightQuery = "wght@100..1000"

  private let session: URLSession
  private let limits: Limits
  private var cache: [String: RemoteComposeDownloadedFont] = [:]
  private var cacheOrder: [String] = []

  public init(session: URLSession = .shared, limits: Limits = .default) {
    self.session = session
    self.limits = limits
  }

  public func resolve(_ request: RemoteComposeDownloadableFontRequest) async throws
    -> RemoteComposeDownloadedFont
  {
    let family = try Self.validatedFamily(request.family)
    let key = family.lowercased()
    if let cached = cache[key] { return cached }
    guard
      limits.maximumStylesheetBytes > 0, limits.maximumFontBytes > 0,
      limits.maximumCachedFonts > 0
    else { throw RemoteComposeDownloadableFontError.invalidResponse }

    var lastError: Error = RemoteComposeDownloadableFontError.noCompatibleFont(family: family)
    for candidate in Self.candidates(for: family) {
      do {
        if let font = try await download(candidate, family: family, key: key) {
          insert(font, for: key)
          return font
        }
      } catch let error as RemoteComposeDownloadableFontError {
        lastError = error
      }
    }
    throw lastError
  }

  /// One stylesheet query and, if it names a registrable face, the face itself.
  ///
  /// Returns nil when the candidate is simply not applicable — the axis syntax is rejected, or the
  /// served bytes are not a font CoreText can register — so the next candidate can be tried. A
  /// transport or host-validation failure is thrown instead: retrying a network fault against a
  /// different user agent would hide it rather than fix it.
  private func download(
    _ candidate: (query: String, userAgent: String), family: String, key: String
  ) async throws -> RemoteComposeDownloadedFont? {
    var components = URLComponents(string: "https://fonts.googleapis.com/css2")!
    components.queryItems = [URLQueryItem(name: "family", value: candidate.query)]
    var stylesheetRequest = URLRequest(url: components.url!)
    stylesheetRequest.setValue(candidate.userAgent, forHTTPHeaderField: "User-Agent")
    let stylesheetData: Data
    do {
      stylesheetData = try await fetch(
        stylesheetRequest, maximumBytes: limits.maximumStylesheetBytes,
        allowedHost: "fonts.googleapis.com")
    } catch RemoteComposeDownloadableFontError.invalidResponse {
      return nil
    }
    guard let stylesheet = String(data: stylesheetData, encoding: .utf8),
      let fontURL = Self.fontURL(from: stylesheet, relativeTo: components.url!)
    else { return nil }
    guard fontURL.scheme == "https", fontURL.host?.lowercased() == "fonts.gstatic.com" else {
      throw RemoteComposeDownloadableFontError.untrustedFontURL
    }
    let data = try await fetch(
      URLRequest(url: fontURL), maximumBytes: limits.maximumFontBytes,
      allowedHost: "fonts.gstatic.com")
    guard let provider = CGDataProvider(data: data as CFData), CGFont(provider) != nil else {
      return nil
    }
    let digest = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    return RemoteComposeDownloadedFont(
      family: family, identity: "google:\(key)#sha256:\(digest)", data: data)
  }

  /// The stylesheet queries to try, in order.
  static func candidates(for family: String) -> [(query: String, userAgent: String)] {
    [
      ("\(family):\(variableWeightQuery)", modernUserAgent),
      (family, modernUserAgent),
      (family, legacyUserAgent),
    ]
  }

  private func fetch(_ request: URLRequest, maximumBytes: Int, allowedHost: String) async throws
    -> Data
  {
    let (data, response) = try await session.data(for: request)
    guard let response = response as? HTTPURLResponse, response.statusCode == 200,
      response.url?.scheme == "https", response.url?.host?.lowercased() == allowedHost
    else {
      throw RemoteComposeDownloadableFontError.invalidResponse
    }
    if response.expectedContentLength > Int64(maximumBytes) {
      throw RemoteComposeDownloadableFontError.responseTooLarge(
        actual: Int(clamping: response.expectedContentLength), maximum: maximumBytes)
    }
    guard data.count <= maximumBytes else {
      throw RemoteComposeDownloadableFontError.responseTooLarge(
        actual: data.count, maximum: maximumBytes)
    }
    return data
  }

  private func insert(_ font: RemoteComposeDownloadedFont, for key: String) {
    if cache[key] == nil { cacheOrder.append(key) }
    cache[key] = font
    while cacheOrder.count > limits.maximumCachedFonts {
      cache.removeValue(forKey: cacheOrder.removeFirst())
    }
  }

  static func validatedFamily(_ value: String) throws -> String {
    let family = value.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !family.isEmpty, family.count <= 100,
      family.unicodeScalars.allSatisfy({
        CharacterSet.alphanumerics.contains($0) || $0 == " " || $0 == "-" || $0 == "."
      })
    else { throw RemoteComposeDownloadableFontError.invalidFamily }
    return family
  }

  static func fontURL(from stylesheet: String, relativeTo baseURL: URL) -> URL? {
    // A labelled sfnt face, if the stylesheet carries one. This is the legacy-agent shape and the
    // one a platform without WOFF2 support can register.
    let truetype =
      #"url\((?:['\"])?([^)'\"]+)(?:['\"])?\)\s*format\((?:['\"])?(?:truetype|opentype)(?:['\"])?\)"#
    if let url = matchURLs(truetype, stylesheet, baseURL).first { return url }
    // WOFF2, preferring the last block: Google emits one @font-face per unicode range and Latin is
    // last. The first match is cyrillic-ext, which would render Latin text with fallback glyphs.
    let woff2 = #"url\((?:['\"])?([^)'\"]+\.woff2)(?:['\"])?\)"#
    if let url = matchURLs(woff2, stylesheet, baseURL).last { return url }
    let labelled = #"url\((?:['\"])?([^)'\"]+\.(?:ttf|otf))(?:['\"])?\)"#
    if let url = matchURLs(labelled, stylesheet, baseURL).first { return url }
    let unlabeled = #"url\((?:['\"])?([^)'\"]+)(?:['\"])?\)\s*;"#
    return matchURLs(unlabeled, stylesheet, baseURL).first
  }

  private static func matchURLs(_ pattern: String, _ stylesheet: String, _ baseURL: URL) -> [URL] {
    guard let expression = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive])
    else { return [] }
    let range = NSRange(stylesheet.startIndex..<stylesheet.endIndex, in: stylesheet)
    return expression.matches(in: stylesheet, range: range).compactMap { match in
      guard let valueRange = Range(match.range(at: 1), in: stylesheet) else { return nil }
      let value = String(stylesheet[valueRange]).trimmingCharacters(in: .whitespacesAndNewlines)
      return URL(string: value, relativeTo: baseURL)?.absoluteURL
    }
  }
}
