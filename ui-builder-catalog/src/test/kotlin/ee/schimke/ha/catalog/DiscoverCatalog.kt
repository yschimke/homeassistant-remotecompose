package ee.schimke.ha.catalog

import ee.schimke.composeai.discovery.*

/**
 * Every Tier-2 card composable the catalog adapts, by name and its file facade in
 * `rc-components-ui`.
 */
val catalogCallables: Map<String, String> =
  linkedMapOf(
    "HaHeading" to "HaHeadingKt",
    "HaTile" to "HaTileKt",
    "HaButton" to "HaButtonKt",
    "HaToggleButton" to "HaButtonKt",
    "HaEntityRow" to "HaEntitiesKt",
    "HaEntities" to "HaEntitiesKt",
    "HaGlance" to "HaGlanceKt",
    "HaGlanceCell" to "HaGlanceKt",
    "HaMarkdown" to "HaMarkdownKt",
    "HaUnsupported" to "HaUnsupportedKt",
    "HaToggleSwitch" to "HaToggleSwitchKt",
    "HaMetric" to "HaMetricKt",
    "HaGauge" to "HaGaugeKt",
    "HaArcDial" to "HaArcDialKt",
    "HaHistoryGraph" to "HaGraphsKt",
    "HaStatisticsGraph" to "HaGraphsKt",
    "HaStatisticCard" to "HaGraphsKt",
    "HaSensorCard" to "HaGraphsKt",
    "HaLogbook" to "HaListsKt",
    "HaCalendar" to "HaListsKt",
    "HaTodoList" to "HaListsKt",
    "HaWeatherForecast" to "HaWeatherForecastKt",
    "HaMediaControl" to "HaMediaControlKt",
    "HaAlarmPanel" to "HaAlarmPanelKt",
    "HaArea" to "HaAreaKt",
    "HaClock" to "HaClockKt",
    "HaPicture" to "HaPictureKt",
    "HaPictureGlance" to "HaPictureKt",
    "HaPictureElements" to "HaPictureKt",
    "HaPictureEntity" to "HaPictureKt",
    "HaBambuPrintStatus" to "HaBambuKt",
    "HaBambuPrintControl" to "HaBambuKt",
    "HaBambuAms" to "HaBambuKt",
    "HaBambuSpool" to "HaBambuKt",
    "HaVerticalStack" to "HaStackKt",
    "HaHorizontalStack" to "HaStackKt",
    "HaGrid" to "HaStackKt",
  )

/**
 * JVM names of the entry points whose `Color` (an inline value class) parameters mangle them; the
 * suffix is the compiler's hash of the signature, so a changed signature fails discovery loudly.
 */
private val mangledJvmNames =
  mapOf("HaToggleSwitch" to "HaToggleSwitch-DTcfvLk", "HaMetric" to "HaMetric-uDo3WH8")

private val cardParts =
  setOf(
    "HaHeading",
    "HaTile",
    "HaButton",
    "HaToggleButton",
    "HaEntityRow",
    "HaGlanceCell",
    "HaToggleSwitch",
    "HaMetric",
  )

private val layouts = setOf("HaVerticalStack", "HaHorizontalStack", "HaGrid")

private fun groupFor(name: String) =
  when (name) {
    in cardParts -> "Card building blocks"
    in layouts -> "Layout"
    "HaUnsupported" -> "Fallback"
    else -> "Complete cards"
  }

private fun labelFor(name: String) =
  name.removePrefix("Ha").replace(Regex("([a-z])([A-Z])"), "$1 $2")

/**
 * Current compiled signatures of the real Tier-2 cards, with explicit native-only platform claims.
 */
fun discoverCatalogComponents(): ComponentRecordFile =
  TypedAdapterDiscovery.discover(
      ":ui-builder-catalog",
      "debug",
      catalogCallables.map { (name, facade) ->
        TypedAdapterDiscovery.Callable("ee.schimke.ha.rc.ui.$facade", mangledJvmNames[name] ?: name)
      },
    )
    .let { records ->
      records
        .newBuilder()
        .also { b ->
          b.components =
            records.components.map { c ->
              c.newBuilder()
                .also {
                  it.builder =
                    BuilderPolicy.Builder()
                      .also { policy ->
                        policy.nativeOnly = true
                        policy.group = groupFor(c.symbol.name)
                        policy.displayName = labelFor(c.symbol.name)
                      }
                      .build()
                }
                .build()
            }
        }
        .build()
    }
