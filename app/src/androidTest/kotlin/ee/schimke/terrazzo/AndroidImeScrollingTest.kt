package ee.schimke.terrazzo

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.MediaStore
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.terrazzo.discovery.DiscoveryForm
import ee.schimke.terrazzo.discovery.HaInstance
import ee.schimke.terrazzo.shared.CardStudioScreen
import ee.schimke.terrazzo.ui.AndroidImeHost
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Long enough to outlast the IME's show/resize animation on a slow emulator. */
private const val IME_SETTLE_MS = 600L

/** Real IME tests: a frozen preview cannot verify viewport resizing or focus relocation. */
@AndroidWindowInsetsRegression
@RunWith(AndroidJUnit4::class)
class AndroidImeScrollingTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val device = UiDevice.getInstance(instrumentation)
  private var originalHardwareKeyboardSetting = "0"

  @Before
  fun configureWindow() {
    originalHardwareKeyboardSetting =
      device.executeShellCommand("settings get secure show_ime_with_hard_keyboard").trim()
    device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
    compose.runOnUiThread {
      compose.activity.enableEdgeToEdge()
      compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
  }

  @After
  fun restoreKeyboard() {
    device.pressBack()
    if (originalHardwareKeyboardSetting == "null") {
      device.executeShellCommand("settings delete secure show_ime_with_hard_keyboard")
    } else {
      device.executeShellCommand(
        "settings put secure show_ime_with_hard_keyboard $originalHardwareKeyboardSetting"
      )
    }
  }

  @Test
  fun mainActivityRequestsResize() {
    val info =
      compose.activity.packageManager.getActivityInfo(
        android.content.ComponentName(compose.activity, MainActivity::class.java),
        0,
      )
    assertEquals(
      WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
      info.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST,
    )
  }

  @Test
  fun discoveredInstancesDoNotStrandUrlOrBottomActionsBehindKeyboard() {
    var connected = false
    var demoSelected = false
    compose.setContent {
      MaterialTheme {
        AndroidImeHost {
          var host by remember { mutableStateOf("http://homeassistant:8123") }
          DiscoveryForm(
            instances =
              listOf(
                HaInstance("Garage", "http://172.30.32.1:8123", "2026.4.1"),
                HaInstance("Home", "http://homeassistant:8123", "2026.10.0"),
              ),
            host = host,
            onHostChange = { host = it },
            onConnect = { connected = true },
            onInstancePicked = {},
            onDemoSelected = { demoSelected = true },
          )
        }
      }
    }
    val field = compose.onNode(hasSetTextAction())
    // Request real text-input focus even when the initial layout clips the bottom field.
    // Capture the keyboard-visible starting state before testing scrollability.
    field.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
    showIme()
    waitForIme(true)
    captureWindowEvidence("discovery-open-ime")
    assertAboveKeyboard(field)
    field.performScrollTo()
    field.performTextReplacement("http://homeassistant.local:8123")
    // Typing can resize the keyboard (its suggestion strip appears), which shrinks the viewport
    // after the scroll above; let it settle and bring the field back before measuring.
    awaitImeSettled()
    field.performScrollTo()
    captureWindowEvidence("discovery-focused-ime")
    assertAboveKeyboard(field)
    val connect = compose.onNodeWithText("Connect", useUnmergedTree = false)
    connect.performScrollTo().assertIsDisplayed()
    assertAboveKeyboard(connect)
    connect.performClick()
    compose.runOnIdle { assertTrue(connected) }
    // Tapping Connect can take the keyboard down on some emulator images and not others, and a
    // check made mid-animation sees either a stranded action or the closed form's resting gap.
    // The bottom actions are asserted with the keyboard up, so bring it back explicitly.
    field.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
    showIme()
    waitForIme(true)
    val demo = compose.onNodeWithText("Try demo mode (no login)")
    demo.performScrollTo().assertIsDisplayed()
    captureWindowEvidence("discovery-bottom-ime")
    scrollToBottomAboveKeyboard(demo)
    assertAboveKeyboard(demo)
    assertNoExtraBottomInset(demo)
    demo.performClick()
    compose.runOnIdle { assertTrue(demoSelected) }
    waitForIme(true)

    device.pressBack()
    waitForIme(false)
    field.performScrollTo().assertIsDisplayed()
    field.assertTextContains("http://homeassistant.local:8123")
    demo.performScrollTo().assertIsDisplayed()
    scrollFormToBottom()
    assertNoExtraBottomInset(demo)
    captureWindowEvidence("discovery-bottom-closed")
    // Opening a second time catches stale padding/scroll-range state after IME dismissal.
    field.performScrollTo().performClick()
    waitForIme(true)
    demo.performScrollTo().assertIsDisplayed()
    assertAboveKeyboard(demo)
  }

  @Test
  fun lastCardStudioFieldAndActionsRemainReachableWithIme() {
    var backedOut = false
    compose.setContent {
      MaterialTheme {
        AndroidImeHost {
          CardStudioScreen(
            card = CardConfig("tile", buildJsonObject {}),
            snapshot = HaSnapshot(),
            onBack = { backedOut = true },
            preview = {},
          )
        }
      }
    }
    val field = compose.onNodeWithText("Create a missing design or describe the issue")
    field.performScrollTo().performClick()
    waitForIme(true)
    val draft = "Make the last field easy to reach\nKeep the reading clear\nKeep Send reachable"
    field.performTextInput(draft)
    field.assertTextContains(draft)
    captureWindowEvidence("card-studio-focused-ime")
    assertAboveKeyboard(field)
    val action = compose.onNodeWithText("Generate or fix draft")
    action.performScrollTo().assertIsDisplayed()
    captureWindowEvidence("card-studio-bottom-ime")
    assertAboveKeyboard(action)
    if (InstrumentationRegistry.getArguments().getString("expectedRotation") == "1") {
      val back = compose.onNodeWithText("Back")
      back.performScrollTo().assertIsDisplayed()
      assertAboveKeyboard(back)
      captureWindowEvidence("card-studio-compact-back-ime")
      back.performClick()
      compose.runOnIdle { assertTrue("Compact toolbar must retain Back", backedOut) }
    }
    waitForIme(true)
    device.pressBack()
    waitForIme(false)
    field.performScrollTo().assertTextContains(draft)
    action.performScrollTo().assertIsDisplayed()
    captureWindowEvidence("card-studio-bottom-closed")
  }

  private fun windowInsets(): WindowInsetsCompat =
    checkNotNull(ViewCompat.getRootWindowInsets(compose.activity.window.decorView))

  /** Shows the keyboard for the focused field; the window controller acts on the real IME. */
  private fun showIme() {
    compose.runOnIdle {
      WindowInsetsControllerCompat(compose.activity.window, compose.activity.window.decorView)
        .show(WindowInsetsCompat.Type.ime())
    }
  }

  private fun waitForIme(visible: Boolean) {
    compose.waitUntil(10_000) {
      compose.runOnIdle { windowInsets().isVisible(WindowInsetsCompat.Type.ime()) == visible }
    }
    device.waitForIdle()
    compose.waitForIdle()
    awaitImeSettled()
  }

  /**
   * Waits until the keyboard's height has held still for [IME_SETTLE_MS]. [waitForIme] only sees
   * the target visibility: the IME can still be animating in, or change height after typing (its
   * suggestion strip), and `imePadding()` follows it frame by frame. A scroll made before that
   * lands on a viewport that then shrinks, leaving the scrolled-to control under the keyboard on a
   * slow emulator. Settling first keeps the assertions about layout, not about timing.
   */
  private fun awaitImeSettled() {
    var lastBottom = -1
    var stableSince = 0L
    compose.waitUntil(5_000) {
      val bottom = compose.runOnIdle {
        windowInsets().getInsets(WindowInsetsCompat.Type.ime()).bottom
      }
      val now = SystemClock.uptimeMillis()
      if (bottom != lastBottom) {
        lastBottom = bottom
        stableSince = now
        false
      } else {
        now - stableSince >= IME_SETTLE_MS
      }
    }
    compose.waitForIdle()
  }

  private fun assertAboveKeyboard(node: SemanticsNodeInteraction) {
    node.assertIsDisplayed()
    val semantics = node.fetchSemanticsNode()
    val bottom = semantics.positionInWindow.y + semantics.size.height
    val keyboardTop = compose.runOnIdle {
      compose.activity.window.decorView.height -
        windowInsets().getInsets(WindowInsetsCompat.Type.ime()).bottom
    }
    assertTrue(
      "Entire control must be above the real IME: bottom $bottom, IME top $keyboardTop",
      bottom <= keyboardTop + 1,
    )
  }

  /**
   * Scrolls the form to its end until [node] sits above the keyboard. The keyboard can still change
   * height after [waitForIme] (a suggestion strip, or a re-show after Connect), and a scroll offset
   * kept from the taller viewport then leaves the last action below the shorter one. Retrying the
   * scroll asserts the settled layout rather than the frame the scroll happened to land on.
   */
  private fun scrollToBottomAboveKeyboard(node: SemanticsNodeInteraction) {
    compose.waitUntil(10_000) {
      awaitImeSettled()
      scrollFormToBottom()
      runCatching { assertAboveKeyboard(node) }.isSuccess
    }
  }

  private fun scrollFormToBottom() {
    compose
      .onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
      .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
    compose.waitForIdle()
  }

  private fun assertNoExtraBottomInset(lastAction: SemanticsNodeInteraction) {
    // At the scroll limit the form has 24dp of design padding below its last 48dp button.
    // Compare against max(IME, navigation bar), never their sum (important in three-button
    // mode).
    val bounds = lastAction.fetchSemanticsNode().boundsInWindow
    val availableBottom = compose.runOnIdle {
      compose.activity.window.decorView.height -
        windowInsets()
          .getInsets(WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.systemBars())
          .bottom
    }
    val density = compose.activity.resources.displayMetrics.density
    val gap = (availableBottom - bounds.bottom) / density
    // Text semantics exclude the button's internal padding; allow that plus rounding.
    assertTrue("No doubled or stale bottom inset: gap=${gap}dp", gap in 23f..41f)
  }
}

internal fun captureWindowEvidence(name: String) {
  val instrumentation = InstrumentationRegistry.getInstrumentation()
  // Window appearance flags change before System UI finishes animating the icons/pill.
  UiDevice.getInstance(instrumentation).waitForIdle()
  val context = instrumentation.targetContext
  val uri =
    checkNotNull(
      context.contentResolver.insert(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        ContentValues().apply {
          put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
          put(MediaStore.Images.Media.MIME_TYPE, "image/png")
          put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TerrazzoImeTests")
        },
      )
    )
  val original = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
  val scale = minOf(1f, 1800f / maxOf(original.width, original.height))
  val bitmap =
    if (scale < 1f)
      Bitmap.createScaledBitmap(
        original,
        (original.width * scale).toInt(),
        (original.height * scale).toInt(),
        true,
      )
    else original
  checkNotNull(context.contentResolver.openOutputStream(uri)).use {
    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
  }
  if (bitmap !== original) bitmap.recycle()
  original.recycle()
}
