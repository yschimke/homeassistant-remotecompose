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
        TypedAdapterDiscovery.Callable(
          "ee.schimke.ha.catalog.HomeAssistantCatalogKt",
          "CatalogTile",
        ),
        TypedAdapterDiscovery.Callable(
          "ee.schimke.ha.catalog.HomeAssistantCatalogKt",
          "CatalogButton",
        ),
        TypedAdapterDiscovery.Callable(
          "ee.schimke.ha.catalog.HomeAssistantCatalogKt",
          "CatalogEntityRow",
        ),
        TypedAdapterDiscovery.Callable(
          "ee.schimke.ha.catalog.HomeAssistantCatalogKt",
          "CatalogEntities",
        ),
        TypedAdapterDiscovery.Callable(
          "ee.schimke.ha.catalog.HomeAssistantCatalogKt",
          "CatalogGlance",
        ),
        TypedAdapterDiscovery.Callable("ee.schimke.ha.rc.ui.HaStackKt", "HaVerticalStack"),
        TypedAdapterDiscovery.Callable("ee.schimke.ha.rc.ui.HaStackKt", "HaHorizontalStack"),
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
