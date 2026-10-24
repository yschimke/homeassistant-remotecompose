@file:Suppress("COMPOSE_APPLIER_CALL_MISMATCH", "RestrictedApi")

package ee.schimke.ha.previews

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.player.core.platform.BitmapLoader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.Section
import ee.schimke.ha.rc.CachedCardPreview
import ee.schimke.ha.rc.FixedHaClock
import ee.schimke.ha.rc.HaRcComposePlayer
import ee.schimke.ha.rc.LocalHaClock
import ee.schimke.ha.rc.LovelaceSectionCardFlow
import ee.schimke.ha.rc.ProvideCardRegistry
import ee.schimke.ha.rc.RemoteSectionCardFlow
import ee.schimke.ha.rc.RenderChild
import ee.schimke.ha.rc.androidXExperimentalWrap
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.cards.shutter.withEnhancedShutter
import ee.schimke.ha.rc.cards.shutter.withGarageShutter
import ee.schimke.ha.rc.components.HaTheme
import ee.schimke.ha.rc.components.ProvideHaTheme
import ee.schimke.ha.rc.enableRemoteComposeWrapContent
import ee.schimke.ha.rc.sectionFlowItems
import kotlinx.coroutines.runBlocking

/**
 * Section layout by the host against section layout by the player. The climate board's sections,
 * with their `grid` cards lifted into the section ([liftGridCards]) so the flow has rows to fill,
 * drawn two ways at the same width:
 * - **Host**: [LovelaceSectionCardFlow], today's layout, one `.rc` document per card placed by a
 *   Compose `Layout`.
 * - **Remote**: [RemoteSectionCardFlow], one `.rc` document per section whose cards are flowed by
 *   `RemoteFlexBox` inside the player.
 */
private const val SECTION_FIXTURE = "climate"

@Preview(name = "section flow host — mobile", widthDp = 411, heightDp = 2000)
@Composable
fun SectionFlowHostMobile() = SectionFlowPreview(remote = false)

@Preview(name = "section flow remote — mobile", widthDp = 411, heightDp = 2000)
@Composable
fun SectionFlowRemoteMobile() = SectionFlowPreview(remote = true)

@Preview(name = "section flow host — tablet", widthDp = 800, heightDp = 1400)
@Composable
fun SectionFlowHostTablet() = SectionFlowPreview(remote = false)

@Preview(name = "section flow remote — tablet", widthDp = 800, heightDp = 1400)
@Composable
fun SectionFlowRemoteTablet() = SectionFlowPreview(remote = true)

@Composable
private fun SectionFlowPreview(remote: Boolean) {
  enableRemoteComposeWrapContent()
  val loaded = DashboardFixtures.load(SECTION_FIXTURE) ?: return
  val view = liftGridCards(loaded.dashboard.views.first())
  val bitmapLoader = remember { DashboardPreviewBitmapLoader() }
  CompositionLocalProvider(LocalHaClock provides FixedHaClock(DashboardPreviewNow)) {
    Column(
      modifier =
        Modifier.fillMaxWidth()
          .background(Color.White)
          .verticalScroll(rememberScrollState())
          .padding(8.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text(
        if (remote) "RemoteSectionCardFlow (one document per section)"
        else "LovelaceSectionCardFlow (one document per card)",
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
      )
      view.sections.forEachIndexed { index, section ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(section.title ?: "section ${index + 1}", fontSize = 12.sp)
          if (remote) RemoteSection(section, loaded.snapshot, bitmapLoader, index)
          else HostSection(section, loaded.snapshot, bitmapLoader)
        }
      }
    }
  }
}

private val registry = defaultRegistry().withEnhancedShutter().withGarageShutter()

@Composable
private fun HostSection(section: Section, snapshot: HaSnapshot, bitmapLoader: BitmapLoader) {
  val items =
    remember(section.cards, snapshot) { sectionFlowItems(section.cards, snapshot, registry) }
  LovelaceSectionCardFlow(items = items, modifier = Modifier.fillMaxWidth()) {
    section.cards.forEach { card ->
      Box(Modifier.fillMaxWidth().heightIn(max = 1800.dp)) {
        CachedCardPreview(
          cacheKey = card,
          profile = androidXExperimentalWrap,
          modifier = Modifier.fillMaxWidth(),
          card = card,
          snapshot = snapshot,
          bitmapLoader = bitmapLoader,
        ) {
          ProvideCardRegistry(registry) {
            ProvideHaTheme(HaTheme.Light) {
              RenderChild(card, snapshot, RemoteModifier.fillMaxWidth())
            }
          }
        }
      }
    }
  }
}

@Composable
private fun RemoteSection(
  section: Section,
  snapshot: HaSnapshot,
  bitmapLoader: BitmapLoader,
  index: Int,
) {
  val items =
    remember(section.cards, snapshot) { sectionFlowItems(section.cards, snapshot, registry) }
  Box(Modifier.fillMaxWidth().heightIn(max = 1800.dp)) {
    CachedCardPreview(
      cacheKey = "remote-section-$SECTION_FIXTURE-$index",
      profile = androidXExperimentalWrap,
      modifier = Modifier.fillMaxWidth(),
      snapshot = snapshot,
      bitmapLoader = bitmapLoader,
    ) {
      ProvideCardRegistry(registry) {
        ProvideHaTheme(HaTheme.Light) { RemoteSectionCardFlow(section.cards, items, snapshot) }
      }
    }
  }
}

/**
 * One capture, played at two widths: the "Indoor climate" section captured once, 800 dp wide, then
 * played at a phone width and at the tablet width. The document picks its row split from the width
 * it is played at, so the phone gets two cards a row with no re-capture.
 */
@Preview(name = "section flow remote — one capture, two widths", widthDp = 1240, heightDp = 1000)
@Composable
fun SectionFlowRemoteReflow() {
  enableRemoteComposeWrapContent()
  val loaded = DashboardFixtures.load(SECTION_FIXTURE) ?: return
  val section = liftGridCards(loaded.dashboard.views.first()).sections[1]
  val snapshot = loaded.snapshot
  val context = LocalContext.current
  val density = LocalDensity.current
  val bitmapLoader = remember { DashboardPreviewBitmapLoader() }
  val items = remember { sectionFlowItems(section.cards, snapshot, registry) }
  val bytes = remember {
    runBlocking {
      captureSingleRemoteDocument(
          context = context,
          creationDisplayInfo =
            RemoteCreationDisplayInfo(
              (800 * density.density).toInt(),
              (1000 * density.density).toInt(),
              (density.density * 160).toInt(),
            ),
          profile = androidXExperimentalWrap,
        ) {
          CompositionLocalProvider(LocalHaClock provides FixedHaClock(DashboardPreviewNow)) {
            ProvideCardRegistry(registry) {
              ProvideHaTheme(HaTheme.Light) {
                RemoteSectionCardFlow(section.cards, items, snapshot)
              }
            }
          }
        }
        .bytes
    }
  }
  Row(
    Modifier.background(Color.White).padding(8.dp),
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    listOf(411, 800).forEach { widthDp ->
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("played at $widthDp dp", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Box(Modifier.width(widthDp.dp).height(960.dp)) {
          HaRcComposePlayer(
            documentBytes = bytes,
            modifier = Modifier.fillMaxWidth(),
            bitmapLoader = bitmapLoader,
            fallback = { Text("CMP player could not play this document") },
          )
        }
      }
    }
  }
}
