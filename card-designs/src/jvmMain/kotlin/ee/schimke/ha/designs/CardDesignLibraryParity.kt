package ee.schimke.ha.designs

import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignManifest
import ee.schimke.ha.rc.CardSnapshotBindings
import ee.schimke.ha.rc.CardTemplatePlayback
import ee.schimke.ha.rc.components.HaAction
import java.io.File
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image

/**
 * Whether two card design libraries draw the same: the Android one (`generateCardDesigns`) and the
 * JVM one (`generateCardDesignsJvm`). Their bytes differ (the two creation libraries order and
 * number a document's parts differently), so each key's template is drawn by the one player, the
 * Compose Multiplatform one, and the pixels compared. Exits non-zero when any key is missing from
 * either library or draws differently; with a third argument, writes both drawings of each
 * difference there.
 *
 * `./gradlew :card-designs:checkCardDesignParity`.
 */
fun main(args: Array<String>) {
  if (args.size !in 2..3) {
    System.err.println("usage: CardDesignLibraryParity <android library> <jvm library> [diff dir]")
    exitProcess(2)
  }
  val report =
    CardDesignLibraryParity.compare(File(args[0]), File(args[1]), args.getOrNull(2)?.let(::File))
  println(report.summary())
  report.different.forEach { println("  differs: $it") }
  report.missing.forEach { println("  missing: $it") }
  exitProcess(if (report.matches) 0 else 1)
}

object CardDesignLibraryParity {
  class Report(val compared: Int, val different: List<String>, val missing: List<String>) {
    val matches: Boolean
      get() = different.isEmpty() && missing.isEmpty()

    fun summary(): String =
      "card design parity: ${compared - different.size} of $compared keys draw the same, " +
        "${different.size} differ, ${missing.size} in only one library"
  }

  fun compare(android: File, jvm: File, diffs: File? = null): Report {
    val a = blobs(android)
    val j = blobs(jvm)
    val renderer = JvmCardPreviewRenderer()
    val different = mutableListOf<String>()
    val shared = a.keys.filter { it in j }
    for (key in shared) {
      val pa = renderer.render(playback(android, a.getValue(key)), null, null)
      val pj = renderer.render(playback(jvm, j.getValue(key)), null, null)
      if (!samePixels(pa, pj)) {
        val id = CardDesignLibrary.idOf(key)
        different += "$id ${key.cardType} ${key.shape} ${key.theme}"
        diffs?.let {
          it.mkdirs()
          it.resolve("$id-android.png").writeBytes(pa)
          it.resolve("$id-jvm.png").writeBytes(pj)
        }
      }
    }
    val missing =
      (a.keys - j.keys).map { "android only: $it" } + (j.keys - a.keys).map { "jvm only: $it" }
    return Report(shared.size, different, missing)
  }

  private fun blobs(library: File): Map<DesignKey, String> =
    json
      .decodeFromString(DesignManifest.serializer(), library.resolve("manifest.json").readText())
      .designs
      .associate { it.key to it.sha256 }

  private fun playback(library: File, sha256: String) =
    CardTemplatePlayback(
      library.resolve(DesignManifest.blobPath(sha256)).readBytes(),
      { _, _ -> NO_BINDINGS },
      HaAction.None,
    )

  private fun samePixels(a: ByteArray, b: ByteArray): Boolean {
    if (a.contentEquals(b)) return true
    val x = Bitmap.makeFromImage(Image.makeFromEncoded(a))
    val y = Bitmap.makeFromImage(Image.makeFromEncoded(b))
    if (x.width != y.width || x.height != y.height) return false
    return requireNotNull(x.readPixels()).contentEquals(y.readPixels())
  }

  private val NO_BINDINGS = CardSnapshotBindings(emptyMap(), emptyMap(), emptyMap(), emptyMap())

  private val json = Json { ignoreUnknownKeys = true }
}
