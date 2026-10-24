package ee.schimke.ha.rc

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.components.ProvideHaTheme
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.haThemeFor
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A card on screen counts each rebuild of its document, by type, reason and cost. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CardRebuildsTest {

  @get:Rule val compose = createComposeRule()

  @AfterTest fun reset() = CardRebuilds.drain().let {}

  private fun camera(state: String, picture: String) =
    HaSnapshot(
      states =
        mapOf(
          "camera.yard" to
            EntityState(
              "camera.yard",
              state,
              JsonObject(mapOf("entity_picture" to JsonPrimitive(picture))),
            )
        )
    )

  @Test
  fun aNewPictureIsARebuildAndAStateChangeIsNot() {
    CardRebuilds.drain()
    val card =
      CardConfig(
        "picture-entity",
        buildJsonObject {
          put("type", "picture-entity")
          put("entity", "camera.yard")
        },
      )
    val registry = defaultRegistry()
    val theme = haThemeFor(ThemeStyle.TerrazzoHome, false)
    var snapshot by mutableStateOf(camera("idle", "/api/camera_proxy/camera.yard?token=a"))
    var key by mutableStateOf<Any>("v1")

    compose.setContent {
      CachedCardPreview(
        cacheKey = key,
        card = card,
        snapshot = snapshot,
        rebuildReason = { _, _ -> "data" },
      ) {
        ProvideCardRegistry(registry) {
          ProvideHaTheme(theme) { RenderChild(card, snapshot, RemoteModifier.fillMaxWidth()) }
        }
      }
    }
    compose.waitForIdle()
    // Coming into view is not a rebuild.
    assertEquals(emptyList(), CardRebuilds.peek())

    // A value the card binds: no new document.
    snapshot = camera("recording", "/api/camera_proxy/camera.yard?token=a")
    compose.waitForIdle()
    assertEquals(emptyList(), CardRebuilds.peek())

    // A rotated token: a new document for the picture, captured again on the converter path.
    snapshot = camera("recording", "/api/camera_proxy/camera.yard?token=b")
    compose.waitForIdle()
    assertEquals(
      listOf(
        CardRebuilds.Count(
          "picture-entity",
          CardRebuilds.REASON_PICTURE,
          CardRebuilds.Cost.Encode,
          1,
        )
      ),
      CardRebuilds.peek(),
    )

    // The caller's own key moving: its reason.
    CardRebuilds.drain()
    key = "v2"
    compose.waitForIdle()
    assertEquals("data", CardRebuilds.peek().single().reason)
  }

  @Test
  fun withStablePicturesANewPictureIsNoRebuild() {
    CardRebuilds.drain()
    val card =
      CardConfig(
        "picture-entity",
        buildJsonObject {
          put("type", "picture-entity")
          put("entity", "camera.yard")
        },
      )
    val registry = defaultRegistry()
    val theme = haThemeFor(ThemeStyle.TerrazzoHome, false)
    var snapshot by mutableStateOf(camera("idle", "/api/camera_proxy/camera.yard?token=a"))
    val documents = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())

    compose.setContent {
      CachedCardPreview(
        cacheKey = "v1",
        card = card,
        snapshot = snapshot,
        stablePictures = true,
        onDocument = { documents += it },
      ) {
        ProvideCardRegistry(registry) {
          ProvideHaTheme(theme) {
            RenderChild(
              card,
              StablePictures.snapshot(card, snapshot),
              RemoteModifier.fillMaxWidth(),
            )
          }
        }
      }
    }
    compose.waitForIdle()
    val first = documents.last()

    // A rotated token loads in place: the same document, nothing rebuilt.
    snapshot = camera("idle", "/api/camera_proxy/camera.yard?token=b")
    compose.waitForIdle()
    assertEquals(emptyList(), CardRebuilds.peek())
    kotlin.test.assertSame(first, documents.last())
  }

  @Test
  fun anotherTemplatesBytesCostADecodeAndTheSameTemplateCostsNothing() {
    CardRebuilds.drain()
    val context =
      androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    fun template(text: String): CardTemplatePlayback {
      val bytes =
        kotlinx.coroutines
          .runBlocking {
            androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument(
              context = context
            ) {
              androidx.compose.remote.creation.compose.layout.RemoteText(text = text.rs)
            }
          }
          .bytes
      return CardTemplatePlayback(
        bytes,
        { _, _ -> CardSnapshotBindings(emptyMap(), emptyMap(), emptyMap(), emptyMap()) },
        ee.schimke.ha.rc.components.HaAction.None,
      )
    }
    val disarmed = template("disarmed")
    val armed = template("armed")
    val card = CardConfig("alarm-panel", buildJsonObject { put("type", "alarm-panel") })
    var current by mutableStateOf(disarmed)
    var key by mutableStateOf<Any>("disarmed")

    compose.setContent {
      CachedCardPreview(cacheKey = key, card = card, snapshot = HaSnapshot(), template = current) {}
    }
    compose.waitForIdle()

    current = armed
    key = "armed"
    compose.waitForIdle()
    assertEquals(CardRebuilds.Cost.Decode, CardRebuilds.drain().single().cost)

    // The same template under a new key: the same bytes, new bindings only.
    key = "armed-again"
    compose.waitForIdle()
    assertEquals(CardRebuilds.Cost.None, CardRebuilds.drain().single().cost)
  }
}
