package ee.schimke.ha.designserver

import ee.schimke.ha.design.CardDesignSlots
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.design.DesignSurface
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardDesignTemplates
import ee.schimke.ha.rc.CardSnapshotBindings
import ee.schimke.ha.rc.SystemHaClock
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ThemeStyle
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Which library design draws a card, and what to push into it: `POST /api/cards/resolve`.
 *
 * Give it a card's Lovelace config (and optionally its entities' states, a card style and light or
 * dark), and it answers with the design's `.rc` and the values to push into it, so a player that
 * has no card converters (UI Builder's canvas, the `<ha-card>` embed, a web page) can draw the card
 * as the app would.
 *
 * With the states of every entity the card shows, the server runs the app's own converter for it
 * (on the JVM, [resolveExact]): the card's exact design key, that design (the library's, or
 * captured now by the [DesignGenerator]) and the values the converter pushes. That match is always
 * [CardMatch.exact].
 *
 * Without them (an anonymous card: its type and slot ids, as the web app sends it) the card's
 * generation properties can't be told, so it picks the closest library design instead: same card
 * type, same slots, the requested card style and mode, the default variant. When several shapes
 * fit, it takes the first and says so ([CardMatch.exact] false). The values are the card's slot
 * bindings computed from its states, plus the converter-only ones with the library example's values
 * ([CardSchema.fromCard]).
 */
@Serializable
data class CardMatch(
  /** The library design's id (`/designs/<id>`). */
  val design: String,
  val sha256: String,
  /** Where its `.rc` is served. */
  val blob: String,
  val key: DesignKey,
  /** False when the card's shape couldn't be told and another design may fit it better. */
  val exact: Boolean,
  /**
   * Every named value to push, by name: the card's own where known, else the example's. Null is a
   * value the card has but can't be written in JSON, such as an unavailable entity's number.
   */
  val bindings: Map<String, JsonPrimitive>,
  val notes: List<String> = emptyList(),
)

