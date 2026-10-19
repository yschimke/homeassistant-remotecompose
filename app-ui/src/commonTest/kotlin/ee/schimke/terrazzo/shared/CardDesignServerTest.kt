package ee.schimke.terrazzo.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class CardDesignServerTest {
  private val sha = "a".repeat(64)

  @Test
  fun defaultServerIsHostedLibrary() {
    assertEquals("https://ha.coo.ee/api/cards/resolve", cardDesignResolveUrl("https://ha.coo.ee"))
    assertEquals(
      "https://ha.coo.ee/designs/v1/blobs/$sha.rc",
      cardDesignBlobUrl(DEFAULT_CARD_DESIGN_SERVER, sha),
    )
  }

  @Test
  fun configuredServerIsUsedForResolveAndBlob() {
    assertEquals(
      "http://localhost:8080/api/cards/resolve",
      cardDesignResolveUrl(" http://localhost:8080/ "),
    )
    assertEquals(
      "https://designs.example/designs/v1/blobs/$sha.rc",
      cardDesignBlobUrl("https://designs.example//", sha),
    )
  }

  @Test
  fun blankServerFallsBackToDefault() {
    assertEquals("https://ha.coo.ee/api/cards/resolve", cardDesignResolveUrl("  "))
  }
}
