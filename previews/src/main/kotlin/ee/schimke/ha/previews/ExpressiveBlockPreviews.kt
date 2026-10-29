@file:Suppress("RestrictedApi")

package ee.schimke.ha.previews

import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.ha.rc.androidXExperimental
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ProvideHaUberTheme
import ee.schimke.ha.rc.components.currentRemoteHaTheme
import ee.schimke.rc.m3.LocalRcAnimationsEnabled
import ee.schimke.rc.m3.RcM3ProgressColors
import ee.schimke.rc.m3.RemoteM3LoadingIndicator
import ee.schimke.rc.m3.RemoteM3ShapeMorph
import ee.schimke.rc.m3.RemoteM3WavyProgress
import ee.schimke.rc.m3.RemoteM3WavyProgressRing

/**
 * The M3 Expressive building blocks in `rc-material3-motion`, frozen at fixed fractions. They are
 * motion, so a still proves only the shapes: `ExpressiveBlocksPlaybackTest` plays them. Animations
 * are off, so the loops on the player's clock draw their first frame, not wherever a capture lands.
 */
@Composable
private fun Expressive(content: @Composable () -> Unit) =
  CapturingRemoteContentPreview(profile = androidXExperimental) {
    CompositionLocalProvider(LocalRcAnimationsEnabled provides false) {
      ProvideHaUberTheme(HaUberTheme.Expressive, darkTheme = false) { content() }
    }
  }

/** The chip's shape morph: at rest, on (selected), and pressed while on. */
@Preview(name = "expressive shape morph", showBackground = true, widthDp = 180, heightDp = 64)
@Composable
fun ExpressiveShapeMorph() = Expressive {
  val accent = currentRemoteHaTheme().linkText
  RemoteRow(modifier = RemoteModifier.fillMaxSize().padding(8.rdp)) {
    for ((selected, pressed) in listOf(0f to 0f, 1f to 0f, 1f to 1f)) {
      RemoteBox(modifier = RemoteModifier.padding(end = 10.rdp)) {
        RemoteM3ShapeMorph(
          selected = selected.rf,
          pressed = pressed.rf,
          color = accent,
          modifier = RemoteModifier.size(48.rdp),
        ) {}
      }
    }
  }
}

@Preview(name = "expressive wavy progress", showBackground = true, widthDp = 220, heightDp = 24)
@Composable
fun ExpressiveWavyProgress() = Expressive {
  RemoteM3WavyProgress(
    progress = 0.6f.rf,
    running = true,
    colors = RcM3ProgressColors(currentRemoteHaTheme().linkText, currentRemoteHaTheme().divider),
    modifier = RemoteModifier.fillMaxSize().padding(horizontal = 6.rdp),
  )
}

@Preview(name = "expressive wavy ring", showBackground = true, widthDp = 140, heightDp = 64)
@Composable
fun ExpressiveWavyRing() = Expressive {
  val colors = RcM3ProgressColors(currentRemoteHaTheme().linkText, currentRemoteHaTheme().divider)
  RemoteRow(modifier = RemoteModifier.fillMaxSize().padding(4.rdp)) {
    RemoteM3WavyProgressRing(0.6f.rf, running = true, colors, RemoteModifier.size(56.rdp))
    RemoteBox(modifier = RemoteModifier.padding(start = 12.rdp)) {
      RemoteM3WavyProgressRing(0.6f.rf, running = false, colors, RemoteModifier.size(56.rdp))
    }
  }
}

@Preview(name = "expressive loading indicator", showBackground = true, widthDp = 64, heightDp = 64)
@Composable
fun ExpressiveLoadingIndicator() = Expressive {
  RemoteM3LoadingIndicator(Color(0xFF6750A4).rc, RemoteModifier.fillMaxSize())
}
