package ee.schimke.terrazzo.widget

import android.content.Intent
import ee.schimke.ha.rc.components.HaAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A widget's more-info and navigate taps open the app on their target, once. */
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
}
