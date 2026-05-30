@file:Suppress("RestrictedApi", "RestrictedApiAndroidX")

package ee.schimke.terrazzo.widget

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asImageBitmap
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.ProvideCardRegistry
import ee.schimke.ha.rc.ProvideCardSizeMode
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.cardHeightDp
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.cards.shutter.withEnhancedShutter
import ee.schimke.ha.rc.components.LocalPictureImageStrategy
import ee.schimke.ha.rc.components.PictureImageStrategy
import ee.schimke.ha.rc.components.ProvideCardChrome
import ee.schimke.ha.rc.components.ProvideHaTheme
import ee.schimke.ha.rc.components.RemoteHaWidgetSurface
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.haThemeFor
import ee.schimke.ha.rc.widgetsProfile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Debug activity that exercises the **widget rendering pipeline** —
 * [captureSingleRemoteDocument] with [widgetsProfile], wrapped in
 * `RemoteViews.DrawInstructions`, inflated as `RemoteViews` — for a
 * `picture-entity` card backed by [PictureImageStrategy.Inline].
 *
 * Same code path as [TerrazzoWidgetProvider]: theme + registry
 * wiring, [widgetsProfile] capture, `DrawInstructions` build,
 * `updateAppWidget`. The only difference is the destination — here
 * the `RemoteViews` is `apply()`ed into a host `FrameLayout`
 * directly so the activity can show what a launcher would draw.
 * Lets us verify that the [PictureImageStrategy.Inline] (bytes-form
 * baked into the doc) actually paints under the stricter widget
 * runtime — no `BitmapLoader`, no override channel.
 *
 * Launch via:
 *
 * ```
 * adb shell am start -n ee.schimke.harc/ee.schimke.terrazzo.widget.WidgetPreviewActivity
 * ```
 *
 * The sample bitmap is generated programmatically (yellow disc on a
 * blue field), so the activity is self-contained — no network, no
 * session needed. If the rendered tile shows the disc, the widget
 * path renders inline bitmaps correctly and `TerrazzoWidgetProvider`
 * just needs the pre-fetch wiring (walk `cardPictureEntityIds`,
 * resolve each URL via `HaImageStack.resolve`, provide [Inline]).
 *
 * API floor matches [TerrazzoWidgetProvider]:
 * `VANILLA_ICE_CREAM` / API 35 — needed for
 * `RemoteViews.DrawInstructions`.
 */
