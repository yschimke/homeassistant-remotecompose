@file:Suppress("RestrictedApi")

// Preview helper that shows a Glance Wear widget the way each watch's widget host frames it: the
// container's padding and corner radius come from the host (Pixel's squircle, Samsung's stadium),
// so one widget is drawn once per host, centred on a round black screen with no tile chrome.

package androidx.glance.wear.tooling.preview

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.wear.GlanceWearWidget
import androidx.glance.wear.core.ContainerInfo
import androidx.glance.wear.core.WearWidgetParams
import androidx.glance.wear.core.WidgetInstanceId
import ee.schimke.ha.rc.HaRcComposePlayer
import ee.schimke.ha.rc.WrapAdaptiveRemoteDocumentPlayer
import ee.schimke.terrazzo.wear.widget.WearWidgetIrSidecar
import kotlinx.coroutines.runBlocking

/** A watch's widget host: its name and the frame it gives each container type. */
public class WearWidgetHost(
  public val name: String,
  public val small: WearWidgetParams,
  public val large: WearWidgetParams,
) {
  public fun params(containerType: Int): WearWidgetParams =
    if (containerType == ContainerInfo.CONTAINER_TYPE_LARGE) large else small

  public companion object {
    private fun params(type: Int, w: Float, h: Float, hp: Float, vp: Float, r: Float) =
      WearWidgetParams(WidgetInstanceId("widgets", 1), type, w, h, hp, vp, r)

    /** Pixel Watch: a squircle, 8 dp in from the edge, 26 dp corners. */
    public val Pixel: WearWidgetHost =
      WearWidgetHost(
        "Pixel",
        params(ContainerInfo.CONTAINER_TYPE_SMALL, 200f, 60f, 8f, 8f, 26f),
        params(ContainerInfo.CONTAINER_TYPE_LARGE, 200f, 108f, 8f, 8f, 26f),
      )

    /** Samsung Galaxy Watch: a stadium, its Large container narrower and taller. */
    public val Galaxy: WearWidgetHost =
      WearWidgetHost(
        "Galaxy",
        params(ContainerInfo.CONTAINER_TYPE_SMALL, 200f, 60f, 15f, 8f, 999f),
        params(ContainerInfo.CONTAINER_TYPE_LARGE, 160f, 136f, 35f, 16f, 999f),
      )

    public val All: List<WearWidgetHost> = listOf(Pixel, Galaxy)
  }
}

/**
 * Previews [widget] in its [containerType] on every host in [hosts], side by side: each a round
 * black watch screen with the widget, framed as that host frames it, in the middle.
 */
@SuppressLint("RestrictedApi")
@Composable
public fun WearWidgetHostsPreview(
  widget: GlanceWearWidget,
  containerType: Int,
  hosts: List<WearWidgetHost> = WearWidgetHost.All,
) {
  Row(
    modifier = Modifier.background(Color(0xFF202124)).padding(8.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    hosts.forEach { host -> HostScreen(widget, host, host.params(containerType)) }
  }
}

@SuppressLint("RestrictedApi")
@Composable
private fun HostScreen(widget: GlanceWearWidget, host: WearWidgetHost, params: WearWidgetParams) {
  val context = LocalContext.current
  val document =
    remember(widget, params, context) {
      val rcDocument = runBlocking {
        widget.provideWidgetData(context, params).captureRawContent(context, params).rcDocument
      }
      WearWidgetIrSidecar.offer(rcDocument)
      rcDocument
    }
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Box(
      modifier = Modifier.size(227.dp).clip(CircleShape).background(Color.Black),
      contentAlignment = Alignment.Center,
    ) {
      val playerModifier =
        Modifier.width((params.widthDp + 2f * params.horizontalPaddingDp).dp)
          .height((params.heightDp + 2f * params.verticalPaddingDp).dp)
      HaRcComposePlayer(
        documentBytes = document,
        modifier = playerModifier,
        fallback = {
          WrapAdaptiveRemoteDocumentPlayer(documentBytes = document, modifier = playerModifier)
        },
      )
    }
    Text(host.name, color = Color(0xFFBDC1C6), fontSize = 11.sp, modifier = Modifier.padding(4.dp))
  }
}
