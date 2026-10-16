package ee.schimke.terrazzo.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import ee.schimke.terrazzo.core.session.DemoData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * Stand-in pictures for demo mode. The demo dashboards reference Home Assistant pictures (camera
 * frames, Bambu cover and pick images, add-on and integration icons) by server-relative URL, which
 * resolve against [DemoData.BASE_URL] — a host nothing serves. [render] draws a believable picture
 * for each one instead, so demo cards look like a real installation rather than grey boxes:
 * - `/api/camera_proxy/<camera>` — a camera frame: a yard (or, for a 3D printer, the build chamber)
 *   with the frame's time burned in as an on-screen display, like a real camera's OSD.
 * - `/api/image_proxy/<image>` — a Bambu plate: a pick image (`*pick*`) is the flat top-down object
 *   mask; anything else is the cover render of the plate with its print.
 * - anything else — an initial avatar, for `entity_picture` icons and person pictures.
 *
 * Drawn, not bundled, so the APK carries no image assets. Deterministic: the same URL always draws
 * the same pixels (colours and layout come from the entity id, motion from the camera frame).
 */
object DemoImages {

  /** True for a URL on the demo host — the only URLs [render] draws. */
  fun isDemoUrl(url: String): Boolean = url.startsWith(DEMO_PREFIX)

  /** A stand-in picture for demo [url], or null when [url] is not on the demo host. */
  fun render(url: String): Bitmap? {
    if (!isDemoUrl(url)) return null
    val path = url.removePrefix(DEMO_PREFIX).substringBefore('?').substringBefore('#')
    val query = url.substringAfter('?', "").substringBefore('#')
    val segments = path.split('/').filter { it.isNotEmpty() }
    return when {
      segments.size >= 3 && segments[0] == "api" && segments[1] == "camera_proxy" -> {
        val frame =
          query
            .split('&')
            .firstOrNull { it.startsWith("frame=") }
            ?.substringAfter('=')
            ?.toLongOrNull() ?: 0L
        camera(segments[2], frame)
      }
      segments.size >= 3 && segments[0] == "api" && segments[1] == "image_proxy" ->
        if ("pick" in segments[2]) pickImage(segments[2]) else coverImage(segments[2])
      else -> avatar(path)
    }
  }

  // ——— Cameras ———

