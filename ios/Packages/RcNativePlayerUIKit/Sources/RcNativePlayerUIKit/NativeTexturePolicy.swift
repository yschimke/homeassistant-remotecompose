import CoreGraphics

/// The mapping from a paint's `SHADER_MATRIX` to the transform the texture renderer applies, and
/// the tiling draw itself.
///
/// AndroidX's `MatrixAccess` exposes a 3x3 in its own layout —
/// `[scaleX, skewX, translateX, skewY, scaleY, translateY, persp0, persp1, persp2]` — which is not
/// the argument order `CGAffineTransform` takes, so the reordering is worth stating once and
/// testing rather than repeating at the draw site.
public enum NativeTexturePolicy {
  /// A transform for the shader's 3x3 matrix, or the identity when no matrix was named.
  public static func transform(_ matrix: [Float]?) -> CGAffineTransform {
    guard let matrix, matrix.count == 9 else { return .identity }
    return CGAffineTransform(
      a: CGFloat(matrix[0]), b: CGFloat(matrix[3]), c: CGFloat(matrix[1]),
      d: CGFloat(matrix[4]), tx: CGFloat(matrix[2]), ty: CGFloat(matrix[5]))
  }

  /// Whether an axis tiles by mirroring. AndroidX: 0 clamp, 1 repeat, 2 mirror, 3 decal.
  public static func mirrors(tileModeX: Int, tileModeY: Int) -> (x: Bool, y: Bool) {
    (x: tileModeX == 2, y: tileModeY == 2)
  }

  /// Whether a tile mode paints only where the bitmap is, rather than tiling it.
  public static func isDecal(_ tileMode: Int) -> Bool {
    tileMode == 3
  }

  /// The Core Graphics interpolation for a paint's filter quality.
  ///
  /// AndroidX: 0 none, 1 low, 2 medium, 3 high, anything else low. Nil when the paint never said,
  /// so the context's own default stands rather than this player inventing a preference.
  public static func interpolationQuality(forFilterQuality quality: Int?) -> CGInterpolationQuality?
  {
    switch quality {
    case 0: return CGInterpolationQuality.none
    case 1: return .low
    case 2: return .medium
    case 3: return .high
    default: return nil
    }
  }

  /// The most tiles one fill will draw before falling back to the bitmap's own rect.
  ///
  /// A texture mapped far smaller than the shape it fills is the pathological case — a 1-point
  /// bitmap over a full canvas is tens of thousands of draws — and a single stretched draw is a
  /// better failure than an unbounded loop.
  private static let maximumTiles = 4_096

  /// Fills the context's current clip with the bitmap texture shader.
  ///
  /// The shader's matrix is in the same user space as the clipped path, so tiles are drawn as
  /// transformed images rather than through a `CGPattern`. A pattern matrix is expressed in the
  /// context's device space and ignores the CTM, and on a UIKit layer context that mismatch made
  /// the fill paint nothing at all; drawing each tile through the CTM is both simpler to reason
  /// about and what the reference does.
  ///
  /// Clamp (0) and repeat (1) both repeat; clamp only clamps where the reference does when the
  /// shape lies inside the mapped bitmap, and repeats rather than extending the edge pixels
  /// outside it. Mirror (2) flips alternate tiles, and decal (3) paints only the bitmap's own
  /// area.
  public static func paint(
    image: CGImage,
    transform shaderTransform: CGAffineTransform,
    tileModeX: Int,
    tileModeY: Int,
    in context: CGContext
  ) {
    let width = CGFloat(image.width)
    let height = CGFloat(image.height)
    guard width > 0, height > 0 else { return }
    let determinant =
      shaderTransform.a * shaderTransform.d - shaderTransform.b * shaderTransform.c
    guard determinant != 0 else { return }
    let mirror = mirrors(tileModeX: tileModeX, tileModeY: tileModeY)
    let clip = context.boundingBoxOfClipPath
    guard !clip.isEmpty, !clip.isInfinite, !clip.isNull else { return }
    let clipInBitmap = clip.applying(shaderTransform.inverted())
    let minX = Int(floor(clipInBitmap.minX / width))
    let maxX = Int(floor(clipInBitmap.maxX / width))
    let minY = Int(floor(clipInBitmap.minY / height))
    let maxY = Int(floor(clipInBitmap.maxY / height))

    func drawTile(_ x: Int, _ y: Int) {
      context.saveGState()
      context.concatenate(shaderTransform)
      context.translateBy(
        x: CGFloat(x) * width + width / 2, y: CGFloat(y) * height + height / 2)
      context.scaleBy(
        x: mirror.x && x % 2 != 0 ? -1 : 1,
        y: mirror.y && y % 2 != 0 ? -1 : 1)
      context.draw(
        image, in: CGRect(x: -width / 2, y: -height / 2, width: width, height: height))
      context.restoreGState()
    }

    if isDecal(tileModeX) || isDecal(tileModeY) {
      // Decal paints only where the bitmap is; any other tile would be empty.
      if minX <= 0, maxX >= 0, minY <= 0, maxY >= 0 { drawTile(0, 0) }
      return
    }
    let columns = maxX - minX + 1
    let rows = maxY - minY + 1
    guard columns > 0, rows > 0, columns * rows <= maximumTiles else {
      drawTile(0, 0)
      return
    }
    for y in minY...maxY {
      for x in minX...maxX { drawTile(x, y) }
    }
  }
}
