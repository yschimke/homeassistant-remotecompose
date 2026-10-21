package ee.schimke.terrazzo.wear.sync

import android.content.Context
import android.util.Log
import ee.schimke.ha.rc.CardDesignTemplates
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.cards.shutter.withEnhancedShutter
import ee.schimke.ha.rc.preloadForComposePlayer
import ee.schimke.terrazzo.wear.data.WearPrefs
import ee.schimke.terrazzo.wear.ui.TopLevelEntry
import ee.schimke.terrazzo.wear.ui.WearCardLook
import ee.schimke.terrazzo.wear.ui.decodeWearCard
import ee.schimke.terrazzo.wear.ui.mergeOrdered
import ee.schimke.terrazzo.wear.ui.wearDesignKey
import ee.schimke.terrazzo.wear.ui.wearSnapshot
import ee.schimke.terrazzo.wear.ui.wearTemplateBytes
import ee.schimke.terrazzo.wear.widget.wearWidgetCardStyle
import ee.schimke.terrazzo.wearsync.proto.CardSummary
import ee.schimke.terrazzo.wearsync.proto.PinnedCardSet
import ee.schimke.terrazzo.wearsync.proto.PinnedSectionSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Gets the watch's pinned cards ready before the app shows them. Each card plays a design template
 * from the local library, which a cold process otherwise captures (a full Remote Compose capture)
 * on the main thread as the card first composes: the slow part of opening the app. This captures
 * those templates in the background into the library's on-disk store, then folds each for this
 * display and decodes it for the player, as [ee.schimke.terrazzo.wear.ui.WearRemoteCard] will.
 *
 * Run when the phone sends new pins or sections (by [WearDataDeletionListenerService], while the
 * app is closed), when the app is updated (whose templates are captured afresh), and as the app
 * opens, where it races the first frame: a card the warm-up is still capturing waits for it rather
 * than capturing twice. Templates already on disk are only read, folded and decoded.
 */
internal object WearCardWarmup {
  private val registry by lazy { defaultRegistry().withEnhancedShutter() }

  /** One warm-up at a time: a second waits, then finds the first's templates ready. */
  private val running = Mutex()

  suspend fun run(context: Context) =
    withContext(Dispatchers.Default) {
      running.withLock {
        val app = context.applicationContext
        val store = WearOfflineStore(app)
        val prefs = WearPrefs(app)
        val look = WearCardLook(prefs.themeStyle.first(), prefs.cardStyle.first())
        val snapshot = wearSnapshot(store.readValues()?.values.orEmpty())
        val density = app.resources.displayMetrics.density
        val fontScale = app.resources.configuration.fontScale
        for (summary in warmupCards(store.readPinned(), store.readSections())) {
          val card = decodeWearCard(summary.rawJson) ?: continue
          val style = wearWidgetCardStyle(summary.cardStyleOverride, look.style)
          val design = CardDesignTemplates.designCard(card, snapshot, registry)
          val key = wearDesignKey(design, snapshot, look, style, registry) ?: continue
          runCatching {
            preloadForComposePlayer(wearTemplateBytes(app, key, registry, density, fontScale))
          }
            .onFailure { Log.w(TAG, "warm-up failed for ${summary.type}", it) }
        }
      }
    }

  private const val TAG = "WearCardWarmup"
}

/**
 * The cards to warm, most urgent first: the pinned cards in the top-level list's order (what the
 * app opens on), then the cards of the pinned sections, which show when one is opened.
 */
internal fun warmupCards(pinned: PinnedCardSet?, sections: PinnedSectionSet?): List<CardSummary> {
  val entries = mergeOrdered(pinned?.cards.orEmpty(), sections?.sections.orEmpty())
  val topLevel = entries.filterIsInstance<TopLevelEntry.Card>().map { it.card.card }
  val inSections = entries.filterIsInstance<TopLevelEntry.Section>().flatMap { it.section.cards }
  return (topLevel + inSections).distinct()
}
