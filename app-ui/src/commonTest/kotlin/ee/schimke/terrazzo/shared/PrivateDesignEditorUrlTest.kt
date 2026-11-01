package ee.schimke.terrazzo.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PrivateDesignEditorUrlTest {
  @Test
  fun opensOnlyTheOneTimeLinkOnTheSignedInHome() {
    assertEquals(
      "https://home.example/api/terrazzo/designs-open/abc_123-X",
      privateDesignEditorUrl("https://home.example/", "/api/terrazzo/designs-open/abc_123-X"),
    )
    for (path in
      listOf(
        "//elsewhere.test/x",
        "https://elsewhere.test/x",
        "/api/terrazzo/designs-open/../x",
        "/api/terrazzo/designs-open/x?y=z",
        "/api/terrazzo/designs-open/",
      )) {
      assertNull(privateDesignEditorUrl("https://home.example", path))
    }
  }
}
