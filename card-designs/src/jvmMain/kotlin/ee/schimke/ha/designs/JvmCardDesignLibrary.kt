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
 *
 * The previews are shared out among one worker process per processor (`--previews <staging> <shard>
 * <shards>`, this same main), as Compose scenes can only draw on one thread of a process.
 */
fun main(args: Array<String>) {
  if (args.size == 4 && args[0] == PREVIEWS_ARG) {
    CardDesignLibrary.drawPreviews(
      File(args[1]),
      JvmCardPreviewRenderer(),
      shard = args[2].toInt(),
      shards = args[3].toInt(),
    )
    // The Compose scenes leave non-daemon threads behind.
    exitProcess(0)
  }
  val target =
    File(
      args.singleOrNull()
        ?: run {
          System.err.println("usage: JvmCardDesignLibrary <output directory>")
          exitProcess(2)
        }
    )
  val previews = JvmCardPreviewRenderer()
  val shards = Runtime.getRuntime().availableProcessors()
  val count =
    CardDesignLibrary.generate(
      target,
      CommonCaptureHost(LibraryCaptureProfile.OPERATIONS),
      previews,
    ) { staging ->
      if (shards == 1) CardDesignLibrary.drawPreviews(staging, previews)
      else drawPreviewsInWorkers(staging, shards)
    }
  println("card designs: $count designs in $target")
  // The Compose scenes leave non-daemon threads behind.
  exitProcess(0)
}

/** Draws [staging]'s previews in [shards] processes running this main, one shard each. */
private fun drawPreviewsInWorkers(staging: File, shards: Int) {
  val java = File(System.getProperty("java.home"), "bin/java").path
  val workers =
    (0 until shards).map { shard ->
      ProcessBuilder(
          java,
          "-Xmx$WORKER_HEAP",
          "-Dfile.encoding=UTF-8",
          "-cp",
          System.getProperty("java.class.path"),
          "ee.schimke.ha.designs.JvmCardDesignLibraryKt",
          PREVIEWS_ARG,
          staging.absolutePath,
          "$shard",
          "$shards",
        )
        .inheritIO()
        .start()
    }
  val failed = workers.withIndex().filter { (_, worker) -> worker.waitFor() != 0 }
  check(failed.isEmpty()) {
    "preview workers ${failed.map { it.index }} of $shards failed (exit ${failed.map { it.value.exitValue() }})"
  }
}

private const val PREVIEWS_ARG = "--previews"
private const val WORKER_HEAP = "1g"

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
