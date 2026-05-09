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

private data class PreviewCardFixture(
    val title: String,
    val state: String,
    val containerType: ContainerType,
)

private val PREVIEW_FIXTURES = listOf(
    PreviewCardFixture("Living Room", "21.5 °C", ContainerType.Large),
    PreviewCardFixture("Bedroom", "19.2 °C", ContainerType.Large),
    PreviewCardFixture("Nursery humidity", "47 %", ContainerType.Small),
    PreviewCardFixture("Kitchen", "on", ContainerType.Small),
    PreviewCardFixture("Dining lights", "off", ContainerType.Small),
    PreviewCardFixture("Front door", "locked", ContainerType.Small),
    PreviewCardFixture("Garage door", "open", ContainerType.Small),
    PreviewCardFixture("Coffee maker", "on", ContainerType.Small),
    PreviewCardFixture("Dishwasher", "running", ContainerType.Small),
    PreviewCardFixture("Laundry", "spin cycle", ContainerType.Large),
    PreviewCardFixture("Dryer", "done", ContainerType.Small),
    PreviewCardFixture("Vacuum", "cleaning", ContainerType.Small),
    PreviewCardFixture("Office sensor", "68 %", ContainerType.Small),
    PreviewCardFixture("Solar output", "4.8 kW", ContainerType.Large),
    PreviewCardFixture("Grid import", "1.2 kW", ContainerType.Large),
    PreviewCardFixture("Router uptime", "14 days", ContainerType.Large),
    PreviewCardFixture("Outside", "58 °F", ContainerType.Small),
    PreviewCardFixture("Rain today", "0.12 in", ContainerType.Small),
    PreviewCardFixture("Air quality", "AQI 34", ContainerType.Small),
    PreviewCardFixture("Bedroom blinds", "65 %", ContainerType.Small),
    PreviewCardFixture("Living room TV", "paused", ContainerType.Large),
    PreviewCardFixture("Alarm", "armed home", ContainerType.Small),
    PreviewCardFixture("Front door", "", ContainerType.Small),
    PreviewCardFixture("Slot 1", "", ContainerType.Small),
)

@Preview(name = "Slot widget — climate living room")
@Composable
fun SlotWidgetPreview_01() = SlotWidgetPreviewFixture(PREVIEW_FIXTURES[0])

@Preview(name = "Slot widget — climate bedroom")
@Composable
fun SlotWidgetPreview_02() = SlotWidgetPreviewFixture(PREVIEW_FIXTURES[1])

@Preview(name = "Slot widget — humidity nursery")
@Composable
fun SlotWidgetPreview_03() = SlotWidgetPreviewFixture(PREVIEW_FIXTURES[2])

@Preview(name = "Slot widget — kitchen lights")
@Composable
fun SlotWidgetPreview_04() = SlotWidgetPreviewFixture(PREVIEW_FIXTURES[3])

@Preview(name = "Slot widget — dining lights")
@Composable
fun SlotWidgetPreview_05() = SlotWidgetPreviewFixture(PREVIEW_FIXTURES[4])

@Preview(name = "Slot widget — front door lock")
@Composable
fun SlotWidgetPreview_06() = SlotWidgetPreviewFixture(PREVIEW_FIXTURES[5])

@Preview(name = "Slot widget — garage door")
@Composable
fun SlotWidgetPreview_07() = SlotWidgetPreviewFixture(PREVIEW_FIXTURES[6])

@Preview(name = "Slot widget — coffee maker")
@Composable
fun SlotWidgetPreview_08() = SlotWidgetPreviewFixture(PREVIEW_FIXTURES[7])

@Preview(name = "Slot widget — dishwasher")
@Composable
fun SlotWidgetPreview_09() = SlotWidgetPreviewFixture(PREVIEW_FIXTURES[8])

@Preview(name = "Slot widget — washing machine")
@Composable
fun SlotWidgetPreview_10() = SlotWidgetPreviewFixture(PREVIEW_FIXTURES[9])

@Preview(name = "Slot widget — dryer")
@Composable
fun SlotWidgetPreview_11() = SlotWidgetPreviewFixture(PREVIEW_FIXTURES[10])

@Preview(name = "Slot widget — vacuum")
@Composable
fun SlotWidgetPreview_12() = SlotWidgetPreviewFixture(PREVIEW_FIXTURES[11])

@Preview(name = "Slot widget — office battery")
@Composable
fun SlotWidgetPreview_13() = SlotWidgetPreviewFixture(PREVIEW_FIXTURES[12])

@Preview(name = "Slot widget — solar output")
@Composable
fun SlotWidgetPreview_14() = SlotWidgetPreviewFixture(PREVIEW_FIXTURES[13])

@Preview(name = "Slot widget — grid import")
@Composable
fun SlotWidgetPreview_15() = SlotWidgetPreviewFixture(PREVIEW_FIXTURES[14])

@Preview(name = "Slot widget — network uptime")
@Composable
fun SlotWidgetPreview_16() = SlotWidgetPreviewFixture(PREVIEW_FIXTURES[15])

@Preview(name = "Slot widget — weather outside")
@Composable
fun SlotWidgetPreview_17() = SlotWidgetPreviewFixture(PREVIEW_FIXTURES[16])

@Preview(name = "Slot widget — rainfall")
@Composable
fun SlotWidgetPreview_18() = SlotWidgetPreviewFixture(PREVIEW_FIXTURES[17])

@Preview(name = "Slot widget — air quality")
@Composable
fun SlotWidgetPreview_19() = SlotWidgetPreviewFixture(PREVIEW_FIXTURES[18])

@Preview(name = "Slot widget — blinds")
@Composable
fun SlotWidgetPreview_20() = SlotWidgetPreviewFixture(PREVIEW_FIXTURES[19])

@Preview(name = "Slot widget — media")
@Composable
fun SlotWidgetPreview_21() = SlotWidgetPreviewFixture(PREVIEW_FIXTURES[20])

@Preview(name = "Slot widget — alarm")
@Composable
fun SlotWidgetPreview_22() = SlotWidgetPreviewFixture(PREVIEW_FIXTURES[21])

@Preview(name = "Slot widget — assigned no value")
@Composable
fun SlotWidgetPreview_23() = SlotWidgetPreviewFixture(PREVIEW_FIXTURES[22])

@Preview(name = "Slot widget — empty placeholder")
@Composable
fun SlotWidgetPreview_24() = SlotWidgetPreviewFixture(PREVIEW_FIXTURES[23])

@Composable
private fun SlotWidgetPreviewFixture(fixture: PreviewCardFixture) =
    WearWidgetPreviewWrapper(containerType = fixture.containerType) {
        WearWidgetPreview(
            widget = PreviewSlotWidget(title = fixture.title, state = fixture.state),
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
