package ee.schimke.ha.designs

import ee.schimke.ha.rc.CardDesignTemplates
import ee.schimke.ha.rc.components.ThemeStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every curated library fixture must key to a design: a fixture with no shape publishes nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryFixturesTest {

  @Test
  fun everyFixtureYieldsADesign() {
    val missing =
      LibraryFixtures.all
        .filter {
          CardDesignTemplates.keyFor(
            it.card,
            it.snapshot,
            ThemeStyle.entries.first(),
            dark = false,
          ) == null
        }
        .map { it.name }
    assertTrue(missing.isEmpty(), "fixtures yielding no design: $missing")
    println(
      "LibraryFixtures: ${LibraryFixtures.all.size} fixtures, card types " +
        LibraryFixtures.all.map { it.card.type }.distinct()
    )
  }

  @Test
  fun fixtureNamesAreUnique() {
    val names = LibraryFixtures.all.map { it.name }
    assertEquals(names.toSet().size, names.size, "duplicate fixture names: $names")
  }
}
