import CoreGraphics
import Foundation

/// Pure geometry used by the UIKit renderer. Keeping this free of UIView mutation makes layout
/// behavior deterministic and directly testable.
struct NativeLayoutDimension: Equatable {
  let type: Int
  let value: CGFloat
  let minimum: CGFloat
  let maximum: CGFloat?

  var weight: CGFloat? {
    type == 3 ? max(value, .leastNonzeroMagnitude) : nil
  }

  func resolve(intrinsic: CGFloat, available: CGFloat) -> CGFloat {
    let proposed: CGFloat
    switch type {
    case 0, 6:
      proposed = max(value, 0)
    case 1, 7, 8:
      let fraction = value.isNaN ? 1 : max(value, 0)
      proposed = available * fraction
    case 3:
      proposed = available
    default:
      proposed = intrinsic
    }
    let upperBound = maximum.map { min($0, available) } ?? available
    return min(max(proposed, minimum), max(upperBound, 0))
  }
}

enum NativeLayoutDirection {
  case leftToRight
  case rightToLeft
}

enum NativeLinearLayout {
  /// AndroidX RowLayout/ColumnLayout positioning, including additive `spacedBy` behavior.
  static func positions(
    total: CGFloat,
    sizes: [CGFloat],
    positioning: Int,
    spacing: CGFloat,
    direction: NativeLayoutDirection = .leftToRight
  ) -> [CGFloat] {
    guard !sizes.isEmpty else { return [] }
    let childSize = sizes.reduce(0, +)
    let contentSize = childSize + spacing * CGFloat(max(sizes.count - 1, 0))
    var distributedGap: CGFloat = 0
    var current: CGFloat
    switch positioning {
    case 2:
      current = (total - contentSize) / 2
    case 3, 5:
      current = total - contentSize
    case 6:
      if sizes.count > 1 {
        distributedGap = (total - childSize) / CGFloat(sizes.count - 1)
        current = 0
      } else {
        current = (total - contentSize) / 2
      }
    case 7:
      distributedGap = (total - childSize) / CGFloat(sizes.count + 1)
      current = distributedGap
    case 8:
      distributedGap = (total - childSize) / CGFloat(sizes.count)
      current = distributedGap / 2
    default:
      current = 0
    }

    return sizes.map { size in
      let logicalPosition = current.rounded()
      current += size + spacing
      if (6...8).contains(positioning) { current += distributedGap }
      if direction == .rightToLeft { return total - logicalPosition - size }
      return logicalPosition
    }
  }

  static func allocateWeighted(
    available: CGFloat,
    naturalSizes: [CGFloat],
    weights: [CGFloat?]
  ) -> [CGFloat] {
    precondition(naturalSizes.count == weights.count)
    let fixed = zip(naturalSizes, weights).reduce(CGFloat.zero) { partial, item in
      partial + (item.1 == nil ? item.0 : 0)
    }
    let totalWeight = weights.compactMap { $0 }.reduce(0, +)
    guard totalWeight > 0 else { return naturalSizes }
    // AndroidX allocates weights from the remaining child space. `spacedBy` is additive and is
    // applied during placement, so it deliberately does not reduce weighted measurements.
    let remaining = max(available - fixed, 0)
    return zip(naturalSizes, weights).map { natural, weight in
      weight.map { remaining * $0 / totalWeight } ?? natural
    }
  }

  /// The space a **collapsible** container's weights divide: its own axis less the gaps that
  /// placement then adds between the children it kept.
  ///
  /// The collapsible family is the exception to the additive-spacing rule above. Its reference
  /// implementation charges `neededSpacing` into the running total before dividing the remainder,
  /// so a 60-point column with a 10-point gap and 1:2 weights allocates 16 and 33 rather than 20 and
  /// 40 — otherwise the shares fill the whole axis, the layout constrains the result, and the last
  /// child is clipped by exactly the total gap. An ordinary row or column keeps the additive
  /// behaviour, which is what Compose's own arrangement does.
  static func collapsibleWeightSpace(extent: CGFloat, count: Int, spacing: CGFloat) -> CGFloat {
    guard count > 1 else { return extent }
    return max(extent - spacing * CGFloat(count - 1), 0)
  }
}

struct NativeRootTransform: Equatable {
  let scaleX: CGFloat
  let scaleY: CGFloat
  let translateX: CGFloat
  let translateY: CGFloat

  static func resolve(
    document: CGSize,
    viewport: CGSize,
    sizing: Int,
    mode: Int,
    alignment: Int
  ) -> NativeRootTransform {
    guard sizing == 2 else {
      return NativeRootTransform(scaleX: 1, scaleY: 1, translateX: 0, translateY: 0)
    }
    let widthRatio = viewport.width / max(document.width, 1)
    let heightRatio = viewport.height / max(document.height, 1)
    let uniformScale: CGFloat
    switch mode {
    case 1: uniformScale = min(1, min(widthRatio, heightRatio))
    case 2: uniformScale = widthRatio
    case 3: uniformScale = heightRatio
    case 4: uniformScale = min(widthRatio, heightRatio)
    case 5: uniformScale = max(widthRatio, heightRatio)
    default: uniformScale = 1
    }
    let scaleX = mode == 6 ? widthRatio : uniformScale
    let scaleY = mode == 6 ? heightRatio : uniformScale
    let content = CGSize(width: document.width * scaleX, height: document.height * scaleY)
    let horizontal = alignment & 0xf0
    let vertical = alignment & 0x0f
    let x: CGFloat
    switch horizontal {
    case 32: x = (viewport.width - content.width) / 2
    case 64: x = viewport.width - content.width
    default: x = 0
    }
    let y: CGFloat
    switch vertical {
    case 2: y = (viewport.height - content.height) / 2
    case 4: y = viewport.height - content.height
    default: y = 0
    }
    return NativeRootTransform(
      scaleX: scaleX, scaleY: scaleY, translateX: x, translateY: y)
  }
}
