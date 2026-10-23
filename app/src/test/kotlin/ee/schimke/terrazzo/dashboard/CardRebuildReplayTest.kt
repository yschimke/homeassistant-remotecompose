package ee.schimke.terrazzo.dashboard

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.JinjaTemplate
import ee.schimke.ha.model.TemplateBindings
import ee.schimke.ha.rc.CardDesignTemplates
import ee.schimke.ha.rc.StablePictures
import ee.schimke.ha.rc.cardDataSignature
import ee.schimke.ha.rc.cardIconSignature
import ee.schimke.ha.rc.cards.cardVisibilitySignature
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.cards.shutter.withEnhancedShutter
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.terrazzo.core.session.DemoData
import java.io.File
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * How often each dashboard card rebuilds its document on live data, replayed over an hour of demo
 * mode's values (they move each minute, cameras every ten seconds): the parts of `CardSlot`'s cache
 * key that come from the snapshot, plus which picture slots have a picture (a new picture loads in
 * place, `StablePictures`), sampled every ten seconds. Any change is a rebuild the card's named
 * bindings didn't carry. A card played from a card design ("template") costs nothing for any of
 * them; a converter card re-captures its document.
 *
 * Writes `build/reports/card-rebuilds.md`; prints the same table.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CardRebuildReplayTest {

  private val registry = defaultRegistry().withEnhancedShutter()

  private enum class Reason {
    Data,
    Icon,
    Visibility,
    Template,
    Picture,
  }

  private fun parts(card: CardConfig, snapshot: HaSnapshot): Map<Reason, Any?> =
    mapOf(
      Reason.Data to registry.cardDataSignature(card, snapshot),
      Reason.Icon to cardIconSignature(card, snapshot),
      Reason.Visibility to cardVisibilitySignature(card, snapshot),
      Reason.Template to
        TemplateBindings.cardTemplates(card).associate {
          it.key to JinjaTemplate.render(it.template, snapshot)
        },
      Reason.Picture to StablePictures.presence(card, snapshot),
    )

  private class Tally(val type: String) {
    var slots = 0
    var templated = 0
    var rebuilds = 0
    var encodes = 0
    var decodes = 0
    val byReason = mutableMapOf<Reason, Int>()
  }

  @Test
  fun reportRebuildsOverAnHourOfDemoData() {
    val start = 1_800_000_000_000L
    val steps = (HOUR_MS / STEP_MS).toInt()
    val snapshots = (0..steps).map { DemoData.snapshot(start + it * STEP_MS) }
    val tallies = sortedMapOf<String, Tally>()
    for (summary in DemoData.dashboards) {
      for (card in DemoData.dashboard(summary.urlPath).slotCards()) {
        val tally = tallies.getOrPut(card.type) { Tally(card.type) }
        tally.slots++
        val templated =
          CardDesignTemplates.keyFor(
            card,
            snapshots[0],
            ThemeStyle.TerrazzoHome,
            false,
            registry,
          ) != null
        if (templated) tally.templated++
        var previous = parts(card, snapshots[0])
        for (snapshot in snapshots.drop(1)) {
          val now = parts(card, snapshot)
          val changed = Reason.entries.filter { previous[it] != now[it] }
          if (changed.isNotEmpty()) {
            tally.rebuilds++
            // What CachedCardPreview does for it: a converter card captures again; a templated one
            // only swaps bindings.
            if (!templated) tally.encodes++
            changed.forEach { tally.byReason.merge(it, 1, Int::plus) }
          }
          previous = now
        }
      }
    }
    val report = buildString {
      appendLine("# Card rebuilds on live data")
      appendLine()
      appendLine(
        "An hour of demo data sampled every ${STEP_MS / 1000} s, every card slot on every demo " +
          "board. Rebuilds per slot per hour; a rebuild with several reasons counts once in the " +
          "total and once per reason."
      )
      appendLine()
      appendLine(
        "| Card type | Slots | Templated | Rebuilds / slot / h | Encodes | Decodes | " +
          "${Reason.entries.joinToString(" | ")} |"
      )
      appendLine(
        "| --- | ---: | ---: | ---: | ---: | ---: |${Reason.entries.joinToString("") { " ---: |" }}"
      )
      tallies.values
        .sortedByDescending { it.rebuilds.toDouble() / it.slots }
        .forEach { t ->
          val perSlot = { n: Int -> "%.1f".format(n.toDouble() / t.slots) }
          appendLine(
            "| ${t.type} | ${t.slots} | ${t.templated} | ${perSlot(t.rebuilds)} | " +
              "${perSlot(t.encodes)} | ${perSlot(t.decodes)} | " +
              Reason.entries.joinToString(" | ") { perSlot(t.byReason[it] ?: 0) } +
              " |"
          )
        }
      val total = tallies.values.sumOf { it.rebuilds }
      val slots = tallies.values.sumOf { it.slots }
      appendLine()
      appendLine(
        "Total: $total rebuilds across $slots slots in the hour: " +
          "${tallies.values.sumOf { it.encodes }} encodes, " +
          "${tallies.values.sumOf { it.decodes }} decodes."
      )
    }
    File("build/reports").apply { mkdirs() }.resolve("card-rebuilds.md").writeText(report)
    println(report)
  }

  private companion object {
    const val HOUR_MS = 60 * 60_000L
    const val STEP_MS = 10_000L
  }
}

/** The cards a dashboard draws in their own slots: view headers, view cards, section cards. */
private fun ee.schimke.ha.model.Dashboard.slotCards(): List<CardConfig> = views.flatMap { view ->
  listOfNotNull(view.header?.card) + view.cards + view.sections.flatMap { it.cards }
}
