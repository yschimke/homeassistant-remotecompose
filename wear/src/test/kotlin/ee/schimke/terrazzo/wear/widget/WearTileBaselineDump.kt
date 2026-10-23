package ee.schimke.terrazzo.wear.widget

import androidx.glance.wear.core.ContainerInfo
import androidx.glance.wear.core.WearWidgetParams
import androidx.glance.wear.core.WidgetInstanceId
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.rc.UNAVAILABLE_DESIGN_PROPERTY
import ee.schimke.ha.rc.cards.TileCardConverter
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ThemeStyle
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Temporary: hashes of every Wear tile document before and after its layout moves into tile.uid.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WearTileBaselineDump {
  private val context = RuntimeEnvironment.getApplication()
  private val registry = defaultRegistry()
  private val converter = TileCardConverter()

  private fun sha(bytes: ByteArray) =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  @Test
  fun dump() {
    val out = File(requireNotNull(System.getProperty("tileBaselineOut")) { "no tileBaselineOut" })
    val lines = sortedMapOf<String, String>()
    val inputs =
      TileCardConverter.TEMPLATE_DOMAINS.flatMap { domain ->
        listOf(false, true).flatMap { unavailable ->
          listOf(false, true).mapNotNull { on ->
            if (on && domain == "sensor") return@mapNotNull null
            converter.wearDesignTemplate(
              DesignProperties.build {
                string(TileCardConverter.DOMAIN, domain)
                if (unavailable) string(UNAVAILABLE_DESIGN_PROPERTY, "1")
                if (on) string(WEAR_ON_PROPERTY, "1")
              }
            )
          }
        }
      }
    val params =
      listOf(
        WearWidgetParams(
          instanceId = WidgetInstanceId("w", 1),
          containerType = ContainerInfo.CONTAINER_TYPE_SMALL,
          widthDp = 200f,
          heightDp = 60f,
          horizontalPaddingDp = 8f,
          verticalPaddingDp = 8f,
          cornerRadiusDp = 26f,
        ),
        WearWidgetParams(
          instanceId = WidgetInstanceId("w", 1),
          containerType = ContainerInfo.CONTAINER_TYPE_LARGE,
          widthDp = 200f,
          heightDp = 112f,
          horizontalPaddingDp = 8f,
          verticalPaddingDp = 8f,
          cornerRadiusDp = 26f,
        ),
      )
    for ((i, input) in inputs.withIndex()) for (style in ThemeStyle.entries) for (cardStyle in
      HaUberTheme.all) {
      val colors = wearWidgetColors(style)
      val label =
        "$i ${input.card.raw} ${input.snapshot.states.values.map { it.state }} ${style.name} ${cardStyle.id}"
      val template = runBlocking {
        WearWidgetTemplate.capture(context) {
          SlotContent(registry, colors, input.card, input.snapshot, 0, true, cardStyle)
        }
      }
      lines["wear template $label"] = sha(template)
      if (cardStyle == HaUberTheme.Classic) {
        for (p in params) {
          val direct = runBlocking {
            slotDirectDocument(registry, colors, input.card, input.snapshot, 0)
              .captureRawContent(context, p, false)
              .rcDocument
          }
          lines["wear direct ${p.containerType} $label"] = sha(direct)
        }
      }
    }
    out.parentFile.mkdirs()
    out.writeText(lines.entries.joinToString("\n") { "${it.value} ${it.key}" } + "\n")
    println("wear tile baseline: ${lines.size} documents -> $out")
  }
}
