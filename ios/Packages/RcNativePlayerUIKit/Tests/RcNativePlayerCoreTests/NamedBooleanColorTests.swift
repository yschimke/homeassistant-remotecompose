import Foundation
import RcNativePlayerCore
import XCTest

final class NamedBooleanColorTests: XCTestCase {
  func testUserBooleanDrivesTintAndHalo() throws {
    let data = try Data(contentsOf: Bundle.module.url(forResource: "demo-tile", withExtension: "rc")!)
    let session = try NativeSwiftDocumentSession.open(data: data)

    let active = try colors(in: session.snapshot().root)
    XCTAssertEqual(session.namedVariableID("light.kitchen.is_on"), 51)
    XCTAssertTrue(active.contains(0xffff_be3e))
    XCTAssertTrue(active.contains(0x33ff_be3e))

    XCTAssertTrue(session.setInteger(0, for: "light.kitchen.is_on"))
    let inactive = try colors(in: session.snapshot().root)
    XCTAssertTrue(inactive.contains(0xffb0_b0b0))
    XCTAssertTrue(inactive.contains(0x33b0_b0b0))
  }

  private func colors(in node: NativeSwiftNodeSnapshot) -> Set<UInt32> {
    var result = Set(node.commands.map(\.colorARGB))
    if let color = node.backgroundARGB { result.insert(color) }
    if let color = node.text?.colorARGB { result.insert(color) }
    for child in node.children { result.formUnion(colors(in: child)) }
    return result
  }
}
