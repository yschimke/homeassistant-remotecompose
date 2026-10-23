package ee.schimke.ha.designs

import androidx.compose.runtime.CompositionLocalProvider
import ee.schimke.ha.cards.CardDesignDocument
import ee.schimke.ha.cards.LocalCardDesignInput
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.rc.CardDesignTemplates
import ee.schimke.ha.rc.DesignTemplateInput
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
 * A card type migrates to a UI Builder card design only once its design draws what its converter
 * draws (server-owned-card-designs.md, "Migrating a card type"): for every library key of the type,
 * the design captured through the same harness gives the same bytes as the converter's template.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardDesignDocumentParityTest {

  private val context = RuntimeEnvironment.getApplication()

  @Test
  fun tileDesignDrawsEveryTileKeyAsTheConverterDoes() {
    val design = designOf("tile")
    val keys = libraryKeys(CardTypes.TILE)
    assertTrue(keys.isNotEmpty(), "the library has tile keys")
    println("tile.uid: ${keys.size} library tile keys")

    val differing = keys.filterNot { key ->
      val converter = CardDesignTemplates.capture(context, key)
      val fromDesign =
        CardDesignTemplates.captureWith(context, key) { card, snapshot, modifier ->
          CompositionLocalProvider(
            LocalCardDesignInput provides DesignTemplateInput(card, snapshot)
          ) {
            design.Render(modifier)
          }
        }
      converter.bytes.contentEquals(fromDesign.bytes)
    }
    assertEquals(emptyList(), differing.map { "${it.shape} ${it.theme}" })
  }

  @Test
  fun everyCheckedInDesignCanBeDrawnHere() {
    val problems =
      DESIGNS.listFiles { file -> file.extension == "uid" }!!.flatMap { file ->
          CardDesignDocument.parse(file.readText()).validate().map { "${file.name}: $it" }
        }
    assertEquals(emptyList(), problems)
  }

  private fun designOf(name: String) =
    CardDesignDocument.parse(DESIGNS.resolve("$name.uid").readText())

  private fun libraryKeys(cardType: String): List<DesignKey> =
    GenerateCardDesignLibrary.librarySources()
      .flatMap { GenerateCardDesignLibrary.keysOf(it.card, it.snapshot) }
      .distinct()
      .filter { it.cardType == cardType }

  private companion object {
    /** The checked-in card designs, relative to the module (Gradle's test working directory). */
    val DESIGNS = File("ui-builder/designs")
  }
}
