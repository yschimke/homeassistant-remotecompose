package ee.schimke.ha.designs

import ee.schimke.ha.client.design.DesignPublisher
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardDesignTemplates
import ee.schimke.ha.rc.components.ThemeStyle
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The designs the app ships in its assets (`app/src/main/assets/card-designs`, read by
 * `ShippedDesignLibrary`): the library's default style, the app's default theme style in light and
 * dark with the classic card style, captured from the checked-in designs exactly as the library is.
 * A card the design server has no design for plays these rather than being captured on the device.
 *
 * Fails when the checked-in assets aren't what this build captures (a card design or a converter
 * changed): run [UPDATE] to write them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShippedCardDesignsTest {

  @Test
  fun theAppShipsTheLibrarysDefaultStyle() {
    val context = RuntimeEnvironment.getApplication()
    val keys =
      GenerateCardDesignLibrary.librarySources()
        .flatMap { shippedKeysOf(it.card, it.snapshot) }
        .distinct()
    assertTrue(keys.isNotEmpty(), "the library's source cards produce designs")
    val built = keys.map { CheckedInCardDesigns.capture(context, it) }
    // Fixed serial and time, so the same designs always write the same manifest.
    val files = DesignPublisher.publish(built, serial = 1, nowEpochMs = 0).files
    val dir = File(requireNotNull(System.getProperty("shippedCardDesignsDir")))

    if (System.getProperty("updateShippedCardDesigns") == "true") {
      dir.deleteRecursively()
      for ((path, bytes) in files) dir
        .resolve(path)
        .also { it.parentFile.mkdirs() }
        .writeBytes(bytes)
      println("shipped card designs: ${built.size} designs in $dir")
      return
    }

    val shipped =
      dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).path to it.readBytes() }
    assertEquals(files.keys.sorted(), shipped.keys.sorted(), STALE)
    for ((path, bytes) in files) {
      assertTrue(bytes.contentEquals(shipped.getValue(path)), "$path differs. $STALE")
    }
  }

  companion object {
    /** The app's default theme style: what a fresh install draws its cards in. */
    private val DEFAULT_STYLE = ThemeStyle.TerrazzoHome

    /** The shipped keys [card] produces: the default style, light and dark, classic card style. */
    fun shippedKeysOf(card: CardConfig, snapshot: HaSnapshot): List<DesignKey> =
      listOf(false, true).mapNotNull { dark ->
        CardDesignTemplates.keyFor(card, snapshot, DEFAULT_STYLE, dark)
      }

    private const val UPDATE =
      "./gradlew :card-designs:testDebugUnitTest --tests '*ShippedCardDesignsTest*' " +
        "-PupdateShippedCardDesigns"

    private const val STALE = "The app's shipped card designs are out of date: run $UPDATE"
  }
}
