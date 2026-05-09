@file:Suppress("RestrictedApi", "RestrictedApiAndroidX")

package ee.schimke.terrazzo.wear.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.glance.wear.GlanceWearWidget
import androidx.glance.wear.WearWidgetBrush
import androidx.glance.wear.WearWidgetData
import androidx.glance.wear.WearWidgetDocument
import androidx.glance.wear.core.ContainerInfo
import androidx.glance.wear.core.WearWidgetParams
import androidx.glance.wear.tooling.preview.WearWidgetPreview
import androidx.glance.wear.core.WidgetInstanceId

/**
 * Slot-widget @Preview fixtures. Each renders the actual production
 * composition ([SlotContent]) inside a [WearWidgetPreview] frame so
 * the IDE preview reflects what the watch will paint.
 *
 * Uses [WearWidgetPreviewWrapper] so each fixture can choose the
 * container size (small vs large) that best matches the card content.
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
@Preview(name = "Slot widget — assigned with value")
@Composable
fun SlotWidgetPreview_AssignedWithValue() = WearWidgetPreviewWrapper(containerType = ContainerType.Large) {
    WearWidgetPreview(
        widget = PreviewSlotWidget(title = "Living Room", state = "21.5 °C"),
        params = it,
    )
}

/** Assigned slot but no live value yet (pre-stream). */
@Preview(name = "Slot widget — assigned no value")
@Composable
fun SlotWidgetPreview_AssignedNoValue() = WearWidgetPreviewWrapper(containerType = ContainerType.Small) {
    WearWidgetPreview(
        widget = PreviewSlotWidget(title = "Front door", state = ""),
        params = it,
    )
}

/** Race / fallback state — slot has no card assigned yet. */
@Preview(name = "Slot widget — empty placeholder")
@Composable
fun SlotWidgetPreview_Empty() = WearWidgetPreviewWrapper(containerType = ContainerType.Small) {
    WearWidgetPreview(
        widget = PreviewSlotWidget(title = "Slot 1", state = ""),
        params = it,
    )
}

private enum class ContainerType { Small, Large }

@Composable
private fun WearWidgetPreviewWrapper(
    containerType: ContainerType,
    content: @Composable (WearWidgetParams) -> Unit,
) {
    val params =
        when (containerType) {
            ContainerType.Large -> largePreviewParams
            ContainerType.Small -> smallPreviewParams
        }
    content(params)
}

private val largePreviewParams =
    WearWidgetParams(
        instanceId = WidgetInstanceId("widgets", 1),
        containerType = ContainerInfo.CONTAINER_TYPE_LARGE,
        widthDp = 200f,
        heightDp = 112f,
        verticalPaddingDp = 8f,
        horizontalPaddingDp = 8f,
        cornerRadiusDp = 26f,
    )

private val smallPreviewParams =
    WearWidgetParams(
        instanceId = WidgetInstanceId("widgets", 2),
        containerType = ContainerInfo.CONTAINER_TYPE_SMALL,
        widthDp = 200f,
        heightDp = 60f,
        verticalPaddingDp = 8f,
        horizontalPaddingDp = 8f,
        cornerRadiusDp = 26f,
    )
