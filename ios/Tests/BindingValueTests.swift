import Foundation
import XCTest

@testable import TerrazzoBindingModels

final class BindingValueTests: XCTestCase {
  func testJsonBooleanIntegerAndFloatBindingsStayDistinct() throws {
    let data = Data(
      #"{"light.test.is_on":true,"alarm.test.state_int":2,"sensor.test.numeric_state":1.0}"#.utf8)
    let raw = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    XCTAssertEqual(
      BindingValue(name: "light.test.is_on", json: raw["light.test.is_on"]!), .integer(1))
    XCTAssertEqual(
      BindingValue(name: "alarm.test.state_int", json: raw["alarm.test.state_int"]!), .integer(2))
    XCTAssertEqual(
      BindingValue(name: "sensor.test.numeric_state", json: raw["sensor.test.numeric_state"]!),
      .float(1))
    XCTAssertEqual(BindingValue(name: "light.test.is_on", json: NSNumber(value: 0)), .integer(0))
  }

  func testInvalidNumericBindingsAreRejectedWithoutTruncatingOrOverflowing() {
    for value in [Double.nan, Double.infinity, 1.5, Double.greatestFiniteMagnitude] {
      XCTAssertNil(BindingValue(name: "alarm.test.state_int", json: NSNumber(value: value)))
    }
    XCTAssertNil(BindingValue(name: "light.test.is_on", json: NSNumber(value: 2)))
    XCTAssertNil(
      BindingValue(name: "sensor.test.numeric_state", json: NSNumber(value: Double.infinity)))
  }
}
