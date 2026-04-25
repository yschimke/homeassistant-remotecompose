package ee.schimke.terrazzo.dashboard

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import ee.schimke.terrazzo.MainActivity
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * uiautomator-driven regression test for the long-press → install flow.
 *
 * The dashboard renders each card inside a `RemotePreview` whose
 * underlying RC player consumes pointer events on the **Main** pass to
 * dispatch in-document click regions. A standard outer
 * `Modifier.combinedClickable` long-press never fires because its
 * `awaitFirstDown(requireUnconsumed = true)` is always rejected by
 * those consumed events.
 *
 * `Modifier.longPressBeforeChild` (in `CombinedClickLongPress.kt`)
 * fixes this by listening on the **Initial** pass; this test exercises
 * the fix end-to-end through the real Android input pipeline rather
 * than Compose's test driver, since it's the input pipeline's
 * Initial → Main pass propagation that's actually under test.
 *
 * Demo mode is forced on via [MainActivity.EXTRA_TEST_DEMO_MODE] so we
 * land directly on a dashboard with deterministic fake data, avoiding
 * the discovery / IndieAuth flow that can't reach anything from CI.
 */
@RunWith(AndroidJUnit4::class)
class LongPressInstallTest {

    private val device: UiDevice =
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before
    fun launchAppInDemoMode() {
        device.pressHome()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(MainActivity.EXTRA_TEST_DEMO_MODE, true)
        context.startActivity(launch)
        // Wait for the navigation suite to render (Dashboards is the
        // default destination after demo-mode short-circuits auth).
        assertTrue(
            "navigation suite did not surface within 15s",
            device.wait(Until.hasObject(By.text("Dashboards")), 15_000),
        )
    }

    @After
    fun tearDown() {
        device.pressHome()
    }

    @Test
    fun longPressOnDashboardCard_opensInstallSheet() {
        // The DashboardPickerScreen lists at least one dashboard;
        // the demo session's default is "Home". Tapping it opens
        // the dashboard view.
        val pickerEntry = device.wait(Until.findObject(By.text("Home")), 10_000)
        assertNotNull("dashboard picker did not list 'Home' within 10s", pickerEntry)
        pickerEntry!!.click()

        val card = waitForFirstCard()
        // uiautomator's longClick uses the platform long-press timeout,
        // so this matches what a real user's finger does — and that's
        // exactly the path Modifier.longPressBeforeChild's
        // `viewConfiguration.longPressTimeoutMillis` measures against.
        card.longClick()

        // WidgetInstallSheet.kt opens with the headline below.
        val sheetVisible = device.wait(
            Until.hasObject(By.text("Add to Home Screen")),
            5_000,
        )
        assertTrue(
            "install sheet did not surface after long-press — long-press " +
                "is being swallowed by the RC player again, see " +
                "Modifier.longPressBeforeChild",
            sheetVisible,
        )
    }

    private fun waitForFirstCard(): UiObject2 {
        val anyCard = device.wait(
            Until.findObject(By.descStartsWith("dashboard-card:")),
            10_000,
        )
        assertNotNull(
            "no card with semantics descriptor 'dashboard-card:*' rendered within 10s",
            anyCard,
        )
        return anyCard!!
    }
}
