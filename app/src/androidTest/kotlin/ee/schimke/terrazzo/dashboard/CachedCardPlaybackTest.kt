@file:Suppress("RestrictedApi")

package ee.schimke.terrazzo.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.height
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CachedCardPreview
import ee.schimke.ha.rc.InMemoryCardDocumentCache
import ee.schimke.ha.rc.LocalCardDocumentCache
import ee.schimke.ha.rc.androidXExperimentalWrap
import ee.schimke.ha.rc.components.LiveValues
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the retained AndroidX player, including integer-backed RemoteBoolean writes. */
@RunWith(AndroidJUnit4::class)
class CachedCardPlaybackTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun onOffChangesReuseTheDocumentAndPreserveWrapHeight() {
    val entity = "light.test"
    val card = CardConfig("tile", buildJsonObject { put("entity", entity) })
    fun snapshot(on: Boolean) =
      HaSnapshot(states = mapOf(entity to EntityState(entity, if (on) "on" else "off")))
    val current = mutableStateOf(snapshot(false))
    val documents = mutableListOf<ByteArray>()
    val cache = InMemoryCardDocumentCache()
    compose.setContent {
      CompositionLocalProvider(LocalCardDocumentCache provides cache) {
        Box(Modifier.width(300.dp)) {
          CachedCardPreview(
            cacheKey = "on-off-regression",
            profile = androidXExperimentalWrap,
            modifier = Modifier.testTag("card"),
            card = card,
            snapshot = current.value,
            onDocument = { documents.add(it) },
          ) {
            val on = checkNotNull(LiveValues.isOn(entity, false))
            RemoteBox(
              RemoteModifier.fillMaxWidth()
                .height(64.rdp)
                .background(on.select(Color.Green.rc, Color.Red.rc))
            ) {}
          }
        }
      }
    }
    fun centerColor(): Color {
      val pixels = compose.onNodeWithTag("card").captureToImage().toPixelMap()
      return pixels[pixels.width / 2, pixels.height / 2]
    }
    compose.onNodeWithTag("card").assertHeightIsEqualTo(64.dp)
    compose.waitUntil(5_000) { centerColor() == Color.Red }
    assertFalse(documents.isEmpty())
    val first = documents.first()
    compose.runOnIdle { current.value = snapshot(true) }
    compose.waitUntil(5_000) { centerColor() == Color.Green }
    compose.runOnIdle { current.value = snapshot(false) }
    compose.waitUntil(5_000) { centerColor() == Color.Red }
    compose.onNodeWithTag("card").assertHeightIsEqualTo(64.dp)
    compose.runOnIdle { documents.forEach { assertArrayEquals(first, it) } }
  }
}
