import CryptoKit
import Foundation

actor CardCache {
  private let root: URL
  private let decoder = JSONDecoder()
  private let encoder = JSONEncoder()

  init(fileManager: FileManager = .default) {
    let support = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
    root = support.appendingPathComponent("Terrazzo", isDirectory: true)
    try? fileManager.createDirectory(at: root, withIntermediateDirectories: true)
  }

  func save(feed: DashboardFeed) throws {
    try atomicWrite(encoder.encode(feed), to: root.appendingPathComponent("manifest.json"))
  }

  func loadFeed() -> DashboardFeed? {
    guard let data = try? Data(contentsOf: root.appendingPathComponent("manifest.json")) else {
      return nil
    }
    return try? decoder.decode(DashboardFeed.self, from: data)
  }

  func save(document: Data, key: String) throws {
    try atomicWrite(document, to: documentURL(key))
  }

  func loadDocument(key: String) -> Data? { try? Data(contentsOf: documentURL(key)) }

  func clear() throws {
    let manager = FileManager.default
    if manager.fileExists(atPath: root.path) { try manager.removeItem(at: root) }
    try manager.createDirectory(at: root, withIntermediateDirectories: true)
  }

  private func documentURL(_ key: String) -> URL {
    let hash = SHA256.hash(data: Data(key.utf8)).map { String(format: "%02x", $0) }.joined()
    return root.appendingPathComponent("card-\(hash).rc")
  }

  private func atomicWrite(_ data: Data, to destination: URL) throws {
    let temporary = destination.appendingPathExtension("tmp")
    try data.write(to: temporary, options: .atomic)
    let manager = FileManager.default
    if manager.fileExists(atPath: destination.path) { try manager.removeItem(at: destination) }
    try manager.moveItem(at: temporary, to: destination)
  }
}
