package ee.schimke.terrazzo.wear.sync

import ee.schimke.ha.rc.CardDesignLibrary
import ee.schimke.ha.rc.CardDesignTemplates
import ee.schimke.ha.rc.ContainerTint
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.terrazzo.wear.ui.WearCardLook
import ee.schimke.terrazzo.wear.ui.decodeWearCard
import ee.schimke.terrazzo.wear.ui.wearAppTemplate
import ee.schimke.terrazzo.wear.ui.wearSnapshot
import ee.schimke.terrazzo.wear.widget.WearWidgetFrame
import ee.schimke.terrazzo.wear.widget.neutralTemplateCaptures
import ee.schimke.terrazzo.wear.widget.wearAppContainer
import ee.schimke.terrazzo.wear.widget.wearWidgetCardStyle
import ee.schimke.terrazzo.wearsync.proto.CardSummary
import ee.schimke.terrazzo.wearsync.proto.DashboardData
import ee.schimke.terrazzo.wearsync.proto.EntityValue
import ee.schimke.terrazzo.wearsync.proto.LiveValues
import ee.schimke.terrazzo.wearsync.proto.PinnedCard
import ee.schimke.terrazzo.wearsync.proto.PinnedCardSet
import ee.schimke.terrazzo.wearsync.proto.PinnedSection
import ee.schimke.terrazzo.wearsync.proto.PinnedSectionSet
import ee.schimke.terrazzo.wearsync.proto.WearSyncPaths
import ee.schimke.terrazzo.wearsync.proto.encodeProto
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
import org.robolectric.annotation.Config

/** The pinned cards' templates are ready, and the phone's last pins kept, before the app opens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WearCardWarmupTest {
  private val context = RuntimeEnvironment.getApplication()

  private fun summary(type: String, entity: String, style: String = "") =
    CardSummary(
      type = type,
      primaryEntityId = entity,
      rawJson = """{"type":"$type","entity":"$entity"}""",
      cardStyleOverride = style,
    )

  @Before
  @After
  fun clearStore() {
    File(context.filesDir, "terrazzo/wear").deleteRecursively()
    CardDesignLibrary.store = null
  }

  @Test
  fun topLevelCardsWarmFirstInTheirListOrderThenSectionCards() {
    val first = summary("tile", "light.hall")
    val second = summary("tile", "light.porch")
    val inSection = summary("tile", "switch.pump")
    val cards =
      warmupCards(
        PinnedCardSet(
          listOf(
            PinnedCard(card = second, cardKey = "b", orderIndex = 2),
            PinnedCard(card = first, cardKey = "a", orderIndex = 0),
          )
        ),
        PinnedSectionSet(
          listOf(PinnedSection(cards = listOf(inSection, first), sectionKey = "s", orderIndex = 1))
        ),
      )
    // A card pinned on its own and in a section is warmed once.
    assertEquals(listOf(first, second, inSection), cards)
    assertEquals(emptyList(), warmupCards(null, null))
  }

  @Test
  fun changedPinsAndDashboardsAreKeptAndWarmTheCards() {
    val store = WearOfflineStore(context)
    val pinned = PinnedCardSet(listOf(PinnedCard(card = summary("tile", "light.hall"))), 7L)
    val dashboard = DashboardData(urlPath = "energy", title = "Energy")
    store.persistChanged(WearSyncPaths.PINNED, encodeProto(pinned))
    store.persistChanged(WearSyncPaths.dashboardPath("energy"), encodeProto(dashboard))
    // The service isn't woken for values; a stray one is not this function's to keep.
    store.persistChanged(WearSyncPaths.VALUES, encodeProto(LiveValues(capturedAtMs = 1L)))
    assertEquals(pinned, store.readPinned())
    assertEquals(listOf(dashboard), store.readAllDashboards())
    assertNull(store.readValues())

    // An older copy (another node's, or a late event) never replaces a newer one.
    val older = PinnedCardSet(listOf(PinnedCard(card = summary("tile", "light.old"))), 3L)
    store.persistChanged(WearSyncPaths.PINNED, encodeProto(older))
    assertEquals(pinned, store.readPinned())
    val olderDashboard = dashboard.copy(title = "Old", updatedAtMs = -1L)
    store.persistChanged(WearSyncPaths.dashboardPath("energy"), encodeProto(olderDashboard))
    assertEquals(listOf(dashboard), store.readAllDashboards())
    val newer = pinned.copy(updatedAtMs = 8L, cards = emptyList())
    store.persistChanged(WearSyncPaths.PINNED, encodeProto(newer))
    assertEquals(newer, store.readPinned())

    assertTrue(warmsOnChange(listOf(WearSyncPaths.PINNED)))
    assertTrue(warmsOnChange(listOf(WearSyncPaths.SECTIONS)))
    assertFalse(warmsOnChange(listOf(WearSyncPaths.SLOTS, WearSyncPaths.dashboardPath("energy"))))
  }

  @Test
  fun warmingCapturesEachPinnedCardsWidgetTemplateBeforeTheAppAsks() {
    // A card style and domain no other test captures, so this process hasn't got its template.
    val pinnedCard = summary("tile", "light.warmup_lamp", style = "console")
    val offline = WearOfflineStore(context)
    offline.writePinned(PinnedCardSet(listOf(PinnedCard(card = pinnedCard))))
    offline.writeValues(
      LiveValues(mapOf("light.warmup_lamp" to EntityValue(state = "on")), capturedAtMs = 1L)
    )
    val before = neutralTemplateCaptures.get()

    runBlocking { WearCardWarmup.run(context, syncFirst = false) }
    assertEquals(before + 1, neutralTemplateCaptures.get())

    // The document the app's card will ask for: the watch's default look, the card's own style.
    val registry = defaultRegistry()
    val card = assertNotNull(decodeWearCard(pinnedCard.rawJson))
    val snapshot = wearSnapshot(offline.readValues()!!.values)
    val frame = WearWidgetFrame.forContainer(context, wearAppContainer(card, snapshot, registry))
    val document = runBlocking {
      wearAppTemplate(
        context,
        CardDesignTemplates.designCard(card, snapshot, registry),
        snapshot,
        WearCardLook(ThemeStyle.TerrazzoHome, "classic"),
        wearWidgetCardStyle(pinnedCard.cardStyleOverride, "classic"),
        frame,
        ContainerTint.argbFor(card, snapshot),
        registry,
      )
    }
    assertNotNull(document)
    // The app plays what the warm-up captured, without capturing again.
    assertEquals(before + 1, neutralTemplateCaptures.get())
  }
}
