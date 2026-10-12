package ee.schimke.ha.designs

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
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
import ee.schimke.ha.previews.DashboardFixtures
import ee.schimke.ha.rc.CachedCardPreview
import ee.schimke.ha.rc.CardDesignTemplates
import ee.schimke.ha.rc.CardSnapshotBindings
import ee.schimke.ha.rc.CardTemplatePlayback
import ee.schimke.ha.rc.androidXExperimentalWrap
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.ThemeStyle
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Generates the card design library into `cardDesignsOut` (see build.gradle.kts):
 * - `manifest.json` and `blobs/<sha256>.rc` — what the app's `DesignRepository` fetches;
 * - `catalog.json` and `previews/` — what the design server's template browser shows.
 *
 * Its designs are every mobile-app template shape its source cards produce — the curated
 * [LibraryFixtures] and the bundled demo dashboards (their cards and the cards nested in them) —
 * for each theme style, light and dark. A design is keyed by its card's generation properties, so
 * the library holds no dashboard's values; the catalog keeps one demo card per design as its
 * example.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GenerateCardDesignLibrary {

  @get:Rule val compose = createComposeRule()

  private class Example(val source: String, val card: CardConfig, val snapshot: HaSnapshot)

  @Test
  fun generate() {
    val out = File(requireNotNull(System.getProperty("cardDesignsOut")) { "no cardDesignsOut" })
    out.deleteRecursively()
    val context = RuntimeEnvironment.getApplication()

    // Every key the source cards produce, with the first card that produced it as its example:
    // the curated fixtures (one per card type and shape the parity tests cover), then every card
    // in the demo dashboards and the cards nested in them.
    val sources =
      LibraryFixtures.all.map { Example("fixture:${it.name}", it.card, it.snapshot) } +
        DASHBOARDS.flatMap { name ->
          val loaded = requireNotNull(DashboardFixtures.load(name)) { "no bundled dashboard $name" }
          loaded.dashboard.allCards().map { Example("dashboard:$name", it, loaded.snapshot) }
        }
    val examples = LinkedHashMap<DesignKey, Example>()
    for (source in sources) {
      for (style in ThemeStyle.entries) for (dark in listOf(false, true)) {
        val key = CardDesignTemplates.keyFor(source.card, source.snapshot, style, dark) ?: continue
        examples.getOrPut(key) { source }
      }
    }
    assertTrue(examples.isNotEmpty(), "the demo dashboards produce designs")

    val built = examples.keys.map { CardDesignTemplates.capture(context, it) }
    val now = System.currentTimeMillis()
    val output = DesignPublisher.publish(built, serial = now, nowEpochMs = now)
    for ((path, bytes) in output.files) out
      .resolve(path)
      .also { it.parentFile.mkdirs() }
      .writeBytes(bytes)

    val entries = built.mapIndexed { i, design ->
      val example = examples.getValue(design.key)
      val id = idOf(design.key)
      val previews =
        DesignPreviews(
          example = DesignCatalog.previewPath(id, "example"),
          template = DesignCatalog.previewPath(id, "template"),
        )
      val playback =
        CardDesignTemplates.playback(
          CardDesignTemplates.designCard(example.card, example.snapshot),
          design.bytes,
        )
      render(
        out.resolve(previews.example!!),
        // Bound to the example card's values, as the app plays it.
        playback,
        card = example.card,
        snapshot = example.snapshot,
      )
      render(
        out.resolve(previews.template!!),
        // As published: nothing pushed, so the template's own placeholders show.
        CardTemplatePlayback(design.bytes, { NO_BINDINGS }, HaAction.None),
        card = null,
        snapshot = null,
      )
      DesignCatalogEntry(
        id = id,
        sha256 = output.manifest.designs[i].sha256,
        key = design.key,
        example =
          DesignExample(example.source, example.card.raw, exampleBindings(playback, example)),
        previews = previews,
      )
    }
    val catalog =
      DesignCatalog(serial = output.manifest.serial, generatedAtEpochMs = now, entries = entries)
    out
      .resolve(DesignCatalog.PATH)
      .writeText(json.encodeToString(DesignCatalog.serializer(), catalog))
    println("card designs: ${built.size} designs, ${output.files.size - 1} files in $out")
  }

  /** The values [playback] pushes for [example]: its preview's bindings, for the catalog. */
  private fun exampleBindings(playback: CardTemplatePlayback, example: Example) =
    playback.bindings(example.snapshot).let { b ->
      buildMap<String, JsonPrimitive> {
        b.strings.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        b.booleans.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        b.ints.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        // NaN (no numeric value) isn't JSON: leave it out.
        b.floats.forEach { (k, v) -> if (v.isFinite()) put(k, JsonPrimitive(v)) }
      }
    }

  private class Render(
    val template: CardTemplatePlayback,
    val card: CardConfig?,
    val snapshot: HaSnapshot?,
  )

  private var current by mutableStateOf<Render?>(null)
  private var contentSet = false

  /**
   * Renders [playback] at card width into [file], one fresh player per document. With [card] and
   * [snapshot] the card's bindings are pushed; without, the template shows its placeholders.
   */
  private fun render(
    file: File,
    playback: CardTemplatePlayback,
    card: CardConfig?,
    snapshot: HaSnapshot?,
  ) {
    if (!contentSet) {
      compose.setContent {
        val render = current ?: return@setContent
        Box(Modifier.width(CARD_WIDTH_DP.dp).testTag("card")) {
          key(render) {
            CachedCardPreview(
              cacheKey = render,
              profile = androidXExperimentalWrap,
              card = render.card,
              snapshot = render.snapshot,
              template = render.template,
            ) {}
          }
        }
      }
      contentSet = true
    }
    compose.runOnIdle { current = Render(playback, card, snapshot) }
    compose.waitForIdle()
    val image = compose.onNodeWithTag("card").captureToImage().asAndroidBitmap()
    file.parentFile.mkdirs()
    file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }

  companion object {
    /** The demo dashboards bundled in `:previews` (the in-app demo's data). */
    val DASHBOARDS =
      listOf("3d_printing", "climate", "energy", "github", "meshcore", "networks", "security")

    private const val CARD_WIDTH_DP = 360

    private val NO_BINDINGS = CardSnapshotBindings(emptyMap(), emptyMap(), emptyMap(), emptyMap())

    private val json = Json {
      prettyPrint = true
      encodeDefaults = true
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
  }
}
