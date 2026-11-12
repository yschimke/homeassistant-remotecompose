@file:Suppress("COMPOSE_APPLIER_CALL_MISMATCH", "RestrictedApi")

package ee.schimke.ha.cards

import androidx.compose.remote.creation.compose.action.hostAction
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.state.rb
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.remote.creation.profile.RcPlatformProfiles
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.remote.material3.RemoteButton
import androidx.wear.compose.remote.material3.RemoteCard
import androidx.wear.compose.remote.material3.RemoteColorScheme
import androidx.wear.compose.remote.material3.RemoteMaterialTheme
import androidx.wear.compose.remote.material3.RemoteSwitchButton
import androidx.wear.compose.remote.material3.RemoteText
import ee.schimke.composeai.preview.CatalogComponent
import kotlinx.coroutines.runBlocking

// The stickers of Wear's Remote Material 3 components. That library is compiled against AndroidX's
// own creation API (`androidx.compose.remote.creation.compose`), so these are written and captured
// with it, where every other sticker uses rc-players' Compose Multiplatform creation API
// ([HaCardSticker]). The two share a document format, and so a player and a sidecar
// ([PlaySticker]).

private const val NO_REMOTE_M3_KIT =
  "No published design kit covers Home Assistant cards in Remote Compose."

private val HaPrimaryText = Color(0xFF141414).rc

/**
 * [HaCardSticker] for Remote Material 3 content: the same frame, captured with AndroidX's creation
 * API under its player's profile. The backdrop is the catalog theme's dashboard colour as a
 * constant, not the named role the HA stickers draw.
 */
@Composable
internal fun RemoteMaterial3Sticker(content: @Composable @RemoteComposable () -> Unit) {
  val context = LocalContext.current
  val theme = LocalHaCatalogTheme.current
  val bytes: ByteArray? =
    remember(content, theme) {
      runCatching {
        runBlocking {
          captureSingleRemoteDocument(context = context, profile = RcPlatformProfiles.ANDROIDX) {
              RemoteMaterialTheme(colorScheme = RemoteColorScheme(theme.asWearColorScheme())) {
                RemoteBox(
                  modifier =
                    RemoteModifier.fillMaxSize()
                      .background(theme.dashboardBackground.rc)
                      .padding(8.rdp),
                  contentAlignment = RemoteAlignment.Center,
                  content = content,
                )
              }
            }
            .bytes
        }
      }
        .getOrNull()
    }
  PlaySticker(bytes)
}

@CatalogComponent(
  id = "Remote/Card",
  group = "Containment",
  noReference = NO_REMOTE_M3_KIT,
  caption = "Remote Material 3's RemoteCard, as remote-m3 offers it.",
)
@Preview(widthDp = 360, heightDp = 96)
@Composable
fun RemoteCardSticker() = RemoteMaterial3Sticker {
  RemoteCard(
    onClick = hostAction("ha-cards.sticker".rs),
    modifier = RemoteModifier.fillMaxWidth(),
    content = { RemoteText("Good evening".rs) },
  )
}

// ── Buttons and selection ──────────────────────────────────────────────────────────────────────

@CatalogComponent(
  id = "Remote/Button",
  group = "Buttons",
  noReference = NO_REMOTE_M3_KIT,
  caption = "Remote Material 3's RemoteButton, as remote-m3 offers it: here, running a scene.",
)
@Preview(widthDp = 216, heightDp = 76)
@Composable
fun RemoteButtonSticker() = RemoteMaterial3Sticker {
  RemoteButton(onClick = hostAction("ha-cards.sticker".rs)) { RemoteText("Movie night".rs) }
}

@CatalogComponent(
  id = "Remote/SwitchButton",
  group = "Selection buttons",
  noReference = NO_REMOTE_M3_KIT,
  caption = "Remote Material 3's RemoteSwitchButton: a labelled switch, checked.",
)
@Preview(widthDp = 216, heightDp = 76)
@Composable
fun RemoteSwitchButtonSticker() = RemoteMaterial3Sticker {
  RemoteSwitchButton(
    checked = true.rb,
    onCheckedChange = hostAction("ha-cards.sticker".rs),
    modifier = RemoteModifier.fillMaxWidth(),
    label = { RemoteText("Porch light".rs, color = HaPrimaryText) },
  )
}

// ── Text ───────────────────────────────────────────────────────────────────────────────────────

@CatalogComponent(
  id = "Remote/Text",
  group = "Text",
  noReference = NO_REMOTE_M3_KIT,
  caption = "Remote Material 3's RemoteText, set by size: the text every template binds.",
)
@Preview(widthDp = 216, heightDp = 56)
@Composable
fun RemoteTextSticker() = RemoteMaterial3Sticker {
  RemoteText("Living room".rs, color = HaPrimaryText, fontSize = 18.rsp)
}
