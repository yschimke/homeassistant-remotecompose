package ee.schimke.terrazzo.wear.widget

import androidx.glance.wear.core.ContainerInfo
import androidx.glance.wear.core.WearWidgetParams
import androidx.glance.wear.core.WidgetInstanceId
import ee.schimke.terrazzo.wear.sync.WearOfflineStore
import ee.schimke.terrazzo.wearsync.proto.CardSummary
import ee.schimke.terrazzo.wearsync.proto.EntityValue
import ee.schimke.terrazzo.wearsync.proto.LiveValues
import ee.schimke.terrazzo.wearsync.proto.PinnedCard
import ee.schimke.terrazzo.wearsync.proto.PinnedCardSet
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A placed widget draws the card chosen for it on the watch, and one with no card chosen asks for
 * one: the phone assigns no widget slots any more.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardWidgetChoiceTest {
  private val context = RuntimeEnvironment.getApplication()

  private fun params(id: Int, containerType: Int, heightDp: Float) =
    WearWidgetParams(
      instanceId = WidgetInstanceId("widgets", id),
      containerType = containerType,
      widthDp = 200f,
      heightDp = heightDp,
      verticalPaddingDp = 8f,
      horizontalPaddingDp = 8f,
      cornerRadiusDp = 26f,
    )

  private val small = params(1, ContainerInfo.CONTAINER_TYPE_SMALL, 60f)
  private val large = params(2, ContainerInfo.CONTAINER_TYPE_LARGE, 112f)

  private val kitchen =
    CardSummary(
      type = "tile",
      title = "Kitchen",
      primaryEntityId = "light.kitchen",
      rawJson = """{"type":"tile","entity":"light.kitchen","name":"Kitchen"}""",
    )

  @Before
  @After
  fun clearStore() {
    File(context.filesDir, "terrazzo/wear").deleteRecursively()
  }

  private fun render(params: WearWidgetParams): String = runBlocking {
    val store = WearOfflineStore(context)
    val chosen = WearWidgetCardStore(context).read(params.instanceId.id)
    val bytes =
      slotWidgetDataFromStore(context, store, chosen)
        .captureRawContent(context, params, false)
        .rcDocument
    String(bytes, Charsets.ISO_8859_1)
  }

  @Test
  fun aWidgetDrawsItsChosenCardAndOneWithoutAsksForOne() {
    val store = WearOfflineStore(context)
    store.writePinned(PinnedCardSet(cards = listOf(PinnedCard(card = kitchen, cardKey = "pin"))))
    store.writeValues(
      LiveValues(
        values = mapOf("light.kitchen" to EntityValue(state = "on", friendlyName = "Kitchen"))
      )
    )

    // Nothing chosen yet, at either size: where to choose one.
    for (params in listOf(small, large)) {
      val unchosen = render(params)
      assertTrue(CHOOSE_CARD_PLACEHOLDER in unchosen, "an unconfigured widget asks for a card")
      assertFalse("Kitchen" in unchosen)
    }

    // The small widget chooses the kitchen; the large one still has nothing.
    WearWidgetCardStore(context).write(small.instanceId.id, WearWidgetCard("pin", kitchen))
    val chosen = render(small)
    assertTrue("Kitchen" in chosen, "the chosen card is drawn")
    assertFalse(CHOOSE_CARD_PLACEHOLDER in chosen)
    assertTrue(CHOOSE_CARD_PLACEHOLDER in render(large), "each widget has its own card")

    // Unpinned on the phone: the widget keeps drawing the copy it chose.
    store.writePinned(PinnedCardSet())
    assertTrue("Kitchen" in render(small), "an unpinned card keeps drawing")

    // Removed from the watch: its choice is forgotten.
    WearWidgetCardStore(context).remove(small.instanceId.id)
    assertTrue(CHOOSE_CARD_PLACEHOLDER in render(small))
  }
}
