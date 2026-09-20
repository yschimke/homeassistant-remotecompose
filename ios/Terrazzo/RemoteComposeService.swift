import Foundation

actor RemoteComposeService {
  enum ServiceError: LocalizedError {
    case invalidResponse
    case unsupportedFeed(Int)

    var errorDescription: String? {
      switch self {
      case .invalidResponse: "The Remote Compose gateway returned an invalid response."
      case .unsupportedFeed(let version): "Dashboard feed version \(version) is not supported."
      }
    }
  }

  private let configuration: ServerConfiguration
  private let session: URLSession

  init(configuration: ServerConfiguration, session: URLSession = .shared) {
    self.configuration = configuration
    self.session = session
  }

  func fetchFeed() async throws -> DashboardFeed {
    let url = configuration.baseURL.appending(path: "v1/apple/manifest.json")
    let (data, response) = try await session.data(for: request(url))
    try validate(response)
    let feed = try JSONDecoder().decode(DashboardFeed.self, from: data)
    guard feed.version == 1 else { throw ServiceError.unsupportedFeed(feed.version) }
    return feed
  }

  func fetchDocument(for card: DashboardCard) async throws -> Data {
    guard let url = URL(string: card.documentURL, relativeTo: configuration.baseURL)?.absoluteURL
    else { throw URLError(.badURL) }
    let (data, response) = try await session.data(for: request(url))
    try validate(response)
    guard !data.isEmpty else { throw ServiceError.invalidResponse }
    return data
  }

  func streamURL(for feed: DashboardFeed) throws -> URL {
    let relative = feed.streamURL ?? "/v1/stream"
    guard let absolute = URL(string: relative, relativeTo: configuration.baseURL)?.absoluteURL,
      var components = URLComponents(url: absolute, resolvingAgainstBaseURL: true)
    else { throw URLError(.badURL) }
    components.scheme = components.scheme == "https" ? "wss" : "ws"
    guard let url = components.url else { throw URLError(.badURL) }
    return url
  }

  func authorizationHeader() -> String? {
    configuration.token.isEmpty ? nil : "Bearer \(configuration.token)"
  }

  private func request(_ url: URL) -> URLRequest {
    var request = URLRequest(url: url)
    request.timeoutInterval = 15
    if let authorization = authorizationHeader() {
      request.setValue(authorization, forHTTPHeaderField: "Authorization")
    }
    return request
  }

  private func validate(_ response: URLResponse) throws {
    guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
      throw ServiceError.invalidResponse
    }
  }
}
