package ee.schimke.ha.designs

import ee.schimke.ha.client.design.DesignPublisher
import ee.schimke.ha.client.design.Sha256
import ee.schimke.ha.design.DesignCatalog
import ee.schimke.ha.design.DesignCatalogEntry
import ee.schimke.ha.design.DesignExample
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignPreviews
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.LovelaceJson
import ee.schimke.ha.rc.CaptureHost
import ee.schimke.ha.rc.CardDesignTemplates
import ee.schimke.ha.rc.CardSnapshotBindings
import ee.schimke.ha.rc.CardTemplatePlayback
import ee.schimke.ha.rc.SystemHaClock
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ThemeStyle
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The card design library the design server hosts, written to a directory:
 * - `manifest.json` and `blobs/<sha256>.rc` — what the app's `DesignRepository` fetches;
 * - `catalog.json` and `previews/` — what the design server's template browser shows;
 * - `sources/` — the checked-in designs the migrated card types were drawn from.
 *
 * Its designs are every mobile-app template shape its source cards produce — the curated
 * [LibraryFixtures] and the bundled demo dashboards (their cards and the cards nested in them) —
 * for each theme style, light and dark, and for each other card style in each mode it has (those
 * bring their own palette, so the theme style doesn't change them). A design is keyed by its card's
 * generation properties, so the library holds no dashboard's values; the catalog keeps one demo
 * card per design as its example.
 *
 * Where it runs decides how a design is captured ([CaptureHost]) and its previews drawn
 * ([CardPreviewRenderer]): on Android (Robolectric) with AndroidX, or on the JVM with the Compose
 * Multiplatform creation fork and player.
 */
object CardDesignLibrary {
  /**
   * Writes the library into [target]. It is built beside it and swapped in whole once every file is
   * written, so a design server serving [target] never sees a half-written library.
   */
  fun generate(target: File, host: CaptureHost, previews: CardPreviewRenderer): Int {
    val out = File(target.parentFile, ".${target.name}.staging")
    out.deleteRecursively()

    // Every key the source cards produce, with the first card that produced it as its example.
    val examples = LinkedHashMap<DesignKey, Example>()
    for (source in librarySources()) {
      for (key in keysOf(source.card, source.snapshot)) examples.getOrPut(key) { source }
    }
    check(examples.isNotEmpty()) { "the demo dashboards produce no designs" }

    // A card type with a checked-in design (ui-builder/designs/<type>.uid) is captured from it, so
    // editing the design is what changes the library; the rest still come from their converters.
    val built = examples.keys.map { CheckedInCardDesigns.capture(host, it) }
    val now = System.currentTimeMillis()
    val output = DesignPublisher.publish(built, serial = now, nowEpochMs = now)
    // The checked-in designs the migrated card types were drawn from, so the design server can
    // offer them for editing and the scheduled import can copy edits back (LibrarySources).
    CheckedInCardDesigns.DIRECTORY.listFiles { file -> file.extension == "uid" }
      .orEmpty()
      .forEach { it.copyTo(out.resolve("sources/${it.name}"), overwrite = true) }
    // The manifest is written last, after its blobs, previews and catalog.
    for ((path, bytes) in output.files) {
      if (path == MANIFEST_PATH) continue
      out.resolve(path).also { it.parentFile.mkdirs() }.writeBytes(bytes)
    }

    val entries = built.mapIndexed { i, design ->
      val example = examples.getValue(design.key)
      val id = idOf(design.key)
      val paths =
        DesignPreviews(
          example = DesignCatalog.previewPath(id, "example"),
          template = DesignCatalog.previewPath(id, "template"),
        )
      val playback =
        CardDesignTemplates.playback(
          CardDesignTemplates.designCard(example.card, example.snapshot),
          design.bytes,
        )
      // Bound to the example card's values, as the app plays it.
      out.write(paths.example!!, previews.render(playback, example.card, example.snapshot))
      // As published: nothing pushed, so the template's own placeholders show.
      out.write(
        paths.template!!,
        previews.render(
          CardTemplatePlayback(design.bytes, { _, _ -> NO_BINDINGS }, HaAction.None),
          card = null,
          snapshot = null,
        ),
      )
      DesignCatalogEntry(
        id = id,
        sha256 = output.manifest.designs[i].sha256,
        key = design.key,
        example =
          DesignExample(example.source, example.card.raw, exampleBindings(playback, example)),
        previews = paths,
      )
    }
    val catalog =
      DesignCatalog(serial = output.manifest.serial, generatedAtEpochMs = now, entries = entries)
    out
      .resolve(DesignCatalog.PATH)
      .writeText(json.encodeToString(DesignCatalog.serializer(), catalog))
    out.resolve(MANIFEST_PATH).writeBytes(output.files.getValue(MANIFEST_PATH))
    publishTo(out, target)
    return built.size
  }

  private fun File.write(path: String, bytes: ByteArray) {
    resolve(path).also { it.parentFile.mkdirs() }.writeBytes(bytes)
  }

  /**
   * Replaces [target] with the complete library in [staging]: the old directory moves aside, the
   * new one takes its name, then the old one is deleted. A server that loaded the old library keeps
   * its blobs in memory, and reloads when the new manifest appears.
   */
  private fun publishTo(staging: File, target: File) {
    val old = File(target.parentFile, ".${target.name}.old")
    old.deleteRecursively()
    if (target.exists()) check(target.renameTo(old)) { "couldn't move $target aside" }
    check(staging.renameTo(target)) { "couldn't move $staging to $target" }
    old.deleteRecursively()
  }

  /** The values [playback] pushes for [example]: its preview's bindings, for the catalog. */
  private fun exampleBindings(playback: CardTemplatePlayback, example: Example) =
    playback.bindings(example.snapshot, SystemHaClock).let { b ->
      buildMap<String, JsonPrimitive> {
        b.strings.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        b.booleans.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        b.ints.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        // NaN (no numeric value) isn't JSON: leave it out.
        b.floats.forEach { (k, v) -> if (v.isFinite()) put(k, JsonPrimitive(v)) }
      }
    }

  /** A source card for the library: the first card that produced a key is its example. */
  class Example(val source: String, val card: CardConfig, val snapshot: HaSnapshot)

  /**
   * Every source card, in order: the curated fixtures (one per card type and shape the parity tests
   * cover), then every card in the demo dashboards and the cards nested in them.
   */
  fun librarySources(): List<Example> =
    LibraryFixtures.all.map { Example("fixture:${it.name}", it.card, it.snapshot) } +
      DASHBOARDS.flatMap { name ->
        val (dashboard, snapshot) = demoDashboard(name)
        dashboard.allCards().map { Example("dashboard:$name", it, snapshot) }
      }

  /**
   * The library keys [card] produces: one per theme style, light and dark, and one per mode of
   * every other card style (those bring their own palette, so the theme style doesn't change them).
   */
  fun keysOf(card: CardConfig, snapshot: HaSnapshot): List<DesignKey> = buildList {
    for (style in ThemeStyle.entries) for (dark in listOf(false, true)) {
      CardDesignTemplates.keyFor(card, snapshot, style, dark)?.let(::add)
    }
    for (cardStyle in HaUberTheme.all) {
      if (cardStyle.id == HaUberTheme.Classic.id) continue
      for (dark in CardDesignTemplates.modesOf(cardStyle)) {
        CardDesignTemplates.keyFor(
            card,
            snapshot,
            ThemeStyle.entries.first(),
            dark,
            cardStyle = cardStyle,
          )
          ?.let(::add)
      }
    }
  }

  /** The demo dashboards bundled with the app (`terrazzo-core`'s `dashboards/`). */
  val DASHBOARDS =
    listOf("3d_printing", "climate", "energy", "github", "meshcore", "networks", "security")

  /** The bundled demo dashboard [name] and its entity states and devices. */
  fun demoDashboard(name: String): Pair<Dashboard, HaSnapshot> {
    fun read(file: String): String? =
      CardDesignLibrary::class
        .java
        .getResourceAsStream("/dashboards/$name/$file")
        ?.bufferedReader()
        ?.use { it.readText() }
    val config = requireNotNull(read("lovelace_config.json")) { "no bundled dashboard $name" }
    val states = requireNotNull(read("entity_states.json")) { "no states for dashboard $name" }
    val devices = read("devices.json")?.let(LovelaceJson::devices).orEmpty()
    return LovelaceJson.dashboard(config) to
      HaSnapshot(states = LovelaceJson.states(states), devices = devices)
  }

  /** A design's id within the library: a short hash of its key. */
  fun idOf(key: DesignKey): String =
    Sha256.hex(Json.encodeToString(DesignKey.serializer(), key).encodeToByteArray()).take(16)

  /** Every card in [this] dashboard, the cards nested in stacks and grids included. */
  private fun Dashboard.allCards(): List<CardConfig> =
    views
      .flatMap { view -> view.cards + view.sections.flatMap { it.cards } }
      .flatMap { it.withNested() }

  private fun CardConfig.withNested(): List<CardConfig> =
    listOf(this) +
      (raw["cards"] as? JsonArray)
        .orEmpty()
        .mapNotNull { child ->
          val obj = child as? JsonObject ?: return@mapNotNull null
          val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
          CardConfig(type = type, raw = obj)
        }
        .flatMap { it.withNested() }

  private val NO_BINDINGS = CardSnapshotBindings(emptyMap(), emptyMap(), emptyMap(), emptyMap())

  private val json = Json {
    prettyPrint = true
    encodeDefaults = true
  }

  /**
   * `DesignPublisher`'s manifest, which a client reads first: written after everything it names.
   */
  private const val MANIFEST_PATH = "manifest.json"
}

/** Draws a design's preview PNG: the card at the library's card width. */
fun interface CardPreviewRenderer {
  /**
   * [playback] drawn as PNG bytes. With [card] and [snapshot] the card's bindings are pushed;
   * without, the template shows its placeholders.
   */
  fun render(playback: CardTemplatePlayback, card: CardConfig?, snapshot: HaSnapshot?): ByteArray

  companion object {
    /** The width previews are drawn at. */
    const val CARD_WIDTH_DP: Int = 360
  }
}
