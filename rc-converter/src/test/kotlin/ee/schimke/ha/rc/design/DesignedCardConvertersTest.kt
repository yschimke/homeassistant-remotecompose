package ee.schimke.ha.rc.design

import ee.schimke.ha.rc.cards.defaultRegistry
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Holds the checked-in card designs and the converters of migrated card types to each other
 * (server-owned-card-designs.md, "Migrating a card type"): a card type has a
 * `card-designs/ui-builder/designs/<cardType>.uid` exactly when its converter is a
 * [DesignedCardConverter], whose final `Render` draws it. A migrated type can't keep or regain a
 * layout of its own in its converter, and a design can't be checked in for a type that doesn't draw
 * it.
 */
class DesignedCardConvertersTest {
  private val registry = defaultRegistry()

  /** The card types with a checked-in design, relative to this module (the test's directory). */
  private val checkedIn: Set<String> =
    File("../card-designs/ui-builder/designs")
      .listFiles { file -> file.extension == "uid" }
      .orEmpty()
      .map { it.nameWithoutExtension }
      .toSet()

  @Test
  fun everyCheckedInDesignIsDrawnByItsTypesConverter() {
    assertTrue(checkedIn.isNotEmpty(), "card designs are checked in")
    val notDesigned = checkedIn.filter { registry.get(it) !is DesignedCardConverter }
    assertEquals(emptyList(), notDesigned.sorted(), "these types' converters don't draw their .uid")
  }

  @Test
  fun everyDesignedConverterHasItsDesignPackaged() {
    val designed = registry.supported().filter { registry.get(it) is DesignedCardConverter }
    assertEquals(checkedIn.sorted(), designed.sorted())
    for (cardType in designed) {
      val design = assertNotNull(CardDesigns.design(cardType), "$cardType.uid isn't packaged")
      assertEquals(emptyList(), design.validate(), cardType)
    }
  }
}
