package ee.schimke.terrazzo.widget

import android.content.Intent
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.WidgetOpenApp
import ee.schimke.terrazzo.core.widget.WidgetStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A widget's taps open the app on their target, once: its more-info and navigate targets, and the
 * card it was made from on its dashboard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetOpenRequestsTest {
  private val context = RuntimeEnvironment.getApplication()

  private fun launchIntentFor(action: HaAction): Intent? {
    // Robolectric's package manager knows no launcher activity unless one is registered.
    shadowOf(context.packageManager)
      .addIntentFilterForActivity(
        android.content.ComponentName(context, "ee.schimke.terrazzo.MainActivity"),
        android.content.IntentFilter(Intent.ACTION_MAIN).apply {
          addCategory(Intent.CATEGORY_LAUNCHER)
        },
      )
    return WidgetOpenRequests.launchIntent(context, action)
  }

  @Test
  fun widgetTapsTargetMainActivityNotALauncherAlias() {
    // A widget's PendingIntent outlives a launcher-icon switch, which disables the alias the
    // package's launch intent would have named.
    val intent = WidgetOpenRequests.launchIntent(context, HaAction.MoreInfo("light.kitchen"))
    assertEquals("ee.schimke.terrazzo.MainActivity", intent?.component?.className)
  }

  @Test
  fun aMoreInfoTapOpensTheEntity() {
    val intent = launchIntentFor(HaAction.MoreInfo("light.kitchen"))
    assertEquals(
      WidgetOpenRequests.Target.MoreInfo("light.kitchen"),
      WidgetOpenRequests.targetOf(intent),
    )
  }

  @Test
  fun aNavigateTapOpensTheDashboardPath() {
    val intent = launchIntentFor(HaAction.Navigate("/lovelace/kitchen"))
    assertEquals(
      WidgetOpenRequests.Target.Navigate("/lovelace/kitchen"),
      WidgetOpenRequests.targetOf(intent),
    )
  }

  @Test
  fun anOtherTapOrAnOutsidePathOpensNothing() {
    assertNull(WidgetOpenRequests.launchIntent(context, HaAction.Toggle("light.kitchen")))
    assertNull(
      WidgetOpenRequests.targetOf(
        Intent().putExtra(WidgetOpenRequests.EXTRA_NAVIGATE, "https://example.com")
      )
    )
    assertNull(WidgetOpenRequests.targetOf(null))
  }

  @Test
  fun aTargetIsOpenedOnceAndANewerTapWins() {
    val first = WidgetOpenRequests.Target.MoreInfo("light.kitchen")
    val second = WidgetOpenRequests.Target.MoreInfo("light.office")
    WidgetOpenRequests.offer(Intent().putExtra(WidgetOpenRequests.EXTRA_MORE_INFO, "light.kitchen"))
    assertEquals(first, WidgetOpenRequests.requests.value)
    WidgetOpenRequests.offer(Intent().putExtra(WidgetOpenRequests.EXTRA_MORE_INFO, "light.office"))
    // The first, opened late, doesn't clear the newer tap.
    WidgetOpenRequests.consume(first)
    assertEquals(second, WidgetOpenRequests.requests.value)
    WidgetOpenRequests.consume(second)
    assertNull(WidgetOpenRequests.requests.value)
  }

  private val tile =
    CardConfig("tile", JsonObject(mapOf("entity" to JsonPrimitive("light.kitchen"))))
  private val entry =
    WidgetStore.Entry(
      widgetId = 7,
      baseUrl = "https://ha.example",
      card = tile,
      origin = WidgetStore.Origin("kitchen", tile),
    )
  private val cardRef = WidgetOpenRequests.CardRef("https://ha.example", "kitchen", tile)
  private val entries: (Int) -> WidgetStore.Entry? = { id -> entry.takeIf { id == it.widgetId } }

  @Test
  fun aTapOnTheWidgetShowsItsCard() {
    val intent = WidgetOpenRequests.launchIntent(context, WidgetOpenApp, widgetId = 7)
    assertEquals(
      WidgetOpenRequests.Target.ShowCard(cardRef),
      WidgetOpenRequests.targetOf(intent, entries),
    )
  }

  @Test
  fun aMoreInfoTapOpensTheEntityOverTheWidgetsCard() {
    val intent =
      WidgetOpenRequests.launchIntent(context, HaAction.MoreInfo("light.kitchen"), widgetId = 7)
    assertEquals(
      WidgetOpenRequests.Target.MoreInfo("light.kitchen", cardRef),
      WidgetOpenRequests.targetOf(intent, entries),
    )
  }

  @Test
  fun aWidgetWithNoOriginOpensTheAppAsBefore() {
    val noOrigin: (Int) -> WidgetStore.Entry? = { entry.copy(origin = null) }
    assertNull(
      WidgetOpenRequests.targetOf(
        WidgetOpenRequests.launchIntent(context, WidgetOpenApp, widgetId = 7),
        noOrigin,
      )
    )
    assertEquals(
      WidgetOpenRequests.Target.MoreInfo("light.kitchen"),
      WidgetOpenRequests.targetOf(
        WidgetOpenRequests.launchIntent(context, HaAction.MoreInfo("light.kitchen"), widgetId = 7),
        noOrigin,
      ),
    )
  }

  @Test
  fun aCardTheAppCantShowIsDroppedLeavingTheRest() {
    val moreInfo = WidgetOpenRequests.Target.MoreInfo("light.kitchen", cardRef)
    WidgetOpenRequests.offer(
      WidgetOpenRequests.launchIntent(context, HaAction.MoreInfo("light.kitchen"), widgetId = 7),
      entries,
    )
    assertEquals(moreInfo, WidgetOpenRequests.requests.value)
    WidgetOpenRequests.dropCard(moreInfo)
    assertEquals(
      WidgetOpenRequests.Target.MoreInfo("light.kitchen"),
      WidgetOpenRequests.requests.value,
    )
    WidgetOpenRequests.consume(WidgetOpenRequests.requests.value!!)

    val showCard = WidgetOpenRequests.Target.ShowCard(cardRef)
    WidgetOpenRequests.offer(WidgetOpenRequests.launchIntent(context, WidgetOpenApp, 7), entries)
    WidgetOpenRequests.dropCard(showCard)
    assertNull(WidgetOpenRequests.requests.value)
  }
}
