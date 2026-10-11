package ee.schimke.ha.catalog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.discovery.*
import ee.schimke.composeai.uibuilder.renderer.sdk.*
import ee.schimke.ha.rc.ui.HaButton
import ee.schimke.ha.rc.ui.HaButtonUiData
import ee.schimke.ha.rc.ui.HaEntityRow
import ee.schimke.ha.rc.ui.HaEntityRowUiData
import ee.schimke.ha.rc.ui.HaHeading
import ee.schimke.ha.rc.ui.HaHeadingUiData
import ee.schimke.ha.rc.ui.HaHorizontalStack
import ee.schimke.ha.rc.ui.HaTile
import ee.schimke.ha.rc.ui.HaTileUiData
import ee.schimke.ha.rc.ui.HaToggleAccentUi
import ee.schimke.ha.rc.ui.HaVerticalStack

/**
 * Fixed entity identity for the scalar card wrappers. A read-only accent (`isOn = null`) keeps the
 * cards free of live `is_on` bindings, and a locally built glyph keeps them off the icon libraries
 * an independent host does not supply.
 */
private val catalogAccent =
  HaToggleAccentUi(activeAccent = Color(0xFFFFC107), inactiveAccent = Color(0xFF8F8F8F))

private val catalogIcon: ImageVector =
  ImageVector.Builder(
      name = "CatalogLightbulb",
      defaultWidth = 24.dp,
      defaultHeight = 24.dp,
      viewportWidth = 24f,
      viewportHeight = 24f,
    )
    .path(fill = SolidColor(Color.Black)) {
      moveTo(9f, 21f)
      curveToRelative(0f, 0.55f, 0.45f, 1f, 1f, 1f)
      horizontalLineToRelative(4f)
      curveToRelative(0.55f, 0f, 1f, -0.45f, 1f, -1f)
      verticalLineToRelative(-1f)
      lineTo(9f, 20f)
      verticalLineToRelative(1f)
      close()
      moveTo(12f, 2f)
      curveTo(8.14f, 2f, 5f, 5.14f, 5f, 9f)
      curveToRelative(0f, 2.38f, 1.19f, 4.47f, 3f, 5.74f)
      lineTo(8f, 17f)
      curveToRelative(0f, 0.55f, 0.45f, 1f, 1f, 1f)
      horizontalLineToRelative(6f)
      curveToRelative(0.55f, 0f, 1f, -0.45f, 1f, -1f)
      verticalLineToRelative(-2.26f)
      curveToRelative(1.81f, -1.27f, 3f, -3.36f, 3f, -5.74f)
      curveToRelative(0f, -3.86f, -3.14f, -7f, -7f, -7f)
      close()
    }
    .build()

/** Discoverable scalar export wrapper around the app's real Android/Remote Compose component. */
@Composable
fun CatalogHeading(title: String, modifier: Modifier = Modifier) {
  // RemoteContentPreview captures a document; a changed scalar must start a fresh capture.
  key(title) { HaHeading(HaHeadingUiData(title = title), modifier) }
}

/** Scalar export wrapper around the real `tile` card: entity name over its state. */
@Composable
fun CatalogTile(name: String, state: String, modifier: Modifier = Modifier) {
  key(name, state) {
    HaTile(
      HaTileUiData(name = name, state = state, icon = catalogIcon, accent = catalogAccent),
      modifier,
    )
  }
}

/** Scalar export wrapper around the real `button` card: an icon with an optional name. */
@Composable
fun CatalogButton(name: String, showName: Boolean, modifier: Modifier = Modifier) {
  key(name, showName) {
    HaButton(
      HaButtonUiData(name = name, icon = catalogIcon, accent = catalogAccent, showName = showName),
      modifier,
    )
  }
}

/** Scalar export wrapper around the real `entities` card row: icon, name and state. */
@Composable
fun CatalogEntityRow(name: String, state: String, modifier: Modifier = Modifier) {
  key(name, state) {
    HaEntityRow(
      HaEntityRowUiData(name = name, state = state, icon = catalogIcon, accent = catalogAccent),
      modifier,
    )
  }
}

class HeadingAdapter(record: ComponentRecord) :
  TypedComponentAdapter<HaHeadingUiData>("homeassistant-remotecompose/heading", record) {
  val title = property(HaHeadingUiData::title, AdapterValueCodecs.String, "Home Assistant")
}

class TileAdapter(record: ComponentRecord) :
  TypedComponentAdapter<HaTileUiData>("homeassistant-remotecompose/tile", record) {
  val name = property(HaTileUiData::name, AdapterValueCodecs.String, "Kitchen")
  val state = property(HaTileUiData::state, AdapterValueCodecs.String, "On")
}

class ButtonAdapter(record: ComponentRecord) :
  TypedComponentAdapter<HaButtonUiData>("homeassistant-remotecompose/button", record) {
  val name = property(HaButtonUiData::name, AdapterValueCodecs.String, "Kitchen")
  val showName = property(HaButtonUiData::showName, AdapterValueCodecs.Boolean, true)
}

class EntityRowAdapter(record: ComponentRecord) :
  TypedComponentAdapter<HaEntityRowUiData>("homeassistant-remotecompose/entity-row", record) {
  val name = property(HaEntityRowUiData::name, AdapterValueCodecs.String, "Kitchen")
  val state = property(HaEntityRowUiData::state, AdapterValueCodecs.String, "On")
}

class StackAdapter(record: ComponentRecord) :
  TypedComponentAdapter<Unit>("homeassistant-remotecompose/vertical-stack", record) {
  val content = slot("content")
}

class HorizontalStackAdapter(record: ComponentRecord) :
  TypedComponentAdapter<Unit>("homeassistant-remotecompose/horizontal-stack", record) {
  val content = slot("content")
}

class HomeAssistantCatalog(records: ComponentRecordFile) {
  val heading = HeadingAdapter(records.components.single { it.symbol.name == "CatalogHeading" })
  val tile = TileAdapter(records.components.single { it.symbol.name == "CatalogTile" })
  val button = ButtonAdapter(records.components.single { it.symbol.name == "CatalogButton" })
  val entityRow =
    EntityRowAdapter(records.components.single { it.symbol.name == "CatalogEntityRow" })
  val stack = StackAdapter(records.components.single { it.symbol.name == "HaVerticalStack" })
  val horizontalStack =
    HorizontalStackAdapter(records.components.single { it.symbol.name == "HaHorizontalStack" })
  val definitions = listOf(heading, tile, button, entityRow, stack, horizontalStack)

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
    register(tile) {
      CatalogTile(name = value(tile.name), state = value(tile.state), modifier = modifier)
    }
    register(button) {
      CatalogButton(
        name = value(button.name),
        showName = value(button.showName),
        modifier = modifier,
      )
    }
    register(entityRow) {
      CatalogEntityRow(
        name = value(entityRow.name),
        state = value(entityRow.state),
        modifier = modifier,
      )
    }
    register(stack) { HaVerticalStack(modifier = modifier) { Slot(stack.content) } }
    register(horizontalStack) {
      HaHorizontalStack(modifier = modifier) { Slot(horizontalStack.content) }
    }
  }
}
