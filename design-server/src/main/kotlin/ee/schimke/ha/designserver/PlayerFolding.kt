package ee.schimke.ha.designserver

import ee.schimke.composeai.rcplayer.edit.RcApproximation
import ee.schimke.composeai.rcplayer.edit.RcDisplay
import ee.schimke.composeai.rcplayer.edit.RcTargetProfiles
import ee.schimke.composeai.rcplayer.edit.adaptTo
import ee.schimke.composeai.rcplayer.edit.hardcodeDensity
import ee.schimke.composeai.rcplayer.edit.hardcodeUnboundNamedValues
import ee.schimke.composeai.rcplayer.edit.optimize
import ee.schimke.composeai.rcplayer.protocol.RcColorConstant
import ee.schimke.composeai.rcplayer.protocol.RcColorExpression
import ee.schimke.composeai.rcplayer.protocol.RcColorTheme
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcHeaderProperty
import ee.schimke.composeai.rcplayer.protocol.RcHeaderValue
import ee.schimke.composeai.rcplayer.protocol.RcOperation
import ee.schimke.composeai.rcplayer.protocol.RcTextLayout
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import java.security.MessageDigest

/**
 * A design made simple for a small player: one that shows a card on one display and can't follow a
 * phone's density and font scale. The wall display API asks for it (`POST /api/cards/resolve` with
 * `player`), so an ESP32 panel gets literals rather than expressions to evaluate.
 *
 * A `RemoteDensity.Host` design writes its sizes as expressions over the player's density and font
 * size, and its text and padding read them by variable. A microcontroller player (Kobento's rccore)
 * leaves a padding nested in a layout that reads a variable unresolved, so such a card lays out as
 * NaN and draws nothing. The fix is the one AndroidX's launcher widgets need for the same reason:
 * bake the display in ([hardcodeDensity]), then adapt the document to the level 6 profile
 * ([adaptTo], [RcTargetProfiles.WIDGETS_V6]), which folds every constant size into the literal
 * field and writes `CoreText` as the older `TextLayout`, then [optimize] what's left.
 *
 * Two more for the same player. Every named value the host won't push (`host.ambient`: a wall panel
 * is never in a watch's always-on mode) is frozen at the document's own value, so what it chooses
 * between — a text colour — folds too. And a `TextLayout` whose colour is a reference to a constant
 * gets the colour itself: rccore reads that field only as a literal ([literalTextColors]).
 *
 * The result is right only for [Player.density] and [Player.fontScale]: served under its own sha,
 * beside the design it was made from.
 */
object PlayerFolding {
  /**
   * What a request asks the design to be made for: the display's density and font scale, and its
   * size in dp when it shows one card on the whole screen ([width] by [height]; null keeps the
   * design's own canvas).
   */
  data class Player(
    val density: Float = 1f,
    val fontScale: Float = 1f,
    val width: Int? = null,
    val height: Int? = null,
  ) {
    init {
      require(density.isFinite() && density > 0f) { "density must be positive: $density" }
      require(fontScale.isFinite() && fontScale > 0f) { "fontScale must be positive: $fontScale" }
      require(width == null || width in 1..MAX_SIDE) { "width out of range: $width" }
      require(height == null || height in 1..MAX_SIDE) { "height out of range: $height" }
    }
  }

  /** A folded design: its bytes and sha256, and what the adaptation couldn't make playable. */
  class Folded(val bytes: ByteArray, val sha256: String, val notes: List<String>)

  /**
   * [design] made for [player], or null when it can't be decoded or adapted. [bound] are the named
   * values the host will push into it (the resolve's bindings); every other one is frozen.
   */
  fun fold(design: ByteArray, player: Player, bound: Set<String> = emptySet()): Folded? =
    runCatching {
      val display = RcDisplay(player.density, player.fontScale)
      val adaptation =
        RcDocumentCodec.decode(design, RcWireLimits())
          .hardcodeUnboundNamedValues(bound)
          .hardcodeDensity(player.density, player.fontScale)
          .optimize()
          .adaptTo(RcTargetProfiles.WIDGETS_V6, setOf(RcApproximation.TEXT), display)
      val bytes =
        RcDocumentCodec.encode(
          adaptation.document.optimize().literalTextColors().sized(player.width, player.height),
        )
      Folded(
        bytes,
        sha256(bytes),
        adaptation.violations.map { "Not simplified for a small player: $it" }.distinct(),
      )
    }
    .getOrNull()

