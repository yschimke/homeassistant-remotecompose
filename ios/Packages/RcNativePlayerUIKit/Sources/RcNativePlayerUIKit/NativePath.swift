import CoreGraphics

struct NativePathElement {
  let kind: Int
  let values: [CGFloat]
}

enum NativePathBuilder {
  static func make(_ elements: [NativePathElement]) -> CGPath {
    let path = CGMutablePath()
    elements.forEach { segment in
      let v = segment.values
      switch segment.kind {
      case 10: path.move(to: CGPoint(x: v[0], y: v[1]))
      case 11: path.addLine(to: CGPoint(x: v[0], y: v[1]))
      case 12, 13:
        path.addQuadCurve(
          to: CGPoint(x: v[2], y: v[3]),
          control: CGPoint(x: v[0], y: v[1]))
      case 14:
        path.addCurve(
          to: CGPoint(x: v[4], y: v[5]),
          control1: CGPoint(x: v[0], y: v[1]),
          control2: CGPoint(x: v[2], y: v[3]))
      case 15: path.closeSubpath()
      default: break
      }
    }
    return path
  }
}
