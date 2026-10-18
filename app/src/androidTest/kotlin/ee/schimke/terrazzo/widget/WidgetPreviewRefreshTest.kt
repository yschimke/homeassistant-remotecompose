package ee.schimke.terrazzo.widget

import android.content.ContentValues
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inspector.WindowInspector
import android.widget.RemoteViews
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.terrazzo.LocalTerrazzoGraph
import ee.schimke.terrazzo.TerrazzoApplication
import ee.schimke.terrazzo.core.session.DemoData
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Deliberately holds each capture open: a frozen screenshot cannot catch loading-frame flicker. */
@RunWith(AndroidJUnit4::class)
class WidgetPreviewRefreshTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val card =
    CardConfig(
      "tile",
      buildJsonObject {
        put("type", "tile")
        put("entity", "light.preview")
        put("name", "Preview light")
      },
    )

  private fun snapshot(value: String) =
    HaSnapshot(states = mapOf("light.preview" to EntityState("light.preview", value)))

  private class Request(val views: RemoteViews, val text: String) {
    val result = LinkedBlockingQueue<RemoteViews>()

    fun succeed() {
      result.put(views)
    }
  }

  private val requests = LinkedBlockingQueue<Request>()
  private val capture: WidgetPreviewCapture = { context, _, _, snapshot, size ->
    // Real native inflation, with deterministic content and a controllably slow image/capture step.
    val text = "Live preview: ${snapshot.states.values.first().state} $size"
    val views =
      RemoteViews(context.packageName, android.R.layout.simple_list_item_1).apply {
        setTextViewText(android.R.id.text1, text)
      }
    val request = Request(views, text)
    requests.put(request)
    checkNotNull(request.result.poll(30, TimeUnit.SECONDS)) { "Test did not release capture" }
  }

  private fun nextRequest(): Request {
    // LaunchedEffect's debounce and polling use the Compose test clock. Keep advancing
    // frames while waiting for the background capture, rather than blocking that clock.
    compose.waitUntil(15_000) { requests.isNotEmpty() }
    return requests.remove()
  }

  private fun visibleText(view: View): TextView? {
    if (
      view is TextView &&
        view.text.startsWith("Live preview:") &&
        view.isShown &&
        view.width > 0 &&
        view.height > 0
    )
      return view
    if (view is ViewGroup) {
      for (index in 0 until view.childCount) visibleText(view.getChildAt(index))?.let {
        return it
      }
    }
    return null
  }

  @Test
  fun periodicSheetRefreshAndResizeNeverRemoveTheLastNativePreview() {
    val context = compose.activity.applicationContext
    val graph = (context as TerrazzoApplication).graph
    var refresh = 0
    compose.setContent {
      CompositionLocalProvider(
        LocalTerrazzoGraph provides graph,
        LocalWidgetPreviewCapture provides capture,
      ) {
        MaterialTheme {
          WidgetInstallSheet(
            DemoData.BASE_URL,
            "",
            card,
            snapshot("0"),
            {},
            refreshSnapshot = {
              val value = refresh++
              if (value > 3) awaitCancellation()
              snapshot(value.toString())
            },
          )
        }
      }
    }
    nextRequest().succeed()
    compose.waitUntil(10_000) {
      compose.onAllNodesWithTag("widget-render").fetchSemanticsNodes().isNotEmpty()
    }
    // ModalBottomSheet uses a dialog window, separate from the activity's decor view.
    var previewWindow: View? = null
    compose.waitUntil(10_000) {
      compose.runOnIdle {
        previewWindow =
          WindowInspector.getGlobalWindowViews().firstOrNull { visibleText(it) != null }
      }
      previewWindow != null
    }
    val root = checkNotNull(previewWindow)
    val samples = mutableListOf<String>()
    val started = android.os.SystemClock.elapsedRealtime()
    var blankFrames = 0
    val hosts = mutableSetOf<Int>()
    val listener = ViewTreeObserver.OnPreDrawListener {
      val text = visibleText(root)
      if (text == null) blankFrames++
      val host = text?.parent?.parent
      if (host != null) hosts.add(System.identityHashCode(host))
      samples.add(
        "${android.os.SystemClock.elapsedRealtime() - started},${System.identityHashCode(host)},\"${text?.text ?: "BLANK"}\""
      )
      root.postInvalidateOnAnimation()
      true
    }
    compose.runOnIdle {
      root.viewTreeObserver.addOnPreDrawListener(listener)
      root.postInvalidateOnAnimation()
    }
    try {
      // Three five-second polling intervals on the Compose test clock, including 750ms of blocked
      // capture each time.
      repeat(3) {
        val request = nextRequest()
        Thread.sleep(750)
        compose.onNodeWithTag("widget-render").assertExists()
        compose.onNodeWithText("Install widget").assertIsEnabled()
        request.succeed()
        compose.waitUntil(10_000) {
          var text: String? = null
          compose.runOnIdle { text = visibleText(root)?.text?.toString() }
          text == request.text
        }
      }
      listOf("Roomy", "Compact", "Roomy").forEach { preset ->
        compose.onNodeWithText(preset).performScrollTo().performClick()
        val request = nextRequest()
        Thread.sleep(750)
        compose.onNodeWithTag("widget-render").assertExists()
        request.succeed()
        compose.waitUntil(10_000) {
          var text: String? = null
          compose.runOnIdle { text = visibleText(root)?.text?.toString() }
          text == request.text
        }
      }
      // Scroll the outer sheet first: the handle's closest scroll parent only pans horizontally.
      compose.onNodeWithTag("widget-grid-viewport").performScrollTo()
      val density = context.resources.displayMetrics.density
      val handle = compose.onNodeWithTag("widget-resize-handle").performScrollTo()
      handle.assertIsDisplayed()
      val dimensions =
        checkNotNull(
            Regex("""(\d+) by (\d+) cells""")
              .matchEntire(
                handle
                  .fetchSemanticsNode()
                  .config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription]
              )
          )
          .groupValues
          .drop(1)
          .map(String::toInt)
      handle.performTouchInput {
        // Cross touch slop before travelling a full cell in each direction.
        swipe(center, center - Offset(112 * density, 128 * density))
      }
      val dragged = nextRequest()
      Thread.sleep(750)
      compose.onNodeWithTag("widget-render").assertExists()
      dragged.succeed()
      compose.waitUntil(10_000) {
        var text: String? = null
        compose.runOnIdle { text = visibleText(root)?.text?.toString() }
        text == dragged.text
      }
      compose.onNodeWithText("${dimensions[0] - 1} × ${dimensions[1] - 1} cells").assertExists()
      compose.onNodeWithText("Install widget").assertIsEnabled()
    } finally {
      compose.runOnIdle { root.viewTreeObserver.removeOnPreDrawListener(listener) }
      val uri =
        checkNotNull(
          context.contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
              put(MediaStore.Downloads.DISPLAY_NAME, "widget-preview-frames.csv")
              put(MediaStore.Downloads.MIME_TYPE, "text/csv")
              put(MediaStore.Downloads.RELATIVE_PATH, "Download/TerrazzoWidgetTests")
            },
          )
        )
      checkNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter().use {
        it.write("elapsed_ms,host_identity,native_preview\n" + samples.joinToString("\n"))
      }
    }
    assertTrue("Must observe frames over multiple polling intervals", samples.size > 100)
    assertEquals(
      "Every drawn frame must retain native preview content; see frame CSV",
      0,
      blankFrames,
    )
    assertEquals("Refresh and resize must preserve the attached native host", 1, hosts.size)
  }

  @Test
  fun failedInflationRetainsContentButInvalidatesInstallationUntilRecovery() {
    val current = mutableStateOf(snapshot("off"))
    val edited = mutableStateOf(card)
    var installable: RemoteViews? = null
    compose.setContent {
      MaterialTheme {
        CompositionLocalProvider(LocalWidgetPreviewCapture provides capture) {
          Column {
            WidgetPreparationPreview(DemoData.BASE_URL, edited.value, current.value) {
              installable = it
            }
          }
        }
      }
    }
    val first = nextRequest()
    first.succeed()
    compose.waitUntil(10_000) { installable === first.views }
    compose.runOnIdle { current.value = snapshot("on") }
    val broken = nextRequest()
    compose.runOnIdle { assertSame(first.views, installable) }
    broken.result.put(RemoteViews(compose.activity.packageName, 0))
    compose.waitUntil(10_000) {
      compose.onAllNodesWithTag("widget-preview-error").fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag("widget-render").assertExists()
    compose.runOnIdle {
      assertNotNull(visibleText(compose.activity.window.decorView))
      assertNull(installable)
    }
    compose.onNodeWithText("Retry preview").performClick()
    val retry = nextRequest()
    retry.succeed()
    compose.waitUntil(10_000) { installable === retry.views }
    compose.runOnIdle { current.value = snapshot("superseded") }
    val superseded = nextRequest()
    compose.runOnIdle { current.value = snapshot("newest") }
    val newest = nextRequest()
    newest.succeed()
    compose.waitUntil(10_000) { installable === newest.views }
    superseded.succeed()
    // Force another UI turn after the cancelled background capture returns.
    Thread.sleep(200)
    compose.runOnIdle {
      assertSame("Cancelled captures cannot overwrite newer state", newest.views, installable)
    }
    compose.runOnIdle {
      edited.value =
        card.copy(
          raw =
            buildJsonObject {
              put("type", "tile")
              put("entity", "light.preview")
              put("name", "Edited preview")
            }
        )
    }
    val edit = nextRequest()
    compose.runOnIdle { assertNull("An older edit must never be installed", installable) }
    compose.onNodeWithTag("widget-render").assertExists()
    edit.succeed()
    compose.waitUntil(10_000) { installable === edit.views }
  }
}