@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
class WidgetPreviewActivity : Activity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
      setContentView(
        TextView(this).apply {
          text = "RemoteViews.DrawInstructions needs API 35+"
          gravity = Gravity.CENTER
        }
      )
      return
    }

    val widthPx = WidgetSizing.dpToPx(this, WIDGET_PREVIEW_WIDTH_DP)
    val heightPx = WidgetSizing.dpToPx(this, WIDGET_PREVIEW_HEIGHT_DP)
    val densityDpi = resources.configuration.densityDpi

    val registry = defaultRegistry().withEnhancedShutter()
    val dark =
      (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    val haTheme = haThemeFor(ThemeStyle.TerrazzoHome, dark)

    val sampleBitmap = sampleBitmap(SAMPLE_BITMAP_PX).asImageBitmap()
    val strategy = PictureImageStrategy.Inline { sampleBitmap }

    val docBytes =
      runCatching {
        runBlocking {
          captureSingleRemoteDocument(
            context = this@WidgetPreviewActivity,
            creationDisplayInfo = RemoteCreationDisplayInfo(widthPx, heightPx, densityDpi),
            profile = widgetsProfile,
          ) {
            // The Inline strategy provider must live inside the
            // capture sub-composition — outer-tree CompositionLocals
            // don't propagate through `captureSingleRemoteDocument`.
            CompositionLocalProvider(LocalPictureImageStrategy provides strategy) {
              ProvideCardRegistry(registry) {
                ProvideHaTheme(haTheme) {
                  ProvideCardSizeMode(CardSizeMode.Fixed) {
                    // Same full-canvas surface the launcher widget uses,
                    // so the preview shows the background filling the tile
                    // rather than wrapping to the card content.
                    ProvideCardChrome(enabled = false) {
                      RemoteHaWidgetSurface {
                        RenderChild(SAMPLE_CARD, SAMPLE_SNAPSHOT, RemoteModifier.fillMaxWidth())
                      }
                    }
                  }
                }
              }
            }
          }
        }.bytes
      }
        .onFailure { Log.e(TAG, "widget capture failed", it) }
        .getOrNull()

    if (docBytes == null) {
      setContentView(
        TextView(this).apply {
          text = "Capture failed — see $TAG logs"
          gravity = Gravity.CENTER
        }
      )
      return
    }

    Log.d(TAG, "captured docBytes=${docBytes.size} bytes for ${WIDGET_PREVIEW_WIDTH_DP}x${WIDGET_PREVIEW_HEIGHT_DP}dp tile")

    val instructions = RemoteViews.DrawInstructions.Builder(listOf(docBytes)).build()
    val remoteViews = RemoteViews(instructions)

    // Host the rendered RemoteViews inside the activity layout the
    // same way a launcher would — call `apply(context, parent)` to
    // inflate, then attach to a host frame. The frame is sized to
    // the requested tile dimensions in pixels so the inflated view
    // gets matching constraints.
    val host =
      FrameLayout(this).apply {
        setBackgroundColor(AndroidColor.LTGRAY)
        val padding = (16f * resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
      }
    val tile =
      FrameLayout(this).apply {
        setBackgroundColor(AndroidColor.WHITE)
        layoutParams =
          FrameLayout.LayoutParams(widthPx, heightPx).apply { gravity = Gravity.CENTER }
      }
    val rendered = remoteViews.apply(this, tile)
    tile.addView(rendered)

    val container =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(
          TextView(this@WidgetPreviewActivity).apply {
            text =
              "widget preview · ${WIDGET_PREVIEW_WIDTH_DP}x${WIDGET_PREVIEW_HEIGHT_DP}dp · " +
                "${docBytes.size}b doc"
            setPadding(0, 0, 0, (8f * resources.displayMetrics.density).toInt())
          }
        )
        addView(tile)
      }
    host.addView(
      container,
      FrameLayout.LayoutParams(
          FrameLayout.LayoutParams.WRAP_CONTENT,
          FrameLayout.LayoutParams.WRAP_CONTENT,
        )
        .apply { gravity = Gravity.CENTER },
    )
    setContentView(host)
  }

  private companion object {
    private const val TAG = "WidgetPreviewActivity"
    private const val WIDGET_PREVIEW_WIDTH_DP = 360
    private const val WIDGET_PREVIEW_HEIGHT_DP = 160
    private const val SAMPLE_BITMAP_PX = 512

    /**
     * Sample picture-entity card. Mirrors the shape the dashboard
     * picker emits — a real installation would pull this from the
     * pinned `WidgetStore`.
     */
    private val SAMPLE_CARD: CardConfig =
      Json.parseToJsonElement(
          """{"type":"picture-entity","entity":"camera.widget_preview","name":"Widget preview","show_name":true,"show_state":true}"""
        )
        .let { it as JsonObject }
        .let { CardConfig(type = (it["type"] as JsonPrimitive).content, raw = it) }

    /**
     * Snapshot with an `entity_picture` attribute set so the
     * converter takes the image branch (vs. the icon fallback). The
     * URL value is a marker — the [PictureImageStrategy.Inline]
     * resolver ignores it and returns the canned sample bitmap.
     */
    private val SAMPLE_SNAPSHOT: HaSnapshot =
      HaSnapshot(
        states =
          mapOf(
            "camera.widget_preview" to
              EntityState(
                entityId = "camera.widget_preview",
                state = "streaming",
                attributes =
                  JsonObject(
                    mapOf(
                      "friendly_name" to JsonPrimitive("Widget preview"),
                      "entity_picture" to JsonPrimitive("local://widget-preview-sample"),
                    )
                  ),
              )
          )
      )

    /** Yellow disc on a blue field — same test bitmap as the JVM previews. */
    private fun sampleBitmap(sizePx: Int): Bitmap {
      val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
      val canvas = AndroidCanvas(bmp)
      val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG)
      paint.color = AndroidColor.rgb(0x15, 0x65, 0xC0)
      canvas.drawRect(0f, 0f, sizePx.toFloat(), sizePx.toFloat(), paint)
      paint.color = AndroidColor.rgb(0xFF, 0xC1, 0x07)
      canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx * 0.32f, paint)
      return bmp
    }
  }
}
