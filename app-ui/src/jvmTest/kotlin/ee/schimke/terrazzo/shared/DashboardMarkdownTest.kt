package ee.schimke.terrazzo.shared

import androidx.compose.ui.text.LinkAnnotation
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class DashboardMarkdownTest {
  private fun links(line: String) =
    markdownInline(line).getLinkAnnotations(0, line.length).map {
      (it.item as LinkAnnotation.Url).url
    }

  @Test
  fun aLinkShowsItsTextAndOpensItsUrl() {
    val line = "See [the docs](https://example.com/docs \"Docs\") for more"
    assertEquals("See the docs for more", markdownInline(line).text)
    assertEquals(listOf("https://example.com/docs"), links(line))
  }

  @Test
  fun aBadgeLinkShowsItsAltText() {
    val line =
      "[![Last commit](https://img.shields.io/github/last-commit/o/r)](https://github.com/o/r) " +
        "![build](https://example.com/badge.svg)"
    assertEquals("Last commit build", markdownInline(line).text)
    assertEquals(listOf("https://github.com/o/r"), links(line))
  }

  @Test
  fun aHomeAssistantPathIsText() {
    val line = "[Open P2s detail →](/dashboard-bambu/p2s)"
    assertEquals("Open P2s detail →", markdownInline(line).text)
    assertEquals(emptyList(), links(line))
  }

  @Test
  fun boldStillWorksAroundLinks() {
    val text = markdownInline("**Hub:** [up](https://x.example)")
    assertEquals("Hub: up", text.text)
    assertTrue(text.spanStyles.isNotEmpty())
  }

  @Test
  fun aMissingEntityIsNoticedOnlyOnceStatesArrive() {
    val card =
      Json.decodeFromString<CardConfig>("""{"type":"tile","entity":"device_tracker.gone"}""")
    assertFalse(namesMissingEntity(card, HaSnapshot()))
    val loaded = HaSnapshot(states = mapOf("sun.sun" to EntityState("sun.sun", "above_horizon")))
    assertTrue(namesMissingEntity(card, loaded))
    val present =
      HaSnapshot(
        states = mapOf("device_tracker.gone" to EntityState("device_tracker.gone", "home"))
      )
    assertFalse(namesMissingEntity(card, present))
  }
}
