import CoreGraphics

struct NativeGradient {
  let kind: Int
  let colors: [CGColor]
  let stops: [CGFloat]
  let values: [CGFloat]
  let tileMode: Int
}

enum NativeGraphicsState {
  static func apply(
    to context: CGContext,
    strokeWidth: CGFloat,
    strokeCap: Int,
    strokeJoin: Int,
    blendMode: Int
  ) {
    context.setLineWidth(max(strokeWidth, 0.5))
    context.setLineCap(lineCap(strokeCap))
    context.setLineJoin(lineJoin(strokeJoin))
    context.setBlendMode(self.blendMode(blendMode))
  }

  static func lineCap(_ value: Int) -> CGLineCap {
    switch value {
    case 1: .round
    case 2: .square
    default: .butt
    }
  }

  static func lineJoin(_ value: Int) -> CGLineJoin {
    switch value {
    case 1: .round
    case 2: .bevel
    default: .miter
    }
  }

  static func blendMode(_ value: Int) -> CGBlendMode {
    switch value {
    case 0: .clear
    case 1: .copy
    case 4: .destinationOver
    case 5: .sourceIn
    case 6: .destinationIn
    case 7: .sourceOut
    case 8: .destinationOut
    case 9: .sourceAtop
    case 10: .destinationAtop
    case 11: .xor
    case 12: .plusLighter
    case 13, 24: .multiply
    case 14: .screen
    case 15: .overlay
    case 16: .darken
    case 17: .lighten
    case 18: .colorDodge
    case 19: .colorBurn
    case 20: .hardLight
    case 21: .softLight
    case 22: .difference
    case 23: .exclusion
    case 25: .hue
    case 26: .saturation
    case 27: .color
    case 28: .luminosity
    default: .normal
    }
  }
}

enum NativeGradientRenderer {
  static func draw(_ value: NativeGradient, in context: CGContext) {
    let normalizedColors = value.colors.count == 1 ? [value.colors[0], value.colors[0]] : value.colors
    guard normalizedColors.count >= 2 else { return }
    let normalizedStops: [CGFloat]?
    if value.stops.count == normalizedColors.count {
      normalizedStops = value.stops
    } else {
      normalizedStops = (0..<normalizedColors.count).map {
        CGFloat($0) / CGFloat(normalizedColors.count - 1)
      }
    }
    // Converted into the gradient's own space first. CGGradient returns nil when a colour is not
    // already in the space it is handed, and `UIColor.cgColor` is sRGB rather than device RGB — so
    // building against CGColorSpaceCreateDeviceRGB() failed for every real colour and painted
    // nothing at all. sRGB is also what the reference players interpolate in.
    let space = CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB()
    let spaceColors = normalizedColors.map {
      $0.converted(to: space, intent: .defaultIntent, options: nil) ?? $0
    }
    guard
      let gradient = CGGradient(
        colorsSpace: space,
        colors: spaceColors as CFArray,
        locations: normalizedStops)
    else { return }

    switch value.kind {
    case 0:
      context.drawLinearGradient(
        gradient,
        start: CGPoint(x: value.values[0], y: value.values[1]),
        end: CGPoint(x: value.values[2], y: value.values[3]),
        options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])
    case 1:
      context.drawRadialGradient(
        gradient,
        startCenter: CGPoint(x: value.values[0], y: value.values[1]),
        startRadius: 0,
        endCenter: CGPoint(x: value.values[0], y: value.values[1]),
        endRadius: value.values[2],
        options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])
    case 2:
      drawSweep(
        colors: normalizedColors,
        stops: normalizedStops ?? [],
        center: CGPoint(x: value.values[0], y: value.values[1]),
        in: context)
    default: break
    }
  }

  private static func drawSweep(
    colors: [CGColor], stops: [CGFloat], center: CGPoint, in context: CGContext
  ) {
    let clip = context.boundingBoxOfClipPath
    let radius = [
      CGPoint(x: clip.minX, y: clip.minY), CGPoint(x: clip.maxX, y: clip.minY),
      CGPoint(x: clip.maxX, y: clip.maxY), CGPoint(x: clip.minX, y: clip.maxY),
    ].map { hypot($0.x - center.x, $0.y - center.y) }.max() ?? 0
    guard radius > 0 else { return }
    let steps = 360
    for index in 0..<steps {
      let start = CGFloat(index) / CGFloat(steps)
      let end = CGFloat(index + 1) / CGFloat(steps)
      context.setFillColor(interpolatedColor(at: (start + end) / 2, colors: colors, stops: stops))
      context.move(to: center)
      context.addArc(
        center: center,
        radius: radius,
        startAngle: start * 2 * .pi,
        endAngle: end * 2 * .pi,
        clockwise: false)
      context.closePath()
      context.fillPath()
    }
  }

  private static func interpolatedColor(
    at location: CGFloat, colors: [CGColor], stops: [CGFloat]
  ) -> CGColor {
    let upper = stops.firstIndex(where: { $0 >= location }) ?? stops.index(before: stops.endIndex)
    guard upper > stops.startIndex else { return colors[0] }
    let lower = stops.index(before: upper)
    let distance = stops[upper] - stops[lower]
    let fraction = distance > 0 ? (location - stops[lower]) / distance : 0
    let left = colors[lower].components ?? [0, 0, 0, 1]
    let right = colors[upper].components ?? [0, 0, 0, 1]
    let components = (0..<4).map { index in
      left[index] + (right[index] - left[index]) * fraction
    }
    return CGColor(
      colorSpace: CGColorSpaceCreateDeviceRGB(), components: components) ?? colors[lower]
  }
}
