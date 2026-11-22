package ee.schimke.ha.designserver

import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import ee.schimke.ha.client.design.validateCardDraft
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignSurface
import ee.schimke.ha.designs.JvmCardPreviewRenderer
import ee.schimke.ha.designs.UiBuilderDesignCompiler
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.MESSAGE_CARD_TYPE
import ee.schimke.ha.rc.CardDesignTemplates
import ee.schimke.ha.rc.cards.UnsupportedCardConverter
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.livery.sessions.server.SessionImage
import ee.schimke.livery.sessions.server.SessionToolResult
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface

/**
 * What an agent in a card or dashboard session can learn and see beyond the request itself: what a
 * draft may be built with, whether it would pass, and how it draws. Card sessions draw their draft
 * with the card's current values (the ones the app sent when it seeded the session) or with sample
 * ones; dashboard sessions draw theirs with sample states only, because the server holds no Home
 * Assistant data: the person's own data reaches the agent only through `get_render`, which their
 * app uploads.
 *
 * Built once in [designServer] with the library and the compiler "Use it" publishes with, and
 * passed to the session kinds through [AgentRelay]. With no library ([holder] null, as in tests),
 * card renders need a key the app previewed and dashboard renders draw nothing.
 */
class AgentGuide(
  private val holder: LibraryHolder? = null,
  private val compiler: UiBuilderDesignCompiler = previewCompiler,
  generator: DesignGenerator? = null,
) {
  private val generator: DesignGenerator? = generator ?: holder?.let(::DesignGenerator)
  private val renderer by lazy { JvmCardPreviewRenderer() }
  private val registry = defaultRegistry()

  private fun library(): DesignLibrary? = holder?.current()

  // ---- Card sessions ----

  /** `get_card_guide`: what a draft may use, its bindings now and as sampled, and the rules. */
  fun cardGuide(session: AgentDesignSessions.Context): JsonObject {
    val variables = session.document.stateVariables()
    val samples = AgentSamples.cardValues(variables, examples(session))
    val capabilities = UiBuilderDesignCompiler.capabilities()
    return buildJsonObject {
      put("revision", session.revision)
      session.cardType?.let { put("cardType", it) }
      put(
        "rules",
        buildJsonArray {
          listOf(
              "Keep the document's schema, id, catalogPin and roots, and every existing " +
                "stateVariable exactly as it is: they are the card's binding contract. You may " +
                "add shared values (sharedValues below) and nodes.",
              "Use only the components, modifiers and value forms listed here; anything else " +
                "is refused when the person uses the draft.",
              "A value reads a binding with {\"type\": \"state\", \"variable\": \"<name>\"}; it " +
                "is drawn live only where dynamicValues.liveIn says.",
              "Call check_card_draft, then render_card (with data \"sample\" too, to see it " +
                "with other values) before submit_draft.",
              "submit_draft with the revision you read; if the draft changed since, read it " +
                "again.",
              "Attach pictures with put_card_asset and name them under document.assets.",
            )
            .forEach { add(JsonPrimitive(it)) }
        },
      )
      put("catalog", capabilities.catalog)
      putJsonArray("components") {
        capabilities.components.forEach { (id, description) ->
          add(
            buildJsonObject {
              put("id", id)
              put("draws", description)
            },
          )
        }
      }
      putJsonArray("modifiers") { capabilities.modifiers.forEach { add(JsonPrimitive(it)) } }
      putJsonArray("literalTypes") { capabilities.literalTypes.forEach { add(JsonPrimitive(it)) } }
      putJsonObject("dynamicValues") {
        putJsonArray("types") { capabilities.dynamicTypes.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("liveIn") { capabilities.dynamicLiveIn.forEach { add(JsonPrimitive(it)) } }
      }
      putJsonArray("expressions") {
        capabilities.expressions.forEach { (op, arity) ->
          add(
            buildJsonObject {
              put("op", op)
              put("arity", arity)
            },
          )
        }
      }
      putJsonArray("systemValues") { capabilities.systemValues.forEach { add(JsonPrimitive(it)) } }
      putJsonObject("remoteCalls") {
        capabilities.remoteCalls.forEach { (name, args) ->
          putJsonArray(name) { args.forEach { add(JsonPrimitive(it)) } }
        }
      }
      putJsonArray("colorTokens") { capabilities.colorTokens.forEach { add(JsonPrimitive(it)) } }
      putJsonArray("bindings") {
        variables.forEach { (name, definition) ->
          add(
            buildJsonObject {
              put("name", name)
              put("valueType", (definition as? JsonObject)?.text("valueType") ?: "string")
              put("current", (definition as? JsonObject)?.get("initialValue") ?: JsonPrimitive(""))
              samples[name]?.let { put("sample", it) }
            },
          )
        }
      }
      put("sharedValues", AgentDesignMcp.sharedValues())
      put(
        "render",
        when {
          session.key != null -> "render_card draws with the design key the person's app previewed."
          libraryKey(session.cardType) != null ->
            "render_card draws with the library's design for ${session.cardType}."
          else ->
            "render_card can't draw yet: the person's app hasn't previewed this draft and the " +
              "library has no design for its card type."
        },
      )
    }
  }

  /** `check_card_draft`: the contract gate and the compiler's problems, without submitting. */
  fun checkCard(session: AgentDesignSessions.Context, document: JsonObject): JsonObject {
    val problems = buildList {
      try {
        validateCardDraft(session.original, document)
      } catch (e: Exception) {
        add(e.message?.takeIf { it.isNotBlank() } ?: "The document isn't a card draft.")
      }
      try {
        addAll(compiler.problems(document, attached(session, document).keys))
      } catch (e: Exception) {
        add("The document can't be read: ${e.message ?: e.javaClass.simpleName}")
      }
      attachmentProblems(session, document).forEach(::add)
    }
    return buildJsonObject {
      put("ok", problems.isEmpty())
      putJsonArray("problems") { problems.forEach { add(JsonPrimitive(it)) } }
      put(
        "next",
        if (problems.isEmpty()) "render_card to see it, then submit_draft."
        else "Fix these and check again.",
      )
    }
  }

  /**
   * `render_card`: the draft compiled with the session's key and drawn with current or sample
   * values.
   */
  suspend fun renderCard(
    session: AgentDesignSessions.Context,
    arguments: JsonObject,
  ): SessionToolResult {
    val data = arguments.text("data") ?: CURRENT
    if (data != CURRENT && data != SAMPLE)
      return SessionToolResult.Failed("data is \"$CURRENT\" or \"$SAMPLE\", not \"$data\".")
    val document = arguments["document"] as? JsonObject ?: session.document
    val dark = (arguments["dark"] as? JsonPrimitive)?.booleanOrNull
    val (base, keySource) =
      session.key?.let { it to "previewed" }
        ?: libraryKey(session.cardType)?.let { it to "library" }
        ?: return SessionToolResult.Failed(
          "There's no design key to draw this card with yet: the person's app hasn't shown the " +
            "draft, and the library has no design for its card type. Ask the person to open the " +
            "draft in Terrazzo, then try again.",
        )
    val key = dark?.let { withMode(base, it) } ?: base
    val assets = attached(session, document)
    val problems =
      try {
        compiler.problems(document, assets.keys)
      } catch (e: Exception) {
        listOf("the document can't be read: ${e.message ?: e.javaClass.simpleName}")
      }
    if (problems.isNotEmpty())
      return SessionToolResult.Failed("This draft can't be drawn: ${problems.joinToString("; ")}")
    val variables = document.stateVariables()
    val values =
      if (data == SAMPLE) AgentSamples.cardValues(variables, examples(session))
      else
        variables
          .mapValues { (_, d) -> (d as? JsonObject)?.get("initialValue") as? JsonPrimitive }
          .filterValues { it != null }
          .mapValues { it.value!! }
    val png =
      try {
        withContext(compiles) {
          val bytes = compiler.compile(document, assets, key)
          renderer.render(bytes, namedValues(values, variables))
        }
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        return SessionToolResult.Failed(
          "This draft couldn't be built: ${e.message ?: e.javaClass.simpleName}",
        )
      }
    val revision =
      if (arguments["document"] == null) "Revision ${session.revision}" else "The given draft"
    return SessionToolResult.Done(
      buildJsonObject {
        put("data", data)
        put("revision", session.revision)
        put("renderedSubmittedDraft", arguments["document"] == null)
        putJsonObject("values") { values.forEach { (k, v) -> put(k, v) } }
        put("key", DesignLibrary.idOf(key))
        put("theme", key.theme)
        put("keyFrom", keySource)
      },
      listOf(
        SessionImage(
          AgentImages.fit(png),
          caption =
            "$revision with ${if (data == SAMPLE) "sample" else "the card's current"} values" +
              (if (UiBuilderDesignCompiler.isDark(key)) ", dark" else ""),
        ),
      ),
    )
  }

  private fun examples(session: AgentDesignSessions.Context) =
    AgentSamples.catalogExamples(library(), session.cardType)

  /** The session's pictures [document] names: what its compile draws. */
  private fun attached(
    session: AgentDesignSessions.Context,
    document: JsonObject,
  ): Map<String, ByteArray> =
    (document["assets"] as? JsonObject)
      .orEmpty()
      .keys
      .mapNotNull { name -> session.assets[name]?.let { name to it } }
      .toMap()

  private fun attachmentProblems(
    session: AgentDesignSessions.Context,
    document: JsonObject,
  ): List<String> = AgentDesignSessions.bindingProblems(document, session.assets)

  /**
   * A key to draw a session's card with when the app hasn't previewed it: the library's live phone
   * design for [cardType], classic and light, its default variant first.
   */
  internal fun libraryKey(cardType: String?): DesignKey? {
    cardType ?: return null
    val designs =
      library()?.designs.orEmpty().filter {
        it.key.cardType == cardType &&
          it.key.surface == DesignSurface.MobileApp &&
          !it.entry.revoked &&
          it.key.container == null
      }
    return designs
      .sortedWith(
        compareBy<LibraryDesign> { if (it.key.cardStyle == DesignKey.CLASSIC_STYLE) 0 else 1 }
          .thenBy { if (UiBuilderDesignCompiler.isDark(it.key)) 1 else 0 }
          .thenBy { if (it.key.variant == DesignKey.DEFAULT_VARIANT) 0 else 1 },
      )
      .firstOrNull()
      ?.key
  }

  // ---- Dashboard sessions ----

  /** `get_dashboard_guide`: the card types drawn, the designs a card can name, and how to look. */
  fun dashboardGuide(): JsonObject {
    val designs = choosableDesigns()
    return buildJsonObject {
      putJsonArray("cardTypes") { cardTypes().forEach { add(JsonPrimitive(it)) } }
      put(
        "cardTypesNote",
        "Terrazzo draws these card types; any other (a custom card) shows a placeholder.",
      )
      putJsonArray("designs") {
        designs.take(MAX_GUIDE_DESIGNS).forEach { d ->
          add(
            buildJsonObject {
              put("design_id", d.id)
              d.title?.let { put("title", it) }
              put("cardType", d.cardType)
              d.domain?.let { put("domain", it) }
              d.device?.let { device ->
                putJsonObject("device") {
                  device.integration?.let { put("integration", it) }
                  device.manufacturer?.let { put("manufacturer", it) }
                  device.model?.let { put("model", it) }
                  device.translationKey?.let { put("translationKey", it) }
                  device.deviceClass?.let { put("deviceClass", it) }
                }
              }
            },
          )
        }
      }
      put("designsTotal", designs.size)
      if (designs.size > MAX_GUIDE_DESIGNS)
        put("designsNote", "The first $MAX_GUIDE_DESIGNS, made-for-a-device designs first.")
      put(
        "designIdNote",
        "To draw a card with one of these, wrap it: {\"type\": \"custom:remote-compose-card\", " +
          "\"card\": {the card}, \"design_id\": \"<design_id>\"}. Only the wrapper keeps the choice " +
          "once the dashboard is applied; a design_id on the card itself is ignored. A device " +
          "design suits that device's entities only.",
      )
      putJsonArray("rules") {
        listOf(
            "render_dashboard draws your draft with made-up sample states for every entity it " +
              "names: use it to check layout before and after submit_draft.",
            "get_render shows the draft as the person's app drew it, with their own current " +
              "data, once you have called submit_draft and their app has uploaded it.",
            "submit_draft with the revision you read, the complete dashboard and a summary.",
          )
          .forEach { add(JsonPrimitive(it)) }
      }
    }
  }

  /** The card types Terrazzo draws: every converter but the placeholder ones. */
  internal fun cardTypes(): List<String> =
    registry
      .supported()
      .filter { type ->
        registry.get(type) !is UnsupportedCardConverter && type != MESSAGE_CARD_TYPE
      }
      .sorted()

  internal data class Choosable(
    val id: String,
    val title: String?,
    val cardType: String,
    val domain: String?,
    val device: ee.schimke.ha.design.DeviceMatch?,
  )

  /** The library's designs a card can name by id: one row per design and card type. */
  internal fun choosableDesigns(): List<Choosable> =
    library()
      ?.designs
      .orEmpty()
      .asSequence()
      .filter { !it.entry.revoked && it.key.surface == DesignSurface.MobileApp }
      .mapNotNull { d ->
        val id = d.entry.stableDesignId ?: return@mapNotNull null
        val device = d.entry.device?.normalized()?.takeIf { it.specificity > 0 }
        Choosable(id, d.entry.title, d.key.cardType, d.properties["domain"], device)
      }
      .distinctBy { Triple(it.id, it.cardType, it.domain) }
      .sortedWith(
        compareByDescending<Choosable> { it.device?.rank ?: -1 }
          .thenBy { it.id }
          .thenBy { it.cardType },
      )
      .toList()

  /** `render_dashboard`: the draft's cards drawn with sample states, stacked into a few images. */
  suspend fun renderDashboard(dashboard: JsonObject, arguments: JsonObject): SessionToolResult {
    val dark = (arguments["dark"] as? JsonPrimitive)?.booleanOrNull ?: false
    val onlyView = (arguments["view"] as? JsonPrimitive)?.intOrNull
    val views = dashboard["views"] as? JsonArray
    if (dashboard["strategy"] != null && views == null)
      return SessionToolResult.Failed(
        "This dashboard is generated by a strategy: it has no cards of its own to draw.",
      )
    if (views == null || views.isEmpty())
      return SessionToolResult.Failed("This dashboard has no views to draw.")
    if (onlyView != null && onlyView !in views.indices)
      return SessionToolResult.Failed("view is 0 to ${views.size - 1}, not $onlyView.")
    val snapshot = AgentSamples.snapshot(AgentSamples.entityIds(dashboard))
    val placed = placeCards(views, onlyView)
    val undrawable = mutableListOf<JsonObject>()
    placed.skipped.forEach { (path, reason) -> undrawable += problem(path, null, reason) }
    val drawn = mutableListOf<Drawn>()
    for ((index, card) in placed.cards.withIndex()) {
      if (index >= MAX_DASHBOARD_CARDS) {
        undrawable +=
          problem(
            card.path,
            card.type,
            "only $MAX_DASHBOARD_CARDS cards are drawn at once: pass view",
          )
        continue
      }
      when (val outcome = drawCard(card.config, snapshot, dark)) {
        is CardOutcome.Drawn -> drawn += Drawn(card, outcome.png, outcome.notes)
        is CardOutcome.Undrawable -> undrawable += problem(card.path, card.type, outcome.reason)
      }
    }
    val pages =
      if (drawn.isEmpty()) emptyList()
      else withContext(compiles) { AgentImages.stack(drawn.map { it.group to it.png }, dark) }
    val images = pages.map { page ->
      SessionImage(
        page.png,
        caption = "Sample data: " + page.groups.joinToString("; ") + (if (dark) " (dark)" else ""),
      )
    }
    return if (images.isEmpty())
      SessionToolResult.Failed(
        "None of this dashboard's cards could be drawn: " +
          undrawable.joinToString("; ") { "${it.text("path")}: ${it.text("reason")}" },
      )
    else
      SessionToolResult.Done(
        buildJsonObject {
          put("data", SAMPLE)
          put(
            "note",
            "Drawn with made-up states, never the person's: get_render shows their own data " +
              "after submit_draft.",
          )
          putJsonObject("sampleStates") {
            snapshot.states.values.take(MAX_LISTED_STATES).forEach { state ->
              put(state.entityId, state.state)
            }
          }
          put("sampleStatesTotal", snapshot.states.size)
          putJsonArray("cards") {
            drawn.forEachIndexed { i, d ->
              add(
                buildJsonObject {
                  put("path", d.card.path)
                  put("type", d.card.type)
                  put("section", d.group)
                  put("image", pages.indexOfFirst { i in it.cards })
                  if (d.notes.isNotEmpty())
                    putJsonArray("notes") { d.notes.forEach { add(JsonPrimitive(it)) } }
                },
              )
            }
          }
          putJsonArray("undrawable") { undrawable.forEach { add(it) } }
          putJsonArray("images") {
            images.forEachIndexed { i, image ->
              add(
                buildJsonObject {
                  put("index", i)
                  put("caption", image.caption)
                },
              )
            }
          }
        },
        images,
      )
  }

  private class Placed(val path: String, val group: String, val config: JsonObject) {
    val type: String? = config.text("type")
  }

  private class Placement(val cards: List<Placed>, val skipped: List<Pair<String, String>>)

  private class Drawn(val card: Placed, val png: ByteArray, val notes: List<String>) {
    val group: String
      get() = card.group
  }

  /** Every card of [views] (or only [onlyView]), with where it is and the section it is in. */
  private fun placeCards(views: JsonArray, onlyView: Int?): Placement {
    val cards = mutableListOf<Placed>()
    val skipped = mutableListOf<Pair<String, String>>()
    views.forEachIndexed { v, element ->
      if (onlyView != null && v != onlyView) return@forEachIndexed
      val view = element as? JsonObject ?: return@forEachIndexed
      val name = view.text("title") ?: view.text("path") ?: "View ${v + 1}"
      if (view["strategy"] != null) {
        skipped += "views[$v]" to "the view is generated by a strategy"
        return@forEachIndexed
      }
      (view["cards"] as? JsonArray)?.forEachIndexed { c, card ->
        (card as? JsonObject)?.let { cards += Placed("views[$v].cards[$c]", "$name (view $v)", it) }
      }
      (view["sections"] as? JsonArray)?.forEachIndexed { s, section ->
        val sectionObject = section as? JsonObject ?: return@forEachIndexed
        val group = "$name (view $v), section ${s + 1}"
        if (sectionObject["strategy"] != null) {
          skipped += "views[$v].sections[$s]" to "the section is generated by a strategy"
          return@forEachIndexed
        }
        (sectionObject["cards"] as? JsonArray)?.forEachIndexed { c, card ->
          (card as? JsonObject)?.let {
            cards += Placed("views[$v].sections[$s].cards[$c]", group, it)
          }
        }
      }
    }
    return Placement(cards, skipped)
  }

  private sealed interface CardOutcome {
    class Drawn(val png: ByteArray, val notes: List<String>) : CardOutcome

    class Undrawable(val reason: String) : CardOutcome
  }

  /** One card drawn as the design server resolves it ([CardResolve.resolveExact]). */
  private suspend fun drawCard(
    placed: JsonObject,
    snapshot: HaSnapshot,
    dark: Boolean,
  ): CardOutcome {
    // Only a remote-compose card keeps a chosen design once the dashboard is applied: it draws its
    // inner card with its own design_id. A design_id on any other card is ignored, as Terrazzo
    // does.
    val wrapped = placed.text("type") == REMOTE_COMPOSE_CARD
    val card =
      if (wrapped)
        placed["card"] as? JsonObject
          ?: return CardOutcome.Undrawable("a $REMOTE_COMPOSE_CARD needs its card under `card`")
      // Drawn as Terrazzo draws it, the stray key dropped: it would otherwise change the card's
      // shape, so no design would match it.
      else JsonObject(placed - "design_id")
    val type = card.text("type") ?: return CardOutcome.Undrawable("the card has no type")
    if (registry.get(type).let { it == null || it is UnsupportedCardConverter })
      return CardOutcome.Undrawable("Terrazzo doesn't draw `$type` cards: it shows a placeholder")
    val generator =
      generator ?: return CardOutcome.Undrawable("this server has no design library to draw with")
    val library = library() ?: return CardOutcome.Undrawable("no design library")
    return try {
      val config = CardConfig(type = type, raw = card)
      val key =
        CardDesignTemplates.keyFor(config, snapshot, ThemeStyle.entries.first(), dark)
          ?: return CardOutcome.Undrawable("this card has no design template to draw it with")
      val notes = mutableListOf<String>()
      if (!wrapped && placed.text("design_id") != null)
        notes +=
          "design_id is ignored on a bare card; wrap it in a $REMOTE_COMPOSE_CARD to keep a design"
      val chosenId = if (wrapped) placed.text("design_id") else null
      val chosen = chosenId?.let { id ->
        library.designs.firstOrNull {
          it.entry.stableDesignId == id && !it.entry.revoked && it.key.accepts(key)
        }
      }
      if (chosenId != null && chosen == null)
        notes += "design_id `$chosenId` has no design for this card's shape; drawn with its own"
      val bytes =
        chosen?.let { library.blob(it.entry.sha256) }
          ?: generator.design(key).let(generator::bytes)
          ?: return CardOutcome.Undrawable("its design `${DesignLibrary.idOf(key)}` has no bytes")
      val playback =
        CardDesignTemplates.playback(CardDesignTemplates.designCard(config, snapshot), bytes)
      val png = withContext(compiles) { renderer.render(playback, config, snapshot) }
      CardOutcome.Drawn(png, notes)
    } catch (e: DesignGenerator.UnsupportedKey) {
      CardOutcome.Undrawable("its design can't be drawn here: ${e.message}")
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      CardOutcome.Undrawable("it couldn't be drawn: ${e.message ?: e.javaClass.simpleName}")
    }
  }

  private fun problem(path: String, type: String?, reason: String) = buildJsonObject {
    put("path", path)
    type?.let { put("type", it) }
    put("reason", reason)
  }

  companion object {
    /** The card that draws its inner card with a chosen library design (`design_id`). */
    const val REMOTE_COMPOSE_CARD = "custom:remote-compose-card"
    const val CURRENT = "current"
    const val SAMPLE = "sample"
    private const val MAX_GUIDE_DESIGNS = 80
    private const val MAX_DASHBOARD_CARDS = 40
    private const val MAX_LISTED_STATES = 60

    /** [key] in light or [dark] mode: its theme's mode swapped, whatever its palette or style. */
    internal fun withMode(key: DesignKey, dark: Boolean): DesignKey {
      val parts = key.theme.split(':').toMutableList()
      if (parts.size < 3 || parts[2] !in setOf("light", "dark")) return key
      parts[2] = if (dark) "dark" else "light"
      return key.copy(theme = parts.joinToString(":"))
    }

    /** [values] as the named values a compiled draft reads, by each variable's `valueType`. */
    internal fun namedValues(
      values: Map<String, JsonPrimitive>,
      variables: JsonObject,
    ): Map<String, RcNamedValue> =
      values
        .mapNotNull { (name, value) ->
          val valueType = (variables[name] as? JsonObject)?.text("valueType") ?: "string"
          val named =
            when (valueType) {
              "bool" ->
                (value.booleanOrNull ?: value.doubleOrNull?.let { it != 0.0 })?.let {
                  RcNamedValue.Integer(if (it) 1 else 0)
                }
              // The compiler reads every number as a float ([UiBuilderDesignCompiler]).
              "int",
              "float" -> value.doubleOrNull?.let { RcNamedValue.FloatValue(it.toFloat()) }
              else -> value.contentOrNull?.let { RcNamedValue.Text(it) }
            }
          named?.let { name to it }
        }
        .toMap()
  }
}

private fun JsonObject.text(name: String): String? =
  (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.stateVariables(): JsonObject =
  this["stateVariables"] as? JsonObject ?: JsonObject(emptyMap())

/** PNGs an agent is shown: at most 1800 px a side (AGENTS.md) and [SessionImage.MAX_BYTES]. */
internal object AgentImages {
  const val MAX_PX = JvmCardPreviewRenderer.MAX_HEIGHT_PX
  private const val GAP = 24
  private const val PAD = 16
  private const val RULE = 6

  /** One stacked image: its PNG, the groups it shows, and which of the cards (by index). */
  class Page(val png: ByteArray, val groups: List<String>, val cards: List<Int>)

  /** [png] as it is when it fits; otherwise scaled down until it does. */
  fun fit(png: ByteArray): ByteArray {
    val image = Image.makeFromEncoded(png)
    if (png.size <= SessionImage.MAX_BYTES && image.width <= MAX_PX && image.height <= MAX_PX)
      return png
    var scale = minOf(1f, MAX_PX.toFloat() / max(image.width, image.height))
    while (true) {
      val out = encode(scaled(image, scale))
      if (out.size <= SessionImage.MAX_BYTES || scale < 0.1f) return out
      scale *= 0.8f
    }
  }

  /**
   * [cards] (a group label and its PNG, in order) stacked top to bottom onto pages at most [MAX_PX]
   * tall, a coloured rule between groups, each page fitted ([fit]).
   */
  fun stack(cards: List<Pair<String, ByteArray>>, dark: Boolean): List<Page> {
    val images = cards.mapIndexed { i, (group, png) ->
      Triple(i, group, Image.makeFromEncoded(png))
    }
    val width = (images.maxOf { it.third.width } + 2 * PAD).coerceAtMost(MAX_PX)
    val pages = mutableListOf<List<Triple<Int, String, Image>>>()
    var current = mutableListOf<Triple<Int, String, Image>>()
    var height = PAD
    for (card in images) {
      val h = card.third.height + GAP + RULE
      if (current.isNotEmpty() && height + h + PAD > MAX_PX) {
        pages += current
        current = mutableListOf()
        height = PAD
      }
      current += card
      height += h
    }
    if (current.isNotEmpty()) pages += current
    return pages.map { page ->
      val pageHeight =
        (PAD + page.sumOf { it.third.height + GAP + RULE } + PAD).coerceAtMost(MAX_PX)
      val surface = Surface.makeRasterN32Premul(width, pageHeight)
      val canvas = surface.canvas
      canvas.clear(if (dark) 0xFF111318.toInt() else 0xFFF3F4F7.toInt())
      val rule = Paint().apply { color = if (dark) 0xFF4D5868.toInt() else 0xFFB7C1D0.toInt() }
      var y = PAD.toFloat()
      var previous: String? = null
      for ((_, group, image) in page) {
        if (group != previous) {
          // A new section starts: a rule across the page above it.
          canvas.drawRect(Rect.makeXYWH(0f, y, width.toFloat(), RULE.toFloat()), rule)
          previous = group
        }
        y += RULE + GAP / 2
        draw(canvas, image, PAD.toFloat(), y, width - 2 * PAD)
        y += image.height + GAP / 2
      }
      Page(
        fit(encode(surface.makeImageSnapshot())),
        page.map { it.second }.distinct(),
        page.map { it.first },
      )
    }
  }

  private fun draw(canvas: Canvas, image: Image, x: Float, y: Float, maxWidth: Int) {
    val scale = minOf(1f, maxWidth.toFloat() / image.width)
    canvas.drawImageRect(
      image,
      Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
      Rect.makeXYWH(x, y, image.width * scale, image.height * scale),
      SamplingMode.LINEAR,
      null,
      true,
    )
  }

  private fun scaled(image: Image, scale: Float): Image {
    val width = max(1, (image.width * scale).roundToInt())
    val height = max(1, (image.height * scale).roundToInt())
    val surface = Surface.makeRasterN32Premul(width, height)
    surface.canvas.clear(Color.TRANSPARENT)
    surface.canvas.drawImageRect(
      image,
      Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
      Rect.makeWH(width.toFloat(), height.toFloat()),
      SamplingMode.LINEAR,
      null,
      true,
    )
    return surface.makeImageSnapshot()
  }

  private fun encode(image: Image): ByteArray =
    requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes
}
