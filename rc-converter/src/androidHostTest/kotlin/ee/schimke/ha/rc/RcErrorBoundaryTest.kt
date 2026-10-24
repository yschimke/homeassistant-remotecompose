package ee.schimke.ha.rc

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import ee.schimke.terrazzo.shared.RcDisabledDocuments
import ee.schimke.terrazzo.shared.RcErrorBoundary
import ee.schimke.terrazzo.shared.rcDocumentKey
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A player that throws disables its document and shows the atomic-symbol warning in its place,
 * while the rest of the screen keeps drawing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RcErrorBoundaryTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private val bytes = byteArrayOf(1, 2, 3)
  private val key = rcDocumentKey(bytes)

  @AfterTest fun cleanUp() = RcDisabledDocuments.clear()

  @Test
  fun aPlayerThatThrowsWhileMeasuringIsDisabled() {
    compose.setContent {
      Column {
        RcErrorBoundary(bytes) { Layout({}) { _, _ -> error("measure failed") } }
        BasicText("the rest of the screen")
      }
    }
    compose.waitForIdle()
    assertEquals("IllegalStateException: measure failed", RcDisabledDocuments.reason(key))
    compose.onNodeWithText("Card disabled after an error").assertExists()
    compose
      .onNodeWithContentDescription("Card disabled after an error", substring = true)
      .assertExists()
    compose.onNodeWithText("the rest of the screen").assertExists()
  }

  @Test
  fun aPlayerThatThrowsWhileDrawingIsDisabled() {
    compose.setContent {
      RcErrorBoundary(bytes) {
        Box(Modifier.size(20.dp).drawBehind { throw IllegalArgumentException("draw failed") })
      }
    }
    // Robolectric draws only when asked: capturing a frame runs the draw pass that throws.
    compose.onRoot().captureToImage()
    compose.waitForIdle()
    assertEquals("IllegalArgumentException: draw failed", RcDisabledDocuments.reason(key))
    compose.onNodeWithText("Card disabled after an error").assertExists()
  }

  @Test
  fun aHostCallbackThatThrowsDisablesTheDocument() {
    val callback = mutableStateOf<(() -> Unit)?>(null)
    compose.setContent {
      RcErrorBoundary(bytes) { fail ->
        callback.value = { runCatching { error("action failed") }.onFailure(fail) }
        Box(Modifier.size(20.dp))
      }
    }
    compose.waitForIdle()
    assertNull(RcDisabledDocuments.reason(key))
    compose.runOnIdle { callback.value!!.invoke() }
    compose.waitForIdle()
    assertNotNull(RcDisabledDocuments.reason(key))
    compose.onNodeWithText("Card disabled after an error").assertExists()
  }

  @Test
  fun everyPlayerOfADisabledDocumentShowsTheWarning() {
    RcDisabledDocuments.disable(key, IllegalStateException("earlier"))
    compose.setContent {
      Column {
        RcErrorBoundary(bytes) { BasicText("playing") }
        RcErrorBoundary(byteArrayOf(9)) { BasicText("another document") }
      }
    }
    compose.onNodeWithText("playing").assertDoesNotExist()
    compose.onNodeWithText("another document").assertExists()
  }

  @Test
  fun aCancellationHandedToTheBoundaryIsRethrownNotSwallowed() {
    var fail: ((Throwable) -> Unit)? = null
    compose.setContent {
      RcErrorBoundary(bytes) {
        fail = it
        Box(Modifier.size(20.dp))
      }
    }
    compose.waitForIdle()
    assertFailsWith<CancellationException> { fail!!(CancellationException("stopped")) }
    assertNull(RcDisabledDocuments.reason(key))
  }

  @Test
  fun documentsSharingALengthAndContentHashHaveDifferentKeys() {
    val a = byteArrayOf(0, 31)
    val b = byteArrayOf(1, 0)
    assertEquals(a.contentHashCode(), b.contentHashCode())
    assertNotEquals(rcDocumentKey(a), rcDocumentKey(b))
  }

  @Test
  fun theDocumentComposingIsInFlightOnlyWhileItComposes() {
    var seen: String? = null
    compose.setContent { RcErrorBoundary(bytes) { seen = RcDisabledDocuments.inFlight() } }
    compose.waitForIdle()
    assertEquals(key, seen)
    assertNull(RcDisabledDocuments.inFlight())
  }
}
