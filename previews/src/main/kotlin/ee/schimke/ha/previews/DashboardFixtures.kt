package ee.schimke.ha.previews

import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.LovelaceJson
import java.io.File

/**
 * Loader for the per-dashboard JSON snapshots that live in the sibling
 * [homeassistant-agents](../../../homeassistant-agents) checkout under
 * `dashboards/<name>/{lovelace_config.json,entity_states.json}`. The JSONs are sanitised in that
 * repo (IPs / MACs / tokens / camera tokens redacted) but entity ids and friendly names are kept —
 * the homeassistant-agents README treats those as non-secret.
 *
 * Files are deliberately *not* copied into this repo; the loader points at the sibling checkout via
 * [DASHBOARDS_ROOT]. When the directory isn't present (CI, fresh clones), [load] returns null and
 * the preview function should skip rendering.
 */
object DashboardFixtures {
  /** Override via `-Dha.dashboards.root=/path/to/dashboards`. */
  private val DASHBOARDS_ROOT: File =
    File(
      System.getProperty("ha.dashboards.root")
        ?: "${System.getProperty("user.home")}/workspace/homeassistant-agents/dashboards"
    )

  data class Loaded(val dashboard: Dashboard, val snapshot: HaSnapshot)

  fun load(name: String): Loaded? {
    // First try the bundled second-pass-scrubbed copy (same data
    // the in-app demo ships) — that's what we want in PR
    // screenshots and CI runs. Fall back to the sibling
    // `homeassistant-agents` checkout for local dev when the
    // resources haven't been re-scrubbed yet.
    val (cfgText, statesText) = readBundled(name) ?: readSibling(name) ?: return null
    val dashboard = LovelaceJson.dashboard(cfgText)
    val states = LovelaceJson.states(statesText)
    val devices = readDevices(name)?.let(LovelaceJson::devices).orEmpty()
    return Loaded(dashboard = dashboard, snapshot = HaSnapshot(states = states, devices = devices))
  }

  private fun readDevices(name: String): String? =
    DashboardFixtures::class
      .java
      .getResourceAsStream("/dashboards/$name/devices.json")
      ?.bufferedReader()
      ?.use { it.readText() }
      ?: File(DASHBOARDS_ROOT, "$name/devices.json").takeIf { it.exists() }?.readText()

  private fun readBundled(name: String): Pair<String, String>? {
    val cls = DashboardFixtures::class.java
    val cfg =
      cls.getResourceAsStream("/dashboards/$name/lovelace_config.json")?.bufferedReader()?.use {
        it.readText()
      } ?: return null
    val states =
      cls.getResourceAsStream("/dashboards/$name/entity_states.json")?.bufferedReader()?.use {
        it.readText()
      } ?: return null
    return cfg to states
  }

  private fun readSibling(name: String): Pair<String, String>? {
    val dir = File(DASHBOARDS_ROOT, name)
    val configFile = File(dir, "lovelace_config.json")
    val statesFile = File(dir, "entity_states.json")
    if (!configFile.exists() || !statesFile.exists()) return null
    return configFile.readText() to statesFile.readText()
  }
}
