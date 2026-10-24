package ee.schimke.ha.rc

import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.design.DesignSurface
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.ThemeStyle
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Design templates are captured under [TemplateHaClock], so a template that reads the time (the
 * clock's date line) has the same bytes on the design server, in the app's shipped assets and on a
 * device, whatever day each captured it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TemplateClockTest {
  private val context = RuntimeEnvironment.getApplication()
  private val registry = defaultRegistry()

  /** A clock with its date line, the template that reads the time. */
  private val clockKey =
    DesignKey(
      cardType = CardTypes.CLOCK,
      surface = DesignSurface.MobileApp,
      rcApiLevel = CardDesignTemplates.MOBILE_APP_API_LEVEL,
      shape =
        DesignProperties.build {
            enum("has_title", 0)
            enum("seconds", 0)
            enum("h24", 1)
            enum("large", 0)
            enum("zoned", 0)
            enum("date", 1)
          }
          .shape,
      theme = DesignKey.bakedTheme(ThemeStyle.TerrazzoHome.name, false),
    )

  @Test
  fun dashboardTemplatesAreCapturedUnderTheTemplateClock() {
    var seen: HaClock? = null
    CardDesignTemplates.captureWith(context, clockKey, registry) { _, _, _ ->
      seen = LocalHaClock.current
    }
    assertSame(TemplateHaClock, seen)
  }

  @Test
  fun launcherTemplatesAreCapturedUnderTheTemplateClock() {
    val card =
      CardConfig(
        CardTypes.CLOCK,
        buildJsonObject {
          put("type", CardTypes.CLOCK)
          put("display", "primary")
        },
      )
    val key = LauncherDesigns.keyFor(card, HaSnapshot(), 130, 102, false, false, registry)!!
    var seen: HaClock? = null
    LauncherDesigns.captureWith(context, key, 320, registry) { _, _, _ ->
      seen = LocalHaClock.current
    }
    assertSame(TemplateHaClock, seen)
  }

  @Test
  fun aClockTemplateCarriesTheTemplateClocksDateNotToday() {
    val text =
      String(CardDesignTemplates.capture(context, clockKey, registry).bytes, Charsets.UTF_8)
    val label = DateTimeFormatter.ofPattern("EEE d MMM")
    val pinned = TemplateHaClock.zonedNow().format(label)
    assertTrue(pinned in text, "the date line's default is the template clock's day, $pinned")
    val today = SystemHaClock.zonedNow().format(label)
    if (today != pinned) assertFalse(today in text, "today's date, $today, leaked into a template")
  }
}
