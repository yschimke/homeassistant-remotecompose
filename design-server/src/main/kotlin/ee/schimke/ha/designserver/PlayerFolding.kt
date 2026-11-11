package ee.schimke.ha.designserver

import ee.schimke.composeai.rcplayer.edit.RcApproximation
import ee.schimke.composeai.rcplayer.edit.RcDisplay
import ee.schimke.composeai.rcplayer.edit.RcPlayerFeature
import ee.schimke.composeai.rcplayer.edit.RcTargetProfile
import ee.schimke.composeai.rcplayer.edit.RcTargetProfiles
import ee.schimke.composeai.rcplayer.edit.adaptTo
import ee.schimke.composeai.rcplayer.edit.hardcodeDensity
import ee.schimke.composeai.rcplayer.edit.hardcodeUnboundNamedValues
import ee.schimke.composeai.rcplayer.edit.optimize
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcHeaderProperty
import ee.schimke.composeai.rcplayer.protocol.RcHeaderValue
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import java.lang.reflect.Modifier
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull

/**
 * A design made for one player: a display it draws on, and what it can play. The wall display API
 * asks for it (`POST /api/cards/resolve` with `player`), so an ESP32 panel gets a document it can
 * draw rather than one written for a phone.
 *
 * A `RemoteDensity.Host` design writes its sizes as expressions over the player's density and font
 * size. The display is baked in ([hardcodeDensity]), every named value the host won't push is
 * frozen at the document's own value (`host.ambient`: a wall panel is never in a watch's always-on
 * mode), and the document is adapted to the player's [Profile] ([adaptTo]): operations it lacks
 * rewritten where they can be, and forms of operations it gets wrong ([RcPlayerFeature]) written
 * another way, constant sizes and colours as literals. Then it is [optimize]d, and given the
 * screen's size as its canvas when the player shows one card on the whole screen.
 *
 * The result is right only for that [Player]: served under its own sha, beside the design it was
 * made from.
 */
