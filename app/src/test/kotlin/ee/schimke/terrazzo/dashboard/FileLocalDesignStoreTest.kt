package ee.schimke.terrazzo.dashboard

import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignSurface
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ThemeStyle
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The local library's templates on disk, and where cards got their designs. */
class FileLocalDesignStoreTest {
  private val key =
    DesignKey(
      cardType = "tile",
      surface = DesignSurface.MobileApp,
      rcApiLevel = 8,
      shape = "domain=light",
      theme = DesignKey.bakedTheme("Material3", dark = false),
    )
  private val bytes = "captured rc document".encodeToByteArray()

  @Test
  fun keepsATemplateForItsScopeOnly() {
    val root = Files.createTempDirectory("designs").toFile()
    var scope = "build1-320-1.0"
    val store = FileLocalDesignStore(root) { scope }
    assertNull(store.read(key))
    store.write(key, bytes)
    assertContentEquals(bytes, store.read(key))
    assertNull(store.read(key.copy(shape = "domain=switch")))

    // A new build, or a larger font: earlier captures don't apply, and pruning drops them.
    scope = "build2-320-1.0"
    assertNull(store.read(key))
    store.write(key, bytes)
    store.prune()
    assertEquals(listOf("build2-320-1.0"), root.list()?.toList())
    assertContentEquals(bytes, store.read(key))
  }

  @Test
  fun sourcesCountServerDesignsAndListTheGapsForOneChoice() {
    val choice = CardDesignChoice("https://designs.test", ThemeStyle.Material3, HaUberTheme.Classic)
    val gauge = key.copy(cardType = "gauge", shape = "domain=sensor")
    CardDesignSources.record(choice, key, fromServer = true)
    CardDesignSources.record(choice, gauge, fromServer = false)
    assertEquals(CardDesignSources.Summary(1, listOf(gauge)), CardDesignSources.summary(choice))

    // Another server (or variant, or author) starts afresh, and the old record no longer applies.
    val other = choice.copy(serverUrl = "https://other.test")
    assertEquals(CardDesignSources.Summary(0, emptyList()), CardDesignSources.summary(other))
    CardDesignSources.record(other, key, fromServer = true)
    assertEquals(CardDesignSources.Summary(1, emptyList()), CardDesignSources.summary(other))
    assertEquals(CardDesignSources.Summary(0, emptyList()), CardDesignSources.summary(choice))
  }
}
