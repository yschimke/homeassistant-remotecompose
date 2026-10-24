package ee.schimke.ha.rc

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.design.DesignSurface
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.rc.cards.TileCardConverter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A card design is captured once (on the design server, at whatever density its build runs) and
 * played on every phone. Its sizes must come from the player's display, not the capture's: the same
 * key captures to the same bytes at any density and font scale, and the device folds its own
 * display in once ([CardDesignTemplates.forDisplay]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CardDesignDensityTest {

  private val context: Context
    get() = ApplicationProvider.getApplicationContext()

  private val tile =
    DesignKey(
      cardType = CardTypes.TILE,
      surface = DesignSurface.MobileApp,
      rcApiLevel = CardDesignTemplates.MOBILE_APP_API_LEVEL,
      shape = DesignProperties.build { string(TileCardConverter.DOMAIN, "light") }.shape,
      theme = DesignKey.bakedTheme("Material3", true),
    )

  /** The MeshCore Contacts grid: three tiles, `columns: 3`, whose per-row count is a width gate. */
  private val grid =
    DesignKey(
      cardType = CardTypes.GRID,
      surface = DesignSurface.MobileApp,
      rcApiLevel = CardDesignTemplates.MOBILE_APP_API_LEVEL,
      shape =
        listOf(0, 1, 2).joinToString(";", postfix = ";columns=3;count=3;square=0") {
          "c$it.domain=binary_sensor;c$it.type=tile"
        },
      theme = DesignKey.bakedTheme("Material3", true),
    )

  private fun captureAt(qualifiers: String, key: DesignKey): ByteArray {
    RuntimeEnvironment.setQualifiers(qualifiers)
    return CardDesignTemplates.capture(context, key).bytes
  }

  @Test
  fun aTemplateIsTheSameAtEveryDensity() {
    for (key in listOf(tile, grid)) {
      assertContentEquals(
        captureAt("xhdpi", key),
        captureAt("xxhdpi", key),
        "${key.cardType} captures the same at xhdpi and xxhdpi",
      )
    }
  }

  @Test
  fun aTemplateCapturesTheSameEveryTime() {
    val first = captureAt("xhdpi", grid)
    assertContentEquals(first, captureAt("xhdpi", grid), "the grid captures reproducibly")
    // Reproducible, but still one expression per call site: the grid's width gate, and the grid
    // width each of its two tiers lays its cells out by.
    val names =
      Regex("__terrazzo_(breakpoint|grid_width)_[0-9a-f]+")
        .findAll(String(first, Charsets.ISO_8859_1))
        .map { it.value }
        .toSet()
    assertEquals(3, names.size, "each call site names its own expression: $names")
  }

  @Test
  fun aTemplateIsTheSameAtEveryFontScale() {
    val plain = captureAt("xhdpi", tile)
    RuntimeEnvironment.setFontScale(1.3f)
    try {
      assertContentEquals(plain, CardDesignTemplates.capture(context, tile).bytes)
    } finally {
      RuntimeEnvironment.setFontScale(1f)
    }
  }

  @Test
  fun theDeviceFoldsItsOwnDisplayIn() {
    val template = captureAt("xhdpi", grid)
    val large = CardDesignTemplates.forDisplay(template, density = 3f, fontScale = 1.3f)
    val xxhdpi = CardDesignTemplates.forDisplay(template, density = 3f, fontScale = 1f)
    assertFalse(xxhdpi.contentEquals(template), "the host density is folded in")
    assertFalse(xxhdpi.contentEquals(large), "and so is the font scale")
    // Folded once: the same bytes come back for the same display.
    kotlin.test.assertSame(xxhdpi, CardDesignTemplates.forDisplay(template, 3f, 1f))
  }
}