  private fun camera(entityId: String, frame: Long): Bitmap {
    val bitmap = Bitmap.createBitmap(CAMERA_W, CAMERA_H, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val timeMs = frame * DemoData.CAMERA_FRAME_MILLIS
    val hour = hourOf(timeMs)
    val night = hour < 6 || hour >= 20
    if (PRINTER.containsMatchIn(entityId)) {
      printerChamber(canvas, entityId, frame)
    } else {
      yard(canvas, entityId, frame)
      if (night) infrared(bitmap)
    }
    vignette(canvas)
    grain(canvas, frame)
    osd(canvas, timeMs, cameraLabel(entityId))
    return bitmap
  }

  /** An outdoor camera: sky, tree line, lawn, path, fence and a corner of the house. */
  private fun yard(canvas: Canvas, entityId: String, frame: Long) {
    val rnd = Random(entityId.hashCode())
    val w = CAMERA_W.toFloat()
    val h = CAMERA_H.toFloat()
    val horizon = h * (0.36f + rnd.nextFloat() * 0.08f)
    val mirrored = rnd.nextBoolean()
    canvas.save()
    if (mirrored) canvas.scale(-1f, 1f, w / 2f, h / 2f)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.shader =
      LinearGradient(
        0f,
        0f,
        0f,
        horizon,
        0xFF6F9FD0.toInt(),
        0xFFC9DDEB.toInt(),
        Shader.TileMode.CLAMP,
      )
    canvas.drawRect(0f, 0f, w, horizon, paint)
    paint.shader = null

    // Clouds drift a few pixels a frame.
    paint.color = 0xCCFFFFFF.toInt()
    for (i in 0 until 3) {
      val cx = ((rnd.nextFloat() * w + frame * (3 + i)) % (w + 160f)) - 80f
      val cy = horizon * (0.2f + rnd.nextFloat() * 0.45f)
      canvas.drawOval(RectF(cx - 60f, cy - 12f, cx + 60f, cy + 12f), paint)
      canvas.drawOval(RectF(cx - 30f, cy - 24f, cx + 25f, cy + 6f), paint)
    }

    // Tree line.
    paint.color = 0xFF2F4A2C.toInt()
    var x = -20f
    while (x < w + 20f) {
      val r = 22f + rnd.nextFloat() * 26f
      canvas.drawCircle(x, horizon - r * 0.4f, r, paint)
      x += r * 1.2f
    }

    // Lawn.
    paint.shader =
      LinearGradient(
        0f,
        horizon,
        0f,
        h,
        0xFF5C8A3E.toInt(),
        0xFF3E6B2A.toInt(),
        Shader.TileMode.CLAMP,
      )
    canvas.drawRect(0f, horizon - 4f, w, h, paint)
    paint.shader = null

    // Path in perspective.
    paint.color = 0xFFB9AE9C.toInt()
    val pathX = w * (0.45f + rnd.nextFloat() * 0.15f)
    canvas.drawPath(
      Path().apply {
        moveTo(pathX - 12f, horizon + 6f)
        lineTo(pathX + 12f, horizon + 6f)
        lineTo(pathX + 150f, h)
        lineTo(pathX - 70f, h)
        close()
      },
      paint,
    )

    // Fence along the horizon.
    paint.color = 0xFF8A6A4A.toInt()
    canvas.drawRect(0f, horizon - 10f, pathX - 30f, horizon - 6f, paint)
    canvas.drawRect(0f, horizon + 2f, pathX - 30f, horizon + 6f, paint)
    var post = 6f
    while (post < pathX - 30f) {
      canvas.drawRect(post, horizon - 22f, post + 5f, horizon + 12f, paint)
      post += 18f
    }

    // The house wall the camera hangs on, with a window.
    val wallLeft = w * 0.78f
    paint.color = HOUSE_COLOURS[rnd.nextInt(HOUSE_COLOURS.size)]
    canvas.drawPath(
      Path().apply {
        moveTo(wallLeft, 0f)
        lineTo(w, 0f)
        lineTo(w, h)
        lineTo(wallLeft - 40f, h)
        close()
      },
      paint,
    )
    paint.color = 0xFF2B3440.toInt()
    canvas.drawRect(wallLeft + 20f, h * 0.30f, wallLeft + 90f, h * 0.62f, paint)
    paint.color = 0x553D5A80
    canvas.drawRect(wallLeft + 26f, h * 0.32f, wallLeft + 84f, h * 0.60f, paint)

    // A planter by the path.
    paint.color = 0xFF6B4B33.toInt()
    canvas.drawRect(pathX - 132f, h * 0.76f, pathX - 104f, h * 0.86f, paint)
    paint.color = 0xFF2E5E2A.toInt()
    canvas.drawCircle(pathX - 118f, h * 0.73f, 20f, paint)
    canvas.restore()
  }

  /** A 3D printer's chamber camera: the textured plate, the toolhead and a print growing on it. */
  private fun printerChamber(canvas: Canvas, entityId: String, frame: Long) {
    val rnd = Random(entityId.hashCode())
    val w = CAMERA_W.toFloat()
    val h = CAMERA_H.toFloat()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Enclosure, lit warm from the chamber light above.
    paint.shader =
      RadialGradient(
        w * 0.5f,
        -h * 0.2f,
        h * 1.3f,
        0xFF6E6152.toInt(),
        0xFF141414.toInt(),
        Shader.TileMode.CLAMP,
      )
    canvas.drawRect(0f, 0f, w, h, paint)
    paint.shader = null

    // Build plate in perspective: textured PEI, dark with a gold speckle.
    val plate =
      Path().apply {
        moveTo(w * 0.22f, h * 0.60f)
        lineTo(w * 0.78f, h * 0.60f)
        lineTo(w * 0.95f, h * 0.92f)
        lineTo(w * 0.05f, h * 0.92f)
        close()
      }
    paint.color = 0xFF2A2724.toInt()
    canvas.drawPath(plate, paint)
    paint.color = 0x55C9A45C
    val speckle = Random(7)
    repeat(220) {
      val t = speckle.nextFloat()
      val y = h * (0.60f + 0.32f * t)
      val half = w * (0.28f + 0.17f * t)
      canvas.drawCircle(w * 0.5f + (speckle.nextFloat() * 2f - 1f) * half, y, 1.2f, paint)
    }
    paint.color = 0xFF4A4643.toInt()
    canvas.drawRect(w * 0.05f, h * 0.92f, w * 0.95f, h * 0.95f, paint)

    // The print: layers stacking up over the frames.
    val colour = FILAMENT[rnd.nextInt(FILAMENT.size)]
    val progress = 0.25f + 0.75f * (frame % PRINT_FRAMES).toFloat() / PRINT_FRAMES
    val baseY = h * 0.78f
    val printH = h * 0.30f * progress
    val left = w * 0.42f
    val right = w * 0.58f
    paint.shader =
      LinearGradient(
        left,
        0f,
        right,
        0f,
        shade(colour, 1.15f),
        shade(colour, 0.7f),
        Shader.TileMode.CLAMP,
      )
    canvas.drawRect(left, baseY - printH, right, baseY, paint)
    paint.shader = null
    paint.color = shade(colour, 1.3f)
    canvas.drawOval(RectF(left, baseY - printH - 6f, right, baseY - printH + 6f), paint)
    paint.color = 0x22000000
    var layer = baseY
    while (layer > baseY - printH) {
      canvas.drawLine(left, layer, right, layer, paint)
      layer -= 3f
    }

    // Toolhead on its gantry, sweeping across the print.
    val sweep = (frame % 6) / 5f
    val headX = left + (right - left) * sweep
    val headY = baseY - printH - 54f
    paint.color = 0xFF3A3A3A.toInt()
    canvas.drawRect(0f, headY - 6f, w, headY + 6f, paint)
    paint.color = 0xFFE5E5E5.toInt()
    canvas.drawRoundRect(RectF(headX - 38f, headY - 30f, headX + 38f, headY + 36f), 8f, 8f, paint)
    paint.color = 0xFF2F8F4E.toInt()
    canvas.drawRect(headX - 38f, headY - 30f, headX + 38f, headY - 22f, paint)
    paint.color = 0xFFB58A3C.toInt()
    canvas.drawPath(
      Path().apply {
        moveTo(headX - 8f, headY + 36f)
        lineTo(headX + 8f, headY + 36f)
        lineTo(headX, headY + 50f)
        close()
      },
      paint,
    )
  }

  /** A night-mode camera: monochrome infrared with lifted shadows. */
  private fun infrared(bitmap: Bitmap) {
    // The IR illuminator lights what is near; the sky and the far tree line fall to black.
    val canvas = Canvas(bitmap)
    val h = bitmap.height.toFloat()
    canvas.drawRect(
      0f,
      0f,
      bitmap.width.toFloat(),
      h,
      Paint().apply {
        shader =
          LinearGradient(
            0f,
            0f,
            0f,
            h * 0.7f,
            0xF0000000.toInt(),
            0x00000000,
            Shader.TileMode.CLAMP,
          )
      },
    )
    val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
    val matrix = ColorMatrix().apply { setSaturation(0f) }
    // A faint green-grey cast, like most IR sensors.
    matrix.postConcat(ColorMatrix().apply { setScale(0.9f, 1f, 0.9f, 1f) })
    canvas.drawBitmap(copy, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) })
    copy.recycle()
  }

  private fun vignette(canvas: Canvas) {
    val w = CAMERA_W.toFloat()
    val h = CAMERA_H.toFloat()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.shader =
      RadialGradient(
        w / 2f,
        h / 2f,
        w * 0.62f,
        intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, 0x88000000.toInt()),
        floatArrayOf(0f, 0.65f, 1f),
        Shader.TileMode.CLAMP,
      )
    canvas.drawRect(0f, 0f, w, h, paint)
  }

  /** Sensor noise, different every frame, so consecutive frames are not pixel-identical. */
  private fun grain(canvas: Canvas, frame: Long) {
    val rnd = Random(frame)
    val paint = Paint()
    repeat(900) {
      paint.color = if (rnd.nextBoolean()) 0x18FFFFFF else 0x18000000
      canvas.drawPoint(rnd.nextFloat() * CAMERA_W, rnd.nextFloat() * CAMERA_H, paint)
    }
  }

  /** The camera's on-screen display: timestamp top left, camera name bottom right. */
  private fun osd(canvas: Canvas, timeMs: Long, label: String) {
    val paint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 20f
        typeface = Typeface.MONOSPACE
        setShadowLayer(2f, 1f, 1f, Color.BLACK)
      }
    val stamp = SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.ROOT).format(Date(timeMs))
    canvas.drawText(stamp, 16f, 32f, paint)
    paint.textAlign = Paint.Align.RIGHT
    canvas.drawText(label, CAMERA_W - 16f, CAMERA_H - 16f, paint)
  }

  /** `camera.back_door_camera` → `BACK DOOR`; `…_high_resolution_channel` suffixes dropped. */
  internal fun cameraLabel(entityId: String): String =
    entityId
      .substringAfter('.')
      .replace(Regex("_(ultra_)?(high|low|medium)_resolution_channel$"), "")
      .removeSuffix("_camera")
      .replace('_', ' ')
      .uppercase(Locale.ROOT)

  private fun hourOf(timeMs: Long): Int =
    java.util.Calendar.getInstance()
      .apply { timeInMillis = timeMs }
      .get(java.util.Calendar.HOUR_OF_DAY)

  // ——— Bambu plate images ———

  /** The slicer's cover render: an isometric textured plate with the print on it. */
  private fun coverImage(entityId: String): Bitmap {
    val bitmap = Bitmap.createBitmap(PLATE_PX, PLATE_PX, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val rnd = Random(entityId.substringBeforeLast("_cover").hashCode())
    val s = PLATE_PX.toFloat()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.shader =
      LinearGradient(0f, 0f, 0f, s, 0xFFEDEFF2.toInt(), 0xFFD5D9DE.toInt(), Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, s, s, paint)
    paint.shader = null

    // Isometric plate: a diamond with a visible front edge.
    val cx = s / 2f
    val cy = s * 0.62f
    val rx = s * 0.44f
    val ry = s * 0.22f
    val top =
      Path().apply {
        moveTo(cx, cy - ry)
        lineTo(cx + rx, cy)
        lineTo(cx, cy + ry)
        lineTo(cx - rx, cy)
        close()
      }
    paint.color = 0xFF55514C.toInt()
    canvas.drawPath(
      Path().apply {
        moveTo(cx - rx, cy)
        lineTo(cx, cy + ry)
        lineTo(cx + rx, cy)
        lineTo(cx + rx, cy + 10f)
        lineTo(cx, cy + ry + 10f)
        lineTo(cx - rx, cy + 10f)
        close()
      },
      paint,
    )
    paint.color = 0xFF2E2B28.toInt()
    canvas.drawPath(top, paint)
    paint.color = 0x33FFFFFF
    paint.strokeWidth = 1f
    for (i in 1 until 8) {
      val t = i / 8f
      canvas.drawLine(cx - rx * (1 - t), cy - ry * t, cx + rx * t, cy + ry * (1 - t), paint)
      canvas.drawLine(cx + rx * (1 - t), cy - ry * t, cx - rx * t, cy + ry * (1 - t), paint)
    }

    // One to three printed parts: isometric boxes and cylinders in filament colours.
    val parts = 1 + rnd.nextInt(3)
    val offsets = listOf(0f to -0.1f, -0.5f to 0.2f, 0.5f to 0.2f)
    for (i in 0 until parts) {
      val colour = FILAMENT[rnd.nextInt(FILAMENT.size)]
      val (ox, oy) = offsets[i]
      val px = cx + ox * rx
      val py = cy + oy * ry - (if (i == 0) ry * 0.1f else 0f)
      val size = s * (if (i == 0) 0.09f else 0.06f)
      val height = s * (0.10f + rnd.nextFloat() * (if (i == 0) 0.22f else 0.10f))
      if (rnd.nextBoolean()) isoCylinder(canvas, px, py, size, height, colour)
      else isoBox(canvas, px, py, size, height, colour)
    }
    return bitmap
  }

  private fun isoBox(canvas: Canvas, x: Float, y: Float, size: Float, height: Float, colour: Int) {
    val dx = size
    val dy = size / 2f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = shade(colour, 0.75f)
    canvas.drawPath(quad(x - dx, y, x, y + dy, x, y + dy - height, x - dx, y - height), paint)
    paint.color = shade(colour, 0.55f)
    canvas.drawPath(quad(x, y + dy, x + dx, y, x + dx, y - height, x, y + dy - height), paint)
    paint.color = shade(colour, 1.1f)
    canvas.drawPath(
      quad(x, y - dy - height, x + dx, y - height, x, y + dy - height, x - dx, y - height),
      paint,
    )
  }

  private fun isoCylinder(
    canvas: Canvas,
    x: Float,
    y: Float,
    r: Float,
    height: Float,
    colour: Int,
  ) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.shader =
      LinearGradient(
        x - r,
        0f,
        x + r,
        0f,
        shade(colour, 0.85f),
        shade(colour, 0.5f),
        Shader.TileMode.CLAMP,
      )
    canvas.drawOval(RectF(x - r, y - r / 2f, x + r, y + r / 2f), paint)
    canvas.drawRect(x - r, y - height, x + r, y, paint)
    paint.shader = null
    paint.color = shade(colour, 1.1f)
    canvas.drawOval(RectF(x - r, y - height - r / 2f, x + r, y - height + r / 2f), paint)
  }

  /** The pick image: the plate from above, each object a flat colour on black, numbered. */
  private fun pickImage(entityId: String): Bitmap {
    val bitmap = Bitmap.createBitmap(PLATE_PX, PLATE_PX, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val rnd = Random(entityId.substringBeforeLast("_pick").hashCode())
    val s = PLATE_PX.toFloat()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    canvas.drawColor(0xFF101010.toInt())
    paint.color = 0xFF1E1E1E.toInt()
    val inset = s * 0.06f
    canvas.drawRoundRect(RectF(inset, inset, s - inset, s - inset), 16f, 16f, paint)
    paint.color = 0xFF2A2A2A.toInt()
    paint.strokeWidth = 1f
    var g = inset
    while (g < s - inset) {
      canvas.drawLine(g, inset, g, s - inset, paint)
      canvas.drawLine(inset, g, s - inset, g, paint)
      g += s / 16f
    }
    val label =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
      }
    val parts = 2 + rnd.nextInt(3)
    for (i in 0 until parts) {
      val col = i % 2
      val row = i / 2
      val cx = s * (0.32f + 0.36f * col) + (rnd.nextFloat() - 0.5f) * s * 0.06f
      val cy = s * (0.32f + 0.36f * row) + (rnd.nextFloat() - 0.5f) * s * 0.06f
      val r = s * (0.08f + rnd.nextFloat() * 0.06f)
      paint.color = PICK_COLOURS[i % PICK_COLOURS.size]
      if (rnd.nextBoolean()) canvas.drawCircle(cx, cy, r, paint)
      else
        canvas.drawRoundRect(RectF(cx - r, cy - r * 0.8f, cx + r, cy + r * 0.8f), 10f, 10f, paint)
      canvas.drawText("${i + 1}", cx, cy + 10f, label)
    }
    return bitmap
  }

  // ——— Avatars ———

  /** An initial in a coloured circle, for entity pictures that are icons or person photos. */
  private fun avatar(path: String): Bitmap {
    val bitmap = Bitmap.createBitmap(AVATAR_PX, AVATAR_PX, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val name = avatarName(path)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = AVATAR_COLOURS[Math.floorMod(name.hashCode(), AVATAR_COLOURS.size)]
    val half = AVATAR_PX / 2f
    canvas.drawCircle(half, half, half, paint)
    paint.color = Color.WHITE
    paint.textSize = AVATAR_PX * 0.5f
    paint.textAlign = Paint.Align.CENTER
    paint.typeface = Typeface.DEFAULT_BOLD
    val initial = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    canvas.drawText(initial, half, half - (paint.descent() + paint.ascent()) / 2f, paint)
    return bitmap
  }

  /**
   * The word an avatar's initial comes from: the last path segment that names something (not `icon`
   * / `picture` / a size), without its extension or redacted `XXXX_` prefix.
   */
  internal fun avatarName(path: String): String {
    val segment =
      path
        .split('/')
        .filter { it.isNotEmpty() }
        .lastOrNull { it !in GENERIC_SEGMENTS && !it.matches(SIZE) }
        ?.substringBeforeLast('.') ?: return ""
    return segment.split('_', '-').firstOrNull { part -> part.any { it.isLetter() && it != 'X' } }
      ?: segment
  }

  // ——— Helpers ———

  private fun quad(
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    x3: Float,
    y3: Float,
  ): Path =
    Path().apply {
      moveTo(x0, y0)
      lineTo(x1, y1)
      lineTo(x2, y2)
      lineTo(x3, y3)
      close()
    }

  private fun shade(colour: Int, factor: Float): Int {
    fun channel(c: Int) = (c * factor).toInt().coerceIn(0, 255)
    return Color.argb(
      Color.alpha(colour),
      channel(Color.red(colour)),
      channel(Color.green(colour)),
      channel(Color.blue(colour)),
    )
  }

  private val DEMO_PREFIX = DemoData.BASE_URL + "/"
  private const val CAMERA_W = 640
  private const val CAMERA_H = 360
  private const val PLATE_PX = 512
  private const val AVATAR_PX = 256

  /** Frames for one demo print to grow from the plate to full height. */
  private const val PRINT_FRAMES = 90L

  /** Entity ids of 3D printer cameras: Bambu model prefixes, or the word itself. */
  private val PRINTER =
    Regex("(^|[._])(a1|a1mini|p1s|p1p|p2s|x1|x1c|x1e|h2d|h2s)(_|$)|bambu|printer|prusa")

  private val SIZE = Regex("\\d+x\\d+")
  private val GENERIC_SEGMENTS = setOf("api", "icon", "logo", "picture", "image", "static", "serve")

  private val FILAMENT =
    intArrayOf(
      0xFFF26B1D.toInt(), // orange
      0xFF1E6FD9.toInt(), // blue
      0xFF2FA84F.toInt(), // green
      0xFFD7263D.toInt(), // red
      0xFFF4C430.toInt(), // yellow
      0xFF8E44AD.toInt(), // purple
      0xFFE8E8E8.toInt(), // white
      0xFF6D7278.toInt(), // grey
    )
  private val PICK_COLOURS =
    intArrayOf(0xFFFF4D4D.toInt(), 0xFF4DD2FF.toInt(), 0xFF7CFF4D.toInt(), 0xFFFFD24D.toInt())
  private val HOUSE_COLOURS =
    intArrayOf(0xFFD8CFC0.toInt(), 0xFFB7C2C9.toInt(), 0xFFC9A98B.toInt(), 0xFFE6E1D6.toInt())
  private val AVATAR_COLOURS =
    intArrayOf(
      0xFF3F7CAC.toInt(),
      0xFF5E9E6E.toInt(),
      0xFFB5654A.toInt(),
      0xFF8C6BB1.toInt(),
      0xFFC48A2C.toInt(),
      0xFF4F8C8C.toInt(),
    )
}
