package ee.schimke.ha.designs

import androidx.compose.runtime.CompositionLocalProvider
import ee.schimke.ha.cards.LocalCardDesignInput
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.LauncherDesigns
import ee.schimke.ha.rc.LauncherLook
import ee.schimke.ha.rc.components.HaUberTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The other surfaces are rewrites of the card's design, not sources of their own
 * (server-owned-card-designs.md, "One source, every surface"): a launcher widget captured from the
 * card's `.uid` through the launcher harness is the launcher template its converter gives, byte for
 * byte, for every cell size the launcher grid offers and every widget theme and look.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardDesignSurfaceParityTest {

  private val context = RuntimeEnvironment.getApplication()
  private val densityDpi = context.resources.configuration.densityDpi

  @Test
  fun tileDesignIsTheLauncherTemplateForEveryCellAndTheme() {
    val design = requireNotNull(CheckedInCardDesigns.designFor(CardTypes.TILE)) { "no tile.uid" }
    val keys = launcherKeys(CardTypes.TILE)
    assertTrue(keys.isNotEmpty(), "the library's tiles have launcher keys")
    println("tile.uid: ${keys.size} launcher tile keys")

    val differing = keys.filterNot { key ->
      val converter = LauncherDesigns.capture(context, key, densityDpi)
      val fromDesign =
        LauncherDesigns.captureWith(context, key, densityDpi) { card, snapshot, modifier ->
          CompositionLocalProvider(
            LocalCardDesignInput provides DesignTemplateInput(card, snapshot)
          ) {
            design.Render(modifier)
          }
        }
      converter.bytes.contentEquals(fromDesign.bytes) && converter.actions == fromDesign.actions
    }
    assertEquals(emptyList(), differing.map { "${it.variant} ${it.shape} ${it.theme}" })
  }

  /**
   * Launcher keys for the library's [cardType] cards: every launcher grid cell size in the classic
   * look, and one common size in every other card style's look, each for both widget themes and
   * modes.
   */
  private fun launcherKeys(cardType: String): List<DesignKey> {
    val cards =
      GenerateCardDesignLibrary.librarySources().filter {
        ee.schimke.ha.rc.CardDesignTemplates.designCard(it.card, it.snapshot).type == cardType
      }
    val classicCells = (1..4).flatMap { columns -> (1..3).map { rows -> cell(columns, rows) } }
    val looks = HaUberTheme.all.map { LauncherLook(cardStyle = it) }
    return buildList {
      for (source in cards) for (dark in listOf(false, true)) {
        for (systemTheme in listOf(false, true)) {
          for ((w, h) in classicCells) {
            LauncherDesigns.keyFor(source.card, source.snapshot, w, h, dark, systemTheme)
              ?.let(::add)
          }
          val (w, h) = cell(2, 1)
          for (look in looks) {
            LauncherDesigns.keyFor(
                source.card,
                source.snapshot,
                w,
                h,
                dark,
                systemTheme,
                look = look,
              )
              ?.let(::add)
          }
        }
      }
    }
      .distinct()
  }

  /** A launcher grid cell of [columns] × [rows], in dp: `(73n − 16) × (118m − 16)`. */
  private fun cell(columns: Int, rows: Int) = (73 * columns - 16) to (118 * rows - 16)
}