object PlayerFolding {
  /**
   * The display a player draws on: its density and font scale, and its size in dp when it shows one
   * card on the whole screen ([width] by [height]; null keeps the design's own canvas).
   */
  data class Display(
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
      require((width == null) == (height == null)) { "width and height go together" }
    }
  }

  /**
   * What a player can play: a [base] profile (a name in [BASES]) without some of its operations
   * ([withoutOpcodes]) and some forms of them ([withoutFeatures]).
   */
  data class Profile(
    val base: String,
    val withoutOpcodes: Set<Int> = emptySet(),
    val withoutFeatures: Set<RcPlayerFeature> = emptySet(),
  ) {
    init {
      require(base in BASES) { "unknown profile $base: ${(BASES.keys + NAMED.keys).sorted()}" }
    }

    /** The rc-players profile [adaptTo] writes for. */
    val target: RcTargetProfile
      get() =
        BASES.getValue(base)
          .let { if (withoutOpcodes.isEmpty()) it else it.without(*withoutOpcodes.toIntArray()) }
          .let {
            if (withoutFeatures.isEmpty()) it else it.without(*withoutFeatures.toTypedArray())
          }

    companion object {
      /** The players rc-players models, by the name a request gives them. */
      val BASES: Map<String, RcTargetProfile> =
        mapOf(
          "widgets-v6" to RcTargetProfiles.WIDGETS_V6,
          "widgets-v7" to RcTargetProfiles.WIDGETS_V7,
          "androidx" to RcTargetProfiles.ANDROIDX,
          "androidx-experimental" to RcTargetProfiles.ANDROIDX_EXPERIMENTAL,
          "wear-widgets" to RcTargetProfiles.WEAR_WIDGETS,
        )

      /**
       * Kobento's MCU player (rccore): it decodes what AndroidX does, `CoreText` included, but
       * never resolves a variable a padding nested in a layout reads (the card lays out as NaN) and
       * reads a `TextLayout`'s colour only as a literal.
       */
      val KOBENTO_MCU: Profile =
        Profile(
          "androidx",
          withoutFeatures =
            setOf(RcPlayerFeature.VARIABLE_FLOAT_FIELDS, RcPlayerFeature.DYNAMIC_TEXT_COLOR),
        )

      /** Players known by name: each base as it is, and the families with their own gaps. */
      val NAMED: Map<String, Profile> =
        BASES.keys.associateWith { Profile(it) } + ("kobento-mcu" to KOBENTO_MCU)

      /**
       * A request's profile: a name (`"kobento-mcu"`), or `{"base": …, "without": […]}` where each
       * entry is `op:<opcode name or number>` (`op:CORE_TEXT`, `op:239`) or `feature:<feature>`
       * (`feature:variable-float-fields`). Null is [KOBENTO_MCU], the small player the wall display
       * API was made for. Throws [IllegalArgumentException] on anything else.
       */
      fun parse(json: JsonElement?): Profile =
        when (json) {
          null -> KOBENTO_MCU
          is JsonPrimitive -> {
            val name = json.contentOrNull?.takeIf { json.isString }
            requireNotNull(name?.let(NAMED::get)) {
              "unknown profile ${json}: ${NAMED.keys.sorted()}"
            }
          }
          is JsonObject -> {
            val base = (json["base"] as? JsonPrimitive)?.contentOrNull ?: "androidx"
            val named =
              requireNotNull(NAMED[base]) { "unknown profile $base: ${NAMED.keys.sorted()}" }
            val without =
              when (val entries = json["without"]) {
                null -> emptyList()
                is JsonArray ->
                  entries.map {
                    (it as? JsonPrimitive)?.takeIf { it.isString }?.content
                      ?: throw IllegalArgumentException("not a without entry: $it")
                  }
                else -> throw IllegalArgumentException("without must be a list: $entries")
              }
            named.without(without)
          }
          else -> throw IllegalArgumentException("not a profile: $json")
        }

      private val OPCODES: Map<String, Int> =
        RcOpcodes::class
          .java
          .declaredFields
          .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
          .associate { it.name to it.getInt(null) }

      private fun opcode(name: String): Int =
        name.toIntOrNull()
          ?: OPCODES[name.uppercase().replace('-', '_')]
          ?: throw IllegalArgumentException("unknown operation $name")

      private fun feature(name: String): RcPlayerFeature =
        RcPlayerFeature.entries.firstOrNull { it.name == name.uppercase().replace('-', '_') }
          ?: throw IllegalArgumentException(
            "unknown feature $name: ${RcPlayerFeature.entries.map { it.wireName }}",
          )

      private val RcPlayerFeature.wireName: String
        get() = name.lowercase().replace('_', '-')
    }

    /** This profile also without each `op:` and `feature:` entry. */
    fun without(entries: List<String>): Profile {
      val opcodes = withoutOpcodes.toMutableSet()
      val features = withoutFeatures.toMutableSet()
      for (entry in entries) {
        val kind = entry.substringBefore(':', "")
        val name = entry.substringAfter(':')
        when (kind) {
          "op" -> opcodes += opcode(name)
          "feature" -> features += feature(name)
          else ->
            throw IllegalArgumentException(
              "$entry: an entry is op:<operation> or feature:<feature>",
            )
        }
      }
      return copy(withoutOpcodes = opcodes, withoutFeatures = features)
    }
  }

  /** What a request asks a design to be made for. */
  data class Player(val display: Display = Display(), val profile: Profile = Profile.KOBENTO_MCU)

  /** A folded design: its bytes and sha256, and what [Player.profile] still can't play in it. */
  class Folded(val bytes: ByteArray, val sha256: String, val notes: List<String>)

  /**
   * [design] made for [player], or null when it can't be decoded or adapted. [bound] are the named
   * values the host will push into it (the resolve's bindings); every other one is frozen.
   */
  fun fold(design: ByteArray, player: Player, bound: Set<String> = emptySet()): Folded? =
    runCatching {
      val display = player.display
      val target = player.profile.target
      val adaptation =
        RcDocumentCodec.decode(design, RcWireLimits())
          .hardcodeUnboundNamedValues(bound)
          .hardcodeDensity(display.density, display.fontScale)
          .optimize()
          .adaptTo(
            target,
            setOf(RcApproximation.TEXT),
            RcDisplay(display.density, display.fontScale),
          )
      val bytes =
        RcDocumentCodec.encode(
          adaptation.document.optimize().sized(display.width, display.height),
        )
      Folded(
        bytes,
        sha256(bytes),
        adaptation.violations.map { "Not playable on ${target.name}: ${it.message}" }.distinct(),
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
    synchronized(this) {
      bySource[key]?.let {
        // The two caches age apart: a design resolved again is advertised again, so its bytes
        // must be fetchable again, however long ago a player last downloaded them.
        blobs[it.sha256] = it.bytes
        return it
      }
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

  /**
   * A request's `player`: `{"display": {...}, "profile": ...}`, or the first form's flat
   * `{"density", "fontScale", "width", "height"}`, which is the display alone and
   * [Profile.KOBENTO_MCU]. Throws [IllegalArgumentException] on a bad display or profile.
   */
  fun parsePlayer(json: JsonObject): Player {
    val displayJson = json["display"] as? JsonObject ?: json
    fun number(name: String) = (displayJson[name] as? JsonPrimitive)?.floatOrNull
    val display =
      Display(
        density = number("density") ?: 1f,
        fontScale = number("fontScale") ?: 1f,
        width = number("width")?.toInt(),
        height = number("height")?.toInt(),
      )
    return Player(display, Profile.parse(json["profile"]))
  }

  private const val MAX_BLOBS = 512

  /** The largest screen side a player may ask for, in dp. */
  const val MAX_SIDE = 4096

  private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
