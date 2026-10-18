package ee.schimke.terrazzo.dashboard

import ee.schimke.ha.client.dashboard.PlaceholderCard
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class PlaceholderCardStoreTest {
  @Test
  fun briefsSurviveRestartAndStayWithinTheirServerAndDashboard() = runTest {
    val root = Files.createTempDirectory("card-briefs").toFile()
    try {
      val card = PlaceholderCard("one", "Garden", "Moisture trend")
      val store = PlaceholderCardStore(root)
      store.write("https://ha.test", null, listOf(card))
      val restarted = PlaceholderCardStore(root)
      assertEquals(listOf(card), restarted.read("https://ha.test/", ""))
      assertEquals(emptyList(), restarted.read("https://other.test", null))
      assertEquals(emptyList(), restarted.read("https://ha.test", "garden"))
      restarted.write("https://ha.test", "garden", listOf(card.copy(id = "two")))
      restarted.write("https://ha.test", null, listOf(card.copy(request = "Updated brief")))
      assertEquals("Updated brief", store.read("https://ha.test", null).single().request)
      restarted.write("https://ha.test", null, emptyList())
      assertTrue(store.read("https://ha.test", null).isEmpty())
      assertEquals("two", store.read("https://ha.test", "garden").single().id)
      assertEquals(2, root.listFiles()!!.size)
    } finally {
      root.deleteRecursively()
    }
  }
}
