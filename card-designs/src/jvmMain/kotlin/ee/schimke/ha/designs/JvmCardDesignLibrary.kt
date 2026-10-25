package ee.schimke.ha.designs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import ee.schimke.composeai.rcplayer.compose.RcComposePlayer
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardSnapshotBindings
import ee.schimke.ha.rc.CardTemplatePlayback
import ee.schimke.ha.rc.CommonCaptureHost
import ee.schimke.ha.rc.SystemHaClock
import java.io.File
import kotlin.system.exitProcess
import org.jetbrains.skia.EncodedImageFormat

/**
 * Generates the card design library on the JVM, with no Android: captured with the Compose
 * Multiplatform creation fork under the app's operations ([LibraryCaptureProfile]), its previews
 * drawn by the Compose Multiplatform player. `./gradlew :card-designs:generateCardDesigns`.
 */
fun main(args: Array<String>) {
  val target =
    File(
      args.singleOrNull()
        ?: run {
          System.err.println("usage: JvmCardDesignLibrary <output directory>")
          exitProcess(2)
        }
    )
  val count =
    CardDesignLibrary.generate(
      target,
      CommonCaptureHost(LibraryCaptureProfile.OPERATIONS),
      JvmCardPreviewRenderer(),
    )
  println("card designs: $count designs in $target")
  // The Compose scenes leave non-daemon threads behind.
  exitProcess(0)
}

/** Previews drawn by the Compose Multiplatform player, as the web app plays a design. */
class JvmCardPreviewRenderer(private val density: Float = 2f) : CardPreviewRenderer {
  override fun render(
    playback: CardTemplatePlayback,
    card: CardConfig?,
    snapshot: HaSnapshot?,
  ): ByteArray {
    val values =
      if (snapshot != null) namedValues(playback.bindings(snapshot, SystemHaClock)) else emptyMap()
    val width = (CardPreviewRenderer.CARD_WIDTH_DP * density).toInt()
    // Laid out once at the tallest preview allowed to learn the card's own height, then drawn at
    // exactly that.
    var height = 0
    scene(width, MAX_HEIGHT_PX) {
        Box(Modifier.onSizeChanged { height = it.height }) { Card(playback.bytes, values) }
      }
      .use { it.render() }
    val image =
      scene(width, height.coerceIn(1, MAX_HEIGHT_PX)) { Card(playback.bytes, values) }
        .use { scene ->
          scene.render()
          // Past any entrance animation.
          scene.render(SETTLED_NANOS)
        }
    return requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes
  }

  private fun scene(width: Int, height: Int, content: @Composable () -> Unit) =
    ImageComposeScene(width, height, Density(density), content = content)

  @Composable
  private fun Card(bytes: ByteArray, values: Map<String, RcNamedValue>) {
    Box(Modifier.width(CardPreviewRenderer.CARD_WIDTH_DP.dp)) {
      RcComposePlayer(
        bytes,
        namedValues = mutableStateMapOf<String, RcNamedValue>().apply { putAll(values) },
      )
    }
  }

  private fun namedValues(bindings: CardSnapshotBindings): Map<String, RcNamedValue> = buildMap {
    bindings.strings.forEach { (k, v) -> put(k, RcNamedValue.Text(v)) }
    bindings.booleans.forEach { (k, v) -> put(k, RcNamedValue.Integer(if (v) 1 else 0)) }
    bindings.ints.forEach { (k, v) -> put(k, RcNamedValue.Integer(v)) }
    bindings.floats.forEach { (k, v) -> put(k, RcNamedValue.FloatValue(v)) }
  }

  private companion object {
    /** No generated image exceeds 1800 px in either dimension (AGENTS.md); a taller card is cut. */
    const val MAX_HEIGHT_PX = 1800
    const val SETTLED_NANOS = 2_000_000_000L
  }
}
