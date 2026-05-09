@file:Suppress("RestrictedApi", "RestrictedApiAndroidX")

package ee.schimke.terrazzo.wear.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.glance.wear.GlanceWearWidget
import androidx.glance.wear.WearWidgetBrush
import androidx.glance.wear.WearWidgetData
import androidx.glance.wear.WearWidgetDocument
import androidx.glance.wear.core.WearWidgetParams
import androidx.glance.wear.tooling.preview.WearWidgetParamsProvider
import androidx.glance.wear.tooling.preview.WearWidgetPreview

/**
 * Slot-widget @Preview fixtures. Each renders the actual production
 * composition ([SlotContent]) inside a [WearWidgetPreview] frame so
 * the IDE preview reflects what the watch will paint.
 *
 * Exercises both Glance Wear container sizes via the
 * [WearWidgetParamsProvider] from the vendored AOSP utility — Android
 * Studio fans out one preview per provided `WearWidgetParams`.
 *
 * The sample widgets here are inline subclasses of [GlanceWearWidget]
 * with hardcoded payloads (no disk reads), so the preview doesn't
 * depend on `WearOfflineStore`. Production [SlotWidget] subclasses
 * still drive both small and large from the same store row.
 */

private class PreviewSlotWidget(
    private val title: String,
    private val state: String,
) : GlanceWearWidget() {
    override suspend fun provideWidgetData(
        context: Context,
        params: WearWidgetParams,
    ): WearWidgetData =
        WearWidgetDocument(
            background = WearWidgetBrush.Companion,
            content = { SlotContent(title, state) },
        )
}

/** Assigned slot showing a sensor reading — typical case. */
@Preview(name = "Slot widget — assigned w/ value")
@Composable
fun SlotWidgetPreview_AssignedWithValue(
    @PreviewParameter(WearWidgetParamsProvider::class) params: WearWidgetParams,
) = WearWidgetPreview(
    widget = PreviewSlotWidget(title = "Living Room", state = "21.5 °C"),
    params = params,
)

/** Assigned slot but no live value yet (pre-stream). */
@Preview(name = "Slot widget — assigned no value")
@Composable
fun SlotWidgetPreview_AssignedNoValue(
    @PreviewParameter(WearWidgetParamsProvider::class) params: WearWidgetParams,
) = WearWidgetPreview(
    widget = PreviewSlotWidget(title = "Front door", state = ""),
    params = params,
)

/** Race / fallback state — slot has no card assigned yet. */
@Preview(name = "Slot widget — empty placeholder")
@Composable
fun SlotWidgetPreview_Empty(
    @PreviewParameter(WearWidgetParamsProvider::class) params: WearWidgetParams,
) = WearWidgetPreview(
    widget = PreviewSlotWidget(title = "Slot 1", state = ""),
    params = params,
)
