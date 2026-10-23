package ee.schimke.ha.designs

import ee.schimke.ha.client.design.Sha256
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.rc.CardDesignTemplates
import ee.schimke.ha.rc.LauncherDesigns
import ee.schimke.ha.rc.LauncherLook
import ee.schimke.ha.rc.captureCardDocument
import ee.schimke.ha.rc.captureLauncherWidgetDocument
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.widgetsProfile
import java.io.File
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Temporary: hashes of every tile document before and after its layout moves into tile.uid. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TileBaselineDump {
  private val context = RuntimeEnvironment.getApplication()
  private val densityDpi = context.resources.configuration.densityDpi

  @Test
  fun dump() {
    val out = File(requireNotNull(System.getProperty("tileBaselineOut")) { "no tileBaselineOut" })
    val registry = defaultRegistry()
    val tiles =
      GenerateCardDesignLibrary.librarySources().filter {
        CardDesignTemplates.designCard(it.card, it.snapshot).type == CardTypes.TILE ||
          it.card.type == CardTypes.TILE
      }
    val lines = sortedMapOf<String, String>()
    fun put(name: String, bytes: ByteArray) {
      lines[name] = Sha256.hex(bytes)
    }
    // Templates on the dashboard, for every library tile key.
    val keys =
      GenerateCardDesignLibrary.librarySources()
        .flatMap { GenerateCardDesignLibrary.keysOf(it.card, it.snapshot) }
        .distinct()
        .filter { it.cardType == CardTypes.TILE }
    for (key in keys) put(
      "template ${key.shape} ${key.theme}",
      CardDesignTemplates.capture(context, key).bytes,
    )
    // Launcher templates: every cell, both widget themes and modes, every look at 2x1.
    val cells = (1..4).flatMap { c -> (1..3).map { r -> (73 * c - 16) to (118 * r - 16) } }
    for (source in tiles) for (dark in listOf(false, true)) for (system in listOf(false, true)) {
      for ((w, h) in cells) {
        LauncherDesigns.keyFor(source.card, source.snapshot, w, h, dark, system)?.let {
          put(
            "launcher ${it.variant} ${it.shape} ${it.theme}",
            LauncherDesigns.capture(context, it, densityDpi).bytes,
          )
        }
      }
      for (look in HaUberTheme.all.map { LauncherLook(cardStyle = it) }) {
        LauncherDesigns.keyFor(source.card, source.snapshot, 130, 102, dark, system, look = look)
          ?.let {
            put(
              "launcher ${it.variant} ${it.shape} ${it.theme}",
              LauncherDesigns.capture(context, it, densityDpi).bytes,
            )
          }
      }
    }
    // Direct captures of every source tile, templatable or not (features, colours, taps...).
    tiles.filter { it.card.type == CardTypes.TILE }.forEachIndexed { i, source ->
      val label = "${source.source}#$i ${source.card.raw}"
      val dashboard = runBlocking {
        captureCardDocument(
          context,
          720,
          400,
          densityDpi,
          registry,
          source.card,
          source.snapshot,
          forceRefresh = true,
        )
      }
      put("direct dashboard $label", dashboard.bytes)
      for ((w, h) in cells) {
        val px = { dp: Int -> (dp * densityDpi / 160f).toInt() }
        val launcher =
          captureLauncherWidgetDocument(
            context,
            source.card,
            source.snapshot,
            registry,
            px(w),
            px(h),
            densityDpi,
            widgetsProfile,
            dark = false,
            fetchImage = { null },
          )
        put("direct launcher ${w}x$h $label", launcher.bytes)
      }
    }
    out.parentFile.mkdirs()
    out.writeText(lines.entries.joinToString("\n") { "${it.value} ${it.key}" } + "\n")
    println("tile baseline: ${lines.size} documents -> $out")
  }
}
