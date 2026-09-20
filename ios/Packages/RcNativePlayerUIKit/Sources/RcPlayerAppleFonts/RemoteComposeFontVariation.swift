import CoreGraphics
import CoreText
import Foundation

/// Applies a text run's numeric weight to a resolved font face.
///
/// A Remote Compose `CoreText` carries its weight on the run, not on the font resource: one family
/// id is used at several weights inside a single document. A variable face can express all of them
/// through its `wght` axis, but a static instance cannot — and replacing the descriptor with the
/// face wholesale, as the renderer used to, discards the weight either way.
public enum RemoteComposeFontVariation {
  /// The OpenType axis tag for weight, `'wght'`.
  public static let weightAxisIdentifier: UInt32 = 0x7767_6874

  /// The weight at which a static face is promoted to its bold symbolic trait.
  public static let boldWeight: CGFloat = 600

  /// True when the face declares a `wght` variation axis, so a weight can be set on it directly.
  public static func hasWeightAxis(_ descriptor: CTFontDescriptor) -> Bool {
    let font = CTFontCreateWithFontDescriptor(descriptor, 0, nil)
    guard let axes = CTFontCopyVariationAxes(font) as? [[CFString: Any]] else { return false }
    return axes.contains { axis in
      (axis[kCTFontVariationAxisIdentifierKey as CFString] as? NSNumber)?.uint32Value
        == weightAxisIdentifier
    }
  }

  /// The same face with `wght` set to `weight`, or nil when the face has no such axis.
  ///
  /// A nil result means the face is a static instance: the caller carries the weight as a symbolic
  /// trait instead, because a variation applied to a face that does not declare the axis is either
  /// ignored or resolves to a synthesised, wrong instance.
  public static func descriptor(
    _ descriptor: CTFontDescriptor, applyingWeight weight: CGFloat
  ) -> CTFontDescriptor? {
    guard weight.isFinite, hasWeightAxis(descriptor) else { return nil }
    let variation: [NSNumber: NSNumber] = [
      NSNumber(value: weightAxisIdentifier): NSNumber(value: Float(weight))
    ]
    return CTFontDescriptorCreateCopyWithAttributes(
      descriptor, [kCTFontVariationAttribute: variation as CFDictionary] as CFDictionary)
  }

  /// The symbolic traits that carry a weight a static face cannot express as an axis.
  ///
  /// Only bold has a symbolic equivalent on Apple platforms; lighter weights than the face's own
  /// default are left alone rather than approximated.
  public static func symbolicTraits(
    forWeight weight: CGFloat, existing: CTFontSymbolicTraits
  ) -> CTFontSymbolicTraits {
    guard weight >= boldWeight, !existing.contains(.traitBold) else {
      return existing
    }
    return existing.union(.traitBold)
  }
}
