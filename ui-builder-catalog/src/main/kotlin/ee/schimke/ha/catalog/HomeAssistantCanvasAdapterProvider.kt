package ee.schimke.ha.catalog

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.renderer.sdk.CanvasAdapterRegistry
import ee.schimke.composeai.uibuilder.renderer.sdk.JvmCanvasAdapterProvider

/** Android implementation supplied by the catalog artifact, never compiled into its host. */
class HomeAssistantCanvasAdapterProvider : JvmCanvasAdapterProvider {
  override val catalogId = "homeassistant-remotecompose"

  override fun registry(records: ComponentRecordFile): CanvasAdapterRegistry =
    HomeAssistantCatalog(records).registry()
}