  /**
   * Folded designs by their own sha, served from the blob route beside the library's. Bounded: a
   * dashboard's worth of designs per player, many times over.
   */
  private val blobs =
    object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>) =
        size > MAX_BLOBS
    }

  private data class Source(val sha256: String, val player: Player, val bound: Set<String>)

  /**
   * Folds by the design they were made from, the player and what's pushed, so each is done once.
   */
  private val bySource =
    object : LinkedHashMap<Source, Folded>(64, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Source, Folded>) =
        size > MAX_BLOBS
    }

  /**
   * [design] (whose sha is [sha256]) for [player] with [bound] pushed, remembered and its blob kept
   * to serve.
   */
  fun forPlayer(
    sha256: String,
    design: ByteArray,
    player: Player,
    bound: Set<String> = emptySet(),
  ): Folded? {
    val key = Source(sha256, player, bound)
    synchronized(this) { bySource[key] }
      ?.let {
        return it
      }
    val folded = fold(design, player, bound) ?: return null
    synchronized(this) {
      bySource[key] = folded
      blobs[folded.sha256] = folded.bytes
    }
    return folded
  }

  /** A folded design's bytes by its sha, or null. */
  fun blob(sha256: String): ByteArray? = synchronized(this) { blobs[sha256] }

  /**
   * This document with each `TextLayout` whose colour is a reference (`FLAG_DYNAMIC_COLOR`) to a
   * colour nothing can change — one `ColorConstant` and no other writer of its id — drawing that
   * colour as the literal. AndroidX reads either; rccore only the literal, so a reference there
   * draws the id as a transparent colour.
   */
  internal fun RcDocument.literalTextColors(): RcDocument {
    val constants =
      operations.filterIsInstance<RcColorConstant>().groupBy { it.id }.filterValues { it.size == 1 }
    val dynamic = RcTextLayout.FLAG_DYNAMIC_COLOR shl 16
    var changed = false
    val rewritten = operations.map { op ->
      if (op !is RcTextLayout || op.textAlignAndFlags and dynamic == 0) return@map op
      val constant = constants[op.color]?.single() ?: return@map op
      // Only a colour no other operation writes.
      if (operations.any { it !== constant && it.writesColorId(op.color) }) return@map op
      changed = true
      op.copy(color = constant.argb, textAlignAndFlags = op.textAlignAndFlags and dynamic.inv())
    }
    return if (changed) copy(operations = rewritten) else this
  }

  /**
   * This document declaring [width] by [height] (dp) as its canvas, so a player that fits a
   * document to its screen by the header's size lays it out at exactly the screen's: one card
   * filling the panel, nothing scaled. Unchanged when either is null.
   */
  internal fun RcDocument.sized(width: Int?, height: Int?): RcDocument {
    if (width == null || height == null) return this
    if (!header.modern)
      return copy(header = header.copy(legacyWidth = width, legacyHeight = height))
    val kept =
      header.properties.filter { it.key != RcHeader.DOC_WIDTH && it.key != RcHeader.DOC_HEIGHT }
    val size =
      listOf(
        RcHeaderProperty(RcHeader.DOC_WIDTH, RcHeaderValue.IntValue(width)),
        RcHeaderProperty(RcHeader.DOC_HEIGHT, RcHeaderValue.IntValue(height)),
      )
    return copy(header = header.copy(properties = size + kept))
  }

  /** Whether this operation writes colour [id]: a colour constant, expression or theme colour. */
  private fun RcOperation.writesColorId(id: Int): Boolean =
    when (this) {
      is RcColorConstant -> this.id == id
      is RcColorExpression -> outId == id
      is RcColorTheme -> outId == id
      else -> false
    }

  private const val MAX_BLOBS = 512

  /** The largest screen side a player may ask for, in dp. */
  const val MAX_SIDE = 4096

  private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
