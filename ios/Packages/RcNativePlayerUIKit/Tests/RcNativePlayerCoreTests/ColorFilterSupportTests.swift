import Foundation
import RcNativePlayerCore
import XCTest

final class ColorFilterSupportTests: XCTestCase {
  func testSolidSrcInTintIsSupported() throws {
    let session = try NativeSwiftDocumentSession.open(data: document(paint: filter()))
    XCTAssertEqual(try session.snapshot().root.commands.first?.colorARGB, 0xffff_0000)
  }

  func testSrcInPreservesTranslucentDestinationAlpha() throws {
    let session = try NativeSwiftDocumentSession.open(
      data: document(paint: [4, 0x8000_0000] + filter()))
    XCTAssertEqual(try session.snapshot().root.commands.first?.colorARGB, 0x80ff_0000)
  }

  func testUnsupportedModesAreReportedRatherThanIgnored() throws {
    try assertUnsupported(document(paint: filter(mode: 3)))
  }

  func testFiltersOnGradientsAndTexturesAreReported() throws {
    let gradient: [UInt32] = [
      11, 2, 0xff00_0000, 0xffff_ffff, 0, 0, 0, Float(100).bitPattern, 0, 0,
    ]
    try assertUnsupported(document(paint: filter() + gradient))
    try assertUnsupported(document(paint: filter() + [24, 42, 0, 0]))
  }

  func testFiltersOnBitmapsAreReported() throws {
    try assertUnsupported(document(paint: filter(), bitmap: true))
  }

  func testClearedFilterDoesNotRejectTheFollowingShader() throws {
    let session = try NativeSwiftDocumentSession.open(
      data: document(paint: filter() + [21, 24, 42, 0, 0]))
    _ = try session.snapshot()
  }

  private func assertUnsupported(_ bytes: Data) throws {
    let session = try NativeSwiftDocumentSession.open(data: bytes)
    XCTAssertThrowsError(try session.snapshot()) { error in
      XCTAssertTrue((error as? NativeSwiftCoreError)?.isUnsupported == true, "\(error)")
    }
  }

  private func filter(mode: UInt32 = 5) -> [UInt32] { [13 | (mode << 16), 0xffff_0000] }

  private func document(paint: [UInt32], bitmap: Bool = false) -> Data {
    var bytes = Data([0])
    func word(_ value: UInt32) {
      bytes.append(contentsOf: [
        UInt8(truncatingIfNeeded: value >> 24), UInt8(truncatingIfNeeded: value >> 16),
        UInt8(truncatingIfNeeded: value >> 8), UInt8(truncatingIfNeeded: value),
      ])
    }
    // Legacy header, one inline bitmap, root, paint, drawing command, container end.
    [1, 0, 0, 100, 100, 0, 0].forEach { word(UInt32($0)) }
    bytes.append(101)
    [42, 1, 1, 4, 0xff00_0000].forEach { word(UInt32($0)) }
    bytes.append(200)
    word(0)
    bytes.append(40)
    word(UInt32(paint.count))
    paint.forEach(word)
    if bitmap {
      bytes.append(44)
      word(42)
    } else {
      bytes.append(42)
    }
    [Float(0), 0, 100, 100].forEach { word($0.bitPattern) }
    if bitmap { word(0) }
    bytes.append(214)
    return bytes
  }
}
