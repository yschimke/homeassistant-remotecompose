package ee.schimke.ha.catalog

import ee.schimke.composeai.discovery.*

/** Current compiled signatures, with explicit native-only platform claims. */
fun discoverCatalogComponents(): ComponentRecordFile =
  TypedAdapterDiscovery.discover(
      ":ui-builder-catalog",
      "debug",
      listOf(
        TypedAdapterDiscovery.Callable(
          "ee.schimke.ha.catalog.HomeAssistantCatalogKt",
          "CatalogHeading",
        ),
        TypedAdapterDiscovery.Callable("ee.schimke.ha.rc.ui.HaStackKt", "HaVerticalStack"),
      ),
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
                    BuilderPolicy.Builder().also { policy -> policy.nativeOnly = true }.build()
                }
                .build()
            }
        }
        .build()
    }
