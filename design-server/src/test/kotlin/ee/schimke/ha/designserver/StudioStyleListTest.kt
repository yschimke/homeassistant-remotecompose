package ee.schimke.ha.designserver

import ee.schimke.ha.rc.components.HaUberTheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The dashboard studio's style picker offers exactly the card styles, as the request check takes.
 */
class StudioStyleListTest {
  @Test
  fun stylePickerListsEveryCardStyle() {
    val page = File("src/main/resources/dashboard/index.html").readText()
    val select = page.substringAfter("<select id=\"style\">").substringBefore("</select>")
    val offered =
      Regex("<option>([^<]+)</option>").findAll(select).map { it.groupValues[1] }.toSet()
    assertEquals(HaUberTheme.all.map { it.id }.toSet(), offered)
  }
}