/** A resolve request: the card, its states, and the look wanted. */
data class ResolveRequest(
  val card: JsonObject,
  val states: Map<String, EntityState>,
  /** An `HaUberTheme` id; `classic` draws in an app palette. */
  val cardStyle: String = DesignKey.CLASSIC_STYLE,
  val dark: Boolean = false,
  /** For `classic`: the app palette (`baked:<palette>:<mode>`); null takes the library's first. */
  val palette: String? = null,
  /**
   * Observable shape discriminators a converter-less client (the web app) can read from a
   * single-slot card and its entity's attributes — `device_class`, `unit`, `icon`, `card_icon` —
   * never an entity id, name or value. They let [resolve] pick the one design the card's shape
   * names, instead of the first of the shapes its slots fit. Empty when none was sent.
   */
  val shapeHint: Map<String, String> = emptyMap(),
) {
  companion object {
    /**
     * [CardSchema.parseRequest]'s body, plus optional `cardStyle`, `dark`, `palette`, `shapeHint`.
     */
    fun parse(body: JsonElement): ResolveRequest {
      val (card, states) = CardSchema.parseRequest(body)
      val obj = body.jsonObject
      fun text(name: String) = (obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
      val shapeHint =
        (obj["shapeHint"] as? JsonObject)
          ?.mapNotNull { (k, v) ->
            (v as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.let { k to it }
          }
          ?.toMap()
          .orEmpty()
      return ResolveRequest(
        card = card,
        states = states,
        cardStyle = text("cardStyle") ?: DesignKey.CLASSIC_STYLE,
        dark = (obj["dark"] as? JsonPrimitive)?.booleanOrNull ?: false,
        palette = text("palette"),
        shapeHint = shapeHint,
      )
    }
  }
}

object CardResolve {
  /**
   * Shape properties a converter-less client can observe from a single-slot card and its entity's
   * attributes. [narrowByShapeHint] constrains on these; any other shape property (a slot's domain,
   * a list card's `count`, a state enum) only a converter can tell, so a hint never speaks to it.
   */
  private val OBSERVABLE_SHAPE_KEYS = setOf("device_class", "unit", "icon", "card_icon")

  /**
   * Narrow [designs] to the shape the [hint] names. The hint lists every observable property the
   * card has, so a design matches only when its shape accounts for all of them: each observable
   * property equals the hint's, and the design declares no observable property the hint lacks. A
   * hint property no design carries — a custom icon the library was never built for — means the
   * card's true shape isn't in the library, so it must stay inexact and fall back rather than
   * borrow a design that drops the property and shows the wrong icon. Returns [designs] unchanged
   * when there is no hint or nothing matches, keeping the previous "first of the fitting shapes"
   * behaviour for the shapeless path.
   */
  internal fun narrowByShapeHint(
    designs: List<LibraryDesign>,
    hint: Map<String, String>,
  ): List<LibraryDesign> {
    if (hint.isEmpty()) return designs
    val wanted = hint.filterKeys { it in OBSERVABLE_SHAPE_KEYS }
    val matching = designs.filter { design ->
      val props = DesignProperties.parse(design.key.shape)
      OBSERVABLE_SHAPE_KEYS.all { k -> props[k] == wanted[k] }
    }
    return if (matching.isEmpty()) designs else matching
  }

  /**
   * [request]'s exact design, from the app's converter: null when the request lacks the state of an
   * entity the card shows (its generation properties can't be told), or the card has no template
   * shape. Throws [DesignGenerator.UnsupportedKey] when its design can't be captured here.
   */
  suspend fun resolveExact(generator: DesignGenerator, request: ResolveRequest): CardMatch? {
    val type = (request.card["type"] as? JsonPrimitive)?.contentOrNull ?: return null
    val card = CardConfig(type = type, raw = request.card)
    val slots = CardDesignSlots.slotEntries(card) ?: return null
    if (slots.isEmpty() || slots.any { it.entityId !in request.states }) return null
    val snapshot = HaSnapshot(states = request.states)
    val cardStyle = HaUberTheme.byId(request.cardStyle)
    val palette =
      request.palette?.let { name -> ThemeStyle.entries.firstOrNull { it.name == name } }
        ?: ThemeStyle.entries.first()
    val key =
      CardDesignTemplates.keyFor(card, snapshot, palette, request.dark, cardStyle = cardStyle)
        ?: return null
    val design = generator.design(key)
    val bytes = generator.bytes(design) ?: return null
    val bindings =
      CardDesignTemplates.playback(CardDesignTemplates.designCard(card, snapshot), bytes)
        .bindings(snapshot, SystemHaClock)
    return CardMatch(
      design = DesignLibrary.idOf(key),
      sha256 = design.sha256,
      blob = design.blob,
      key = key,
      exact = true,
      bindings = bindings.toJson(),
      notes =
        if (design.generated) listOf("Captured on request: the library has no such design.")
        else emptyList(),
    )
  }

  /** Every binding as JSON; a number with no JSON form (NaN, an unavailable reading) is null. */
  private fun CardSnapshotBindings.toJson(): Map<String, JsonPrimitive> = buildMap {
    strings.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
    booleans.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
    ints.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
    floats.forEach { (k, v) -> put(k, if (v.isFinite()) JsonPrimitive(v) else JsonNull) }
  }

  /**
   * The value pushed for each of [names]: the card's [own] where it has the binding, even one with
   * no JSON form (an unavailable entity's `numeric_state` is NaN), which is null, never the
   * example's reading. Only bindings the card lacks, those its converter would add, take the
   * library [example]'s value.
   */
  internal fun pushedValues(
    names: List<String>,
    own: List<BindingSchema>,
    example: Map<String, JsonPrimitive>,
  ): Map<String, JsonPrimitive> {
    val byName = own.associateBy { it.name }
    return buildMap {
      for (name in names) {
        val value = if (name in byName) byName.getValue(name).example ?: JsonNull else example[name]
        if (value != null) put(name, value)
      }
    }
  }

  /** The design [request]'s card renders as, or null when no library design has its slots. */
  fun resolve(library: DesignLibrary, request: ResolveRequest): CardMatch? {
    val schema = CardSchema.fromCard(request.card, request.states, library)
    val candidates =
      CardSchema.templatesWithSlots(library, schema.cardType, schema.slots.map { it.slot }).filter {
        it.key.surface == DesignSurface.MobileApp && !it.entry.revoked
      }
    if (candidates.isEmpty()) return null

    val mode = if (request.dark) "dark" else "light"
    val inStyle =
      if (request.cardStyle == DesignKey.CLASSIC_STYLE) {
        val baked = candidates.filter { it.key.theme.startsWith("baked:") }
        val palette = request.palette ?: baked.firstOrNull()?.key?.theme?.split(':')?.getOrNull(1)
        baked.filter { it.key.theme == DesignKey.bakedTheme(palette.orEmpty(), request.dark) }
      } else {
        val styled = candidates.filter { it.key.cardStyle == request.cardStyle }
        // A style with one mode (Trinitron, Nixie and Console are dark only) uses it either way.
        styled.filter { it.key.theme.endsWith(":$mode") }.ifEmpty { styled }
      }
    if (inStyle.isEmpty()) return null
    val inVariant =
      inStyle.filter { it.key.variant == DesignKey.DEFAULT_VARIANT }.ifEmpty { inStyle }
    val variant = narrowByShapeHint(inVariant, request.shapeHint)
    val shapes = variant.map { it.key.shape }.distinct()
    val chosen = variant.first()

    val example = chosen.catalog?.example?.bindings.orEmpty()
    val bindings =
      pushedValues(library.decode(chosen).bindings.map { it.name }, schema.bindings, example)
    return CardMatch(
      design = chosen.id,
      sha256 = chosen.entry.sha256,
      blob = "$API_BASE/blobs/${chosen.entry.sha256}.rc",
      key = chosen.key,
      exact = shapes.size == 1,
      bindings = bindings,
      notes =
        buildList {
          if (shapes.size > 1)
            add(
              "${shapes.size} shapes of `${schema.cardType}` have these slots, and the card's " +
                "shape can't be told without its converter: this is the first of them."
            )
          addAll(schema.notes)
        },
    )
  }
}
