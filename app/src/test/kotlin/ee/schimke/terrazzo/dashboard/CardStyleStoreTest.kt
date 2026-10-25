package ee.schimke.terrazzo.dashboard

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class CardStyleStoreTest {
  private val dir: File = createTempDirectory("card-styles").toFile()
  private val file = File(dir, "card-styles.json")

  @AfterTest fun cleanUp() = dir.deleteRecursively().let {}

  @Test
  fun classicAndHomeAssistantComeWithTheApp() {
    val installed = CardStyleStore(file).installed.value
    assertEquals(emptySet(), installed)
    assertTrue(CardStyles.isInstalled("classic", installed))
    assertTrue(CardStyles.isInstalled("home-assistant", installed))
    assertFalse(CardStyles.isInstalled("console", installed))
  }

  @Test
  fun anInstalledStyleIsKeptAcrossRestartsUntilRemoved() = runBlocking {
    CardStyleStore(file).add("console")
    assertEquals(setOf("console"), CardStyleStore(file).installed.value)
    CardStyleStore(file).remove("console")
    assertEquals(emptySet(), CardStyleStore(file).installed.value)
  }

  @Test
  fun anUpdatedInstallKeepsTheStylesItDrawsWith() = runBlocking {
    // No file yet: an install from before styles were installable.
    CardStyleStore(file).seed(setOf("classic", "lcd", "braun"))
    assertEquals(setOf("lcd", "braun"), CardStyleStore(file).installed.value)
  }

  @Test
  fun seedingOnlyHappensOnce() = runBlocking {
    val store = CardStyleStore(file)
    store.seed(setOf("lcd"))
    store.remove("lcd")
    // A later start, now drawing in Nixie, doesn't install anything by seeding again.
    CardStyleStore(file).seed(setOf("nixie"))
    assertEquals(emptySet(), CardStyleStore(file).installed.value)
  }

  @Test
  fun aNewInstallStartsWithTheBuiltInStylesOnly() = runBlocking {
    CardStyleStore(file).seed(setOf("classic"))
    assertEquals(emptySet(), CardStyleStore(file).installed.value)
    assertTrue(file.exists())
  }

  @Test
  fun aStyleInUseIsTheDashboardsOrACards() {
    assertEquals(setOf("lcd", "nixie"), CardStyles.inUse("lcd", setOf("nixie")))
  }
}
