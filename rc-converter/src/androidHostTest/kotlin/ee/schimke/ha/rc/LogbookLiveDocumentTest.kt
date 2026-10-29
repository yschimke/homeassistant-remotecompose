package ee.schimke.ha.rc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.ProvideHaTheme
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.haThemeFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The logbook is captured once and follows its entities through bindings alone
 * (live-card-updates.md, path 1). Its "when" is Home Assistant's `last_changed`, pushed as is
 * (`<id>.last_changed`); the document turns it into "5 min ago" against the player's clock, so it
 * must read as [formatRelativeTime] says, and move on by itself as time passes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w420dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LogbookLiveDocumentTest {

  @get:Rule val compose = createComposeRule()

  private val registry = defaultRegistry()
  private val card =
    CardConfig(
      "logbook",
      Json.parseToJsonElement("""{"type":"logbook","entities":["$DOOR"]}""").jsonObject,
    )

  private fun door(state: String, changed: Instant?) =
    HaSnapshot(
      states =
        mapOf(
          DOOR to
            EntityState(
              DOOR,
              state,
              JsonObject(
                mapOf(
                  "friendly_name" to JsonPrimitive("Front door"),
                  "device_class" to JsonPrimitive("door"),
                )
              ),
              lastChanged = changed,
            )
        )
    )

  private var snapshot by mutableStateOf(door("off", null))
  private var clock: HaClock by mutableStateOf(SystemHaClock)
  private val documents = HashSet<Int>()

  private fun play() {
    val theme = haThemeFor(ThemeStyle.TerrazzoHome, false)
    val cache = InMemoryCardDocumentCache()
    compose.setContent {
      CompositionLocalProvider(LocalCardDocumentCache provides cache, LocalHaClock provides clock) {
        Box(Modifier.background(Color.White).width(381.dp)) {
          CachedCardPreview(
            cacheKey = "logbook-live",
            profile = androidXExperimentalWrap,
            card = card,
            snapshot = snapshot,
            onDocument = { documents += it.contentHashCode() },
          ) {
            ProvideCardRegistry(registry) {
              ProvideHaTheme(theme) { RenderChild(card, snapshot, RemoteModifier.fillMaxWidth()) }
            }
          }
        }
      }
    }
    compose.waitForIdle()
  }

  /** The ages to show, each well inside its label so the test's own run time can't tip it over. */
  private val ages: List<Duration> =
    listOf(
      5.seconds,
      2.minutes,
      150.seconds, // a half: HA's Math.round makes it 3 min
      40.minutes,
      50.minutes,
      3.hours,
      (21.hours + 40.minutes),
      2.days,
      (6.days + 3.hours),
      10.days,
      400.days,
    )

  @Test
  fun everyAgeReadsAsHomeAssistantSaysWithoutReEncoding() {
    assertNull(registry.cardDataSignature(card, snapshot), "a logbook has no re-encode signature")
    play()
    val zone = TimeZone.currentSystemDefault()
    for ((i, age) in ages.withIndex()) {
      // The message changes too: `<id>.state` follows the entity in the same document.
      val state = if (i % 2 == 0) "on" else "off"
      val now = Clock.System.now()
      snapshot = door(state, now - age)
      compose.waitForIdle()
      val expected = formatRelativeTime(now - age, now, zone)
      val shown = texts()
      assertTrue(expected in shown, "$age reads $expected: $shown")
      assertTrue((if (state == "on") "Open" else "Closed") in shown, "message for $state: $shown")
    }
    assertEquals(1, documents.size, "one document for every age and state")
  }

  @Test
  fun theLabelMovesOnWithThePlayersClockAlone() {
    // The player reads the time itself: moving its clock on (here a frozen one, swapped) moves the
    // label on, with nothing pushed and no new document.
    val start = Instant.parse("2026-10-29T10:15:20Z")
    clock = FixedHaClock(start)
    snapshot = door("on", start - 42.seconds)
    play()
    assertTrue("just now" in texts(), texts().toString())
    clock = FixedHaClock(start + 10.seconds)
    compose.waitForIdle()
    assertTrue("1 min ago" in texts(), "the label moved on: ${texts()}")
    clock = FixedHaClock(start + 3.hours)
    compose.waitForIdle()
    assertTrue("3 h ago" in texts(), "and on again: ${texts()}")
    assertEquals(1, documents.size, "one document throughout")
  }

  @Test
  fun anEntityWithNoLastChangedShowsNoTime() {
    snapshot = door("on", null)
    play()
    val shown = texts()
    assertTrue("Open" in shown, shown.toString())
    assertTrue(shown.none { it.endsWith(" ago") || it == "just now" }, shown.toString())
  }

  @Test
  fun aClockBehindHomeAssistantReadsAsJustNow() {
    snapshot = door("on", Clock.System.now() + 30.seconds)
    play()
    assertTrue("just now" in texts(), texts().toString())
  }

  /** The texts and content descriptions of every node the player shows. */
  private fun texts(): List<String> {
    val root = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode()
    return generateSequence(listOf(root)) { level ->
        level.flatMap(SemanticsNode::children).ifEmpty { null }
      }
      .flatten()
      .flatMap {
        it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } +
          it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
      }
      .toList()
  }

  private companion object {
    const val DOOR = "binary_sensor.front_door"
  }
}
