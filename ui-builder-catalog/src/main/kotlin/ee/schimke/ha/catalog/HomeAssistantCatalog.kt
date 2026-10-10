package ee.schimke.ha.catalog

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ee.schimke.composeai.discovery.*
import ee.schimke.composeai.uibuilder.renderer.sdk.*
import ee.schimke.ha.rc.ui.HaHeading
import ee.schimke.ha.rc.ui.HaHeadingUiData
import ee.schimke.ha.rc.ui.HaVerticalStack

/** Discoverable scalar export wrapper around the app's real Android/Remote Compose component. */
@Composable
fun CatalogHeading(title: String, modifier: Modifier = Modifier) {
  HaHeading(HaHeadingUiData(title = title), modifier)
}

class HeadingAdapter(record: ComponentRecord) :
  TypedComponentAdapter<HaHeadingUiData>("homeassistant-remotecompose/heading", record) {
  val title = property(HaHeadingUiData::title, AdapterValueCodecs.String, "Home Assistant")
}

class StackAdapter(record: ComponentRecord) :
  TypedComponentAdapter<Unit>("homeassistant-remotecompose/vertical-stack", record) {
  val content = slot("content")
}

class HomeAssistantCatalog(records: ComponentRecordFile) {
  val heading = HeadingAdapter(records.components.single { it.symbol.name == "CatalogHeading" })
  val stack = StackAdapter(records.components.single { it.symbol.name == "HaVerticalStack" })
  val definitions = listOf(heading, stack)

  fun generate(records: ComponentRecordFile): GeneratedAdapterCatalog =
    TypedAdapterCatalog.generate(
      records,
      UiBuilderCatalogs.CoverSheet(
        "homeassistant-remotecompose",
        "Home Assistant native components",
      ),
      UiBuilderPolicyFile.Builder(UI_BUILDER_POLICY_SCHEMA, "mobile").build(),
      definitions,
    )

  fun registry(): CanvasAdapterRegistry = canvasAdapterRegistry {
    register(heading) { CatalogHeading(title = value(heading.title), modifier = modifier) }
    register(stack) { HaVerticalStack(modifier = modifier) { Slot(stack.content) } }
  }
}
