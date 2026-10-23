package ee.schimke.ha.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class DesignEntryWireTest {
  private val json = Json { ignoreUnknownKeys = true }

  private val key =
    DesignKey(
      cardType = "tile",
      surface = DesignSurface.MobileApp,
      rcApiLevel = 8,
      shape = "domain=light",
      theme = DesignKey.bakedTheme("Material3", dark = false),
    )

  @Test
  fun anEntryWithADesignIdRoundTrips() {
    val entry =
      DesignEntry(
        key = key,
        sha256 = "00",
        bytes = 1,
        slots = 1,
        author = "github:octocat",
        device = DeviceMatch(integration = "bambu_lab"),
        designId = DesignEntry.userDesignId("printer-card"),
        title = "Printer card",
      )
    val text = json.encodeToString(DesignEntry.serializer(), entry)
    assertTrue("\"designId\":\"user:printer-card\"" in text)
    assertEquals(entry, json.decodeFromString(DesignEntry.serializer(), text))
  }

  @Test
  fun anEntryFromBeforeDesignIdsStillParsesAndWritesNoId() {
    val old =
      """{"key":{"cardType":"tile","surface":"mobile-app","rcApiLevel":8,"shape":"domain=light",""" +
        """"theme":"baked:Material3:light"},"sha256":"00","bytes":1,"slots":1}"""
    val entry = json.decodeFromString(DesignEntry.serializer(), old)
    assertNull(entry.designId)
    assertNull(entry.title)
    // Unset, the new fields stay off the wire, so an old client reads exactly what it did.
    assertFalse("designId" in json.encodeToString(DesignEntry.serializer(), entry))
  }

  @Test
  fun anOldClientIgnoresTheNewFields() {
    val manifest =
      DesignManifest(
        serial = 1,
        generatedAtEpochMs = 0,
        expiresAtEpochMs = 1,
        designs =
          listOf(
            DesignEntry(
              key,
              "00",
              1,
              1,
              designId = DesignEntry.libraryDesignId("tile"),
              title = "Built-in",
            )
          ),
      )
    val text = json.encodeToString(DesignManifest.serializer(), manifest)
    // Every client decodes with ignoreUnknownKeys, so a manifest with ids parses for it too.
    val strictOld = Json { ignoreUnknownKeys = true }
    assertEquals(1, strictOld.decodeFromString(DesignManifest.serializer(), text).designs.size)
  }

  @Test
  fun aLibraryEntryWithoutAnIdGetsTheLibrarysId() {
    val library = DesignEntry(key, "00", 1, 1)
    assertEquals("library:tile", library.stableDesignId)
    assertNull(library.copy(author = "github:octocat").stableDesignId)
    assertEquals(
      "user:x",
      library.copy(author = "github:octocat", designId = "user:x").stableDesignId,
    )
  }

  @Test
  fun aQueryCanNameOneDesign() {
    val entry = DesignEntry(key, "00", 1, 1, author = "github:octocat", designId = "user:x")
    assertTrue(DesignQuery(designId = "user:x").matches(entry))
    assertFalse(DesignQuery(designId = "user:y").matches(entry))
    assertTrue(DesignQuery(designId = "library:tile").matches(DesignEntry(key, "00", 1, 1)))
    val query = DesignQuery(designId = "user:x", theme = key.theme)
    assertEquals(query, DesignQuery.of(query.parameters().toMap()::get))
  }
}
