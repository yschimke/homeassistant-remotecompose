@file:Suppress("RestrictedApi", "RestrictedApiAndroidX")

package ee.schimke.terrazzo.wear.widget

import android.app.PendingIntent
import android.os.Bundle
import androidx.compose.remote.creation.compose.action.PendingIntentAction
import androidx.compose.remote.creation.compose.action.pendingIntentAction
import androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.state.rdp
import androidx.glance.wear.WearWidgetBrush
import androidx.glance.wear.WearWidgetDocument
import androidx.glance.wear.core.ContainerInfo
import androidx.glance.wear.core.WearWidgetParams
import androidx.glance.wear.core.WearWidgetRawContent
import androidx.glance.wear.core.WidgetInstanceId
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcHostNamedAction
import ee.schimke.composeai.rcplayer.protocol.RcHostNamedActionValue
import ee.schimke.composeai.rcplayer.protocol.RcIntegerConstant
import ee.schimke.composeai.rcplayer.protocol.RcTextData
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.ha.rc.androidXExperimentalWrap
import ee.schimke.ha.rc.components.HA_ACTION_NAME
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.toNamedRemoteAction
import ee.schimke.terrazzo.wear.WearMainActivity
import ee.schimke.terrazzo.wear.sync.WearOfflineStore
import ee.schimke.terrazzo.wearsync.proto.CardSummary
import ee.schimke.terrazzo.wearsync.proto.EntityValue
import ee.schimke.terrazzo.wearsync.proto.LiveValues
import ee.schimke.terrazzo.wearsync.proto.PinnedCard
import ee.schimke.terrazzo.wearsync.proto.PinnedCardSet
import ee.schimke.terrazzo.wearsync.proto.PinnedSection
import ee.schimke.terrazzo.wearsync.proto.PinnedSectionSet
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A tap on a Wear widget opens the Wear app on its card ([WearWidgetTaps]): every `ha` tap in the
 * widget's document becomes Glance Wear's own `SendPendingIntent` action, naming the widget's one
 * `PendingIntent` in its extras.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WearWidgetTapsTest {
  private val context = RuntimeEnvironment.getApplication()

  @Before
  @After
  fun clearStore() {
    File(context.filesDir, "terrazzo/wear").deleteRecursively()
  }

  private fun capture(content: @androidx.compose.runtime.Composable () -> Unit) = runBlocking {
    captureSingleRemoteDocument(
      context = context,
      creationDisplayInfo = RemoteCreationDisplayInfo(200, 112, 160),
      profile = androidXExperimentalWrap,
      content = content,
    )
  }

  private fun decode(bytes: ByteArray) = RcDocumentCodec.decode(bytes, RcWireLimits())

  /** Each named host action's name and, for an integer one, its value. */
  private fun RcDocument.namedTaps(): List<Pair<String?, Int?>> {
    val texts = operations.filterIsInstance<RcTextData>().associate { it.id to it.text }
    val ints = operations.filterIsInstance<RcIntegerConstant>().associate { it.id to it.value }
    return operations.filterIsInstance<RcHostNamedAction>().map { action ->
      texts[action.nameTextId] to
        (action.value as? RcHostNamedActionValue.IntegerValue)?.let { ints[it.valueId] }
    }
  }

  @Test
  fun everyHaTapSendsTheWidgetsPendingIntentAsPendingIntentActionWritesIt() {
    val document = capture {
      RemoteBox(
        RemoteModifier.size(80.rdp)
          .clickable(HaAction.Toggle("light.kitchen").toNamedRemoteAction()!!)
      ) {
        RemoteBox(
          RemoteModifier.size(20.rdp)
            .clickable(HaAction.MoreInfo("sensor.temperature").toNamedRemoteAction()!!)
        ) {}
      }
    }
      .bytes
    assertEquals(
      listOf(HA_ACTION_NAME, HA_ACTION_NAME),
      decode(document).namedTaps().map { it.first },
    )

    val rewritten = decode(assertNotNull(WearWidgetTaps.rewrite(document)))
    val sendIndex = PendingIntentAction.ACTION_NAME to WearWidgetTaps.OPEN_APP_INDEX
    assertEquals(listOf(sendIndex, sendIndex), rewritten.namedTaps())

    // What Glance Wear itself makes of a `pendingIntentAction` tap in a Wear widget: the same name
    // and index, and the PendingIntent at that index in the extras, under the same key.
    val reference = runBlocking {
      WearWidgetDocument(WearWidgetBrush.Companion) {
          val action = pendingIntentAction { openIntent() }
          RemoteBox(RemoteModifier.size(80.rdp).clickable(action)) {}
        }
        .captureRawContent(context, params(1), false)
    }
    assertEquals(listOf(sendIndex), decode(reference.rcDocument).namedTaps())
    val intents = reference.extras.getParcelable<Bundle>(WearWidgetTaps.PENDING_INTENTS)
    assertEquals(
      openIntent(),
      intents?.getParcelable<PendingIntent>(WearWidgetTaps.OPEN_APP_INDEX.toString()),
    )
  }

  @Test
  fun aDocumentWithoutTapsIsUnchanged() {
    val document = capture { RemoteBox(RemoteModifier.size(20.rdp)) {} }.bytes
    assertTrue(document.contentEquals(WearWidgetTaps.rewrite(document)))
  }

  @Test
  fun theWidgetsExtrasCarryItsOpenAppIntentAndKeepTheRest() {
    val document = capture {
      RemoteBox(
        RemoteModifier.size(80.rdp)
          .clickable(HaAction.Toggle("light.kitchen").toNamedRemoteAction()!!)
      ) {}
    }
      .bytes
    val intent = WearWidgetTaps.openCardIntent(context, 7, "pin")
    val content =
      WearWidgetTaps.openingApp(
        WearWidgetRawContent(document, Bundle().apply { putString("kept", "yes") }, "Kitchen"),
        intent,
      )
    assertEquals("yes", content.extras.getString("kept"))
    assertEquals("Kitchen", content.widgetTitle)
    val pending = content.extras.getParcelable<Bundle>(WearWidgetTaps.PENDING_INTENTS)
    assertEquals(intent, pending?.getParcelable<PendingIntent>("0"))
    val opened = shadowOf(intent).savedIntent
    assertEquals(WearMainActivity.ACTION_OPEN_WIDGET_CARD, opened.action)
    assertEquals("pin", opened.getStringExtra(WearMainActivity.EXTRA_WIDGET_CARD_KEY))
  }

  @Test
  fun aPlacedWidgetsCardTapsOpenTheAppOnIt() {
    val kitchen =
      CardSummary(
        type = "tile",
        title = "Kitchen",
        primaryEntityId = "light.kitchen",
        rawJson = """{"type":"tile","entity":"light.kitchen","name":"Kitchen"}""",
      )
    val store = WearOfflineStore(context)
    store.writePinned(PinnedCardSet(cards = listOf(PinnedCard(card = kitchen, cardKey = "pin"))))
    store.writeValues(
      LiveValues(
        values = mapOf("light.kitchen" to EntityValue(state = "on", friendlyName = "Kitchen"))
      )
    )
    val params = params(3)
    val content = runBlocking {
      slotWidgetDataFromStore(context, store, WearWidgetCard("pin", kitchen))
        .openingApp("pin")
        .captureRawContent(context, params, false)
    }
    val taps = decode(content.rcDocument).namedTaps()
    assertTrue(taps.isNotEmpty(), "the tile is tappable")
    assertTrue(
      taps.all { it == PendingIntentAction.ACTION_NAME to WearWidgetTaps.OPEN_APP_INDEX },
      "taps: $taps",
    )
    val intent =
      content.extras
        .getParcelable<Bundle>(WearWidgetTaps.PENDING_INTENTS)
        ?.getParcelable<PendingIntent>("0")
    assertEquals(
      "pin",
      shadowOf(assertNotNull(intent))
        .savedIntent
        .getStringExtra(WearMainActivity.EXTRA_WIDGET_CARD_KEY),
    )
  }

  @Test
  fun aSectionCardOpensItsSectionAndAPinnedCardThePinnedList() {
    val card = CardSummary(type = "tile", rawJson = """{"type":"tile","entity":"light.hall"}""")
    val section = PinnedSection(sectionKey = "hall", title = "Hall", cards = listOf(card))
    val sections = PinnedSectionSet(sections = listOf(section))
    val key = widgetCardOptions(PinnedCardSet(), sections).single().cardKey
    assertEquals(section, widgetCardSection(key, sections))
    assertNull(widgetCardSection("pin", sections))
    assertNull(widgetCardSection(key, null))
  }

  private fun params(id: Int) =
    WearWidgetParams(
      instanceId = WidgetInstanceId("widgets", id),
      containerType = ContainerInfo.CONTAINER_TYPE_LARGE,
      widthDp = 200f,
      heightDp = 112f,
      verticalPaddingDp = 8f,
      horizontalPaddingDp = 8f,
      cornerRadiusDp = 26f,
    )

  private fun openIntent(): PendingIntent = WearWidgetTaps.openCardIntent(context, 1, "pin")
}
