@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.*
import androidx.compose.remote.creation.compose.modifier.*
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

// ---------------------------------------------------------------------------------------------
// The tile, designed per visual family (docs/design/everyday-cards.md §1 and §5). The tile is two
// thirds of every dashboard, so each family lays it out its own way — a plate, a screen, a product
// tag, a command key — instead of recolouring HA's icon / name / state row. Classic and Home
// Assistant keep that row: they are the HA reference the others depart from.
//
// Every layout draws the same slots from the same data: the identity (the family's meter glyph for
// a measurable sensor, else the state icon), the name, and the state binding. Nothing here adds a
// binding or bakes a value.
// ---------------------------------------------------------------------------------------------

/** Whether [family] draws the tile's own layout below rather than HA's row. */
internal fun HaVisualFamily.hasFamilyTile(): Boolean =
  this != HaVisualFamily.Classic && this != HaVisualFamily.HomeAssistant

/**
 * The tile's identity: the family's meter glyph for a measurable sensor ([HaTileData.reading]), so
 * an LCD tile shows a segment column and a Nixie tile a glowing tube; otherwise the state icon.
 * Classic and Home Assistant always draw HA's icon.
 */
@Composable
internal fun TileIdentity(
  data: HaTileData,
  look: HaEntityLook,
  renderChip: Boolean,
  icon: @Composable () -> Unit,
) {
  val reading = data.reading
  if (reading == null || !visualFamily().hasFamilyTile()) {
    icon()
    return
  }
  val (w, h) = reading.kind.visual.tileGlyphSizeDp()
  RemoteHaMeasurementGlyph(reading, RemoteModifier.size(w.rdp, h.rdp))
}

/** The glyph's box in a tile: as tall as the tile's two text lines at most. */
private fun HaMeasureVisual.tileGlyphSizeDp(): Pair<Int, Int> =
  when (this) {
    HaMeasureVisual.Thermometer -> 16 to 40
    HaMeasureVisual.Battery -> 36 to 18
    HaMeasureVisual.Arc -> 40 to 24
    HaMeasureVisual.Bar -> 40 to 10
  }

/**
 * The tile's info row for a family with its own design, or false when [family] keeps another path
 * (Classic, Home Assistant, and the community layouts in `CommunityTileInfo`).
 */
@Composable
internal fun FamilyTileInfo(
  family: HaVisualFamily,
  data: HaTileData,
  look: HaEntityLook,
  identity: @Composable () -> Unit,
): Boolean {
  when (family) {
    HaVisualFamily.Expressive -> ExpressiveTile(data, look, identity)
    HaVisualFamily.Trinitron -> TrinitronTile(data, look, identity)
    HaVisualFamily.Lcd -> LcdTile(data, look, identity)
    HaVisualFamily.Braun -> BraunTile(data, look, identity)
    HaVisualFamily.Nixie -> NixieTile(data, look, identity)
    HaVisualFamily.Console -> ConsoleTile(data, look, identity)
    HaVisualFamily.Ikea -> IkeaTile(data, look, identity)
    HaVisualFamily.Mushroom -> MushroomTile(data, look, identity)
    HaVisualFamily.Catppuccin -> CatppuccinTile(data, look, identity)
    HaVisualFamily.Obsidian -> ObsidianTile(data, look, identity)
    HaVisualFamily.Noctis -> NoctisTile(data, look, identity)
    HaVisualFamily.Lcars -> LcarsTile(data, look, identity)
    HaVisualFamily.Aeon -> AeonTile(data, look, identity)
    else -> return false
  }
  return true
}

@Composable
private fun TileName(
  data: HaTileData,
  look: HaEntityLook,
  size: RemoteTextUnit = HaType.Body,
  weight: FontWeight = FontWeight.Medium,
  secondary: Boolean = false,
  modifier: RemoteModifier = RemoteModifier,
) {
  val theme = haTheme()
  RemoteText(
    text = entityName(data.entityId, data.name),
    modifier = modifier,
    color = look.dim(if (secondary) theme.secondaryText else theme.primaryText),
    fontSize = size,
    fontWeight = weight,
    style = haTextStyle(),
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )
}

@Composable
private fun TileValue(
  data: HaTileData,
  look: HaEntityLook,
  size: RemoteTextUnit = HaType.Body,
  weight: FontWeight = haValueWeight(),
  modifier: RemoteModifier = RemoteModifier,
) {
  val theme = haTheme()
  RemoteText(
    text = data.state,
    modifier = modifier,
    color = look.dim(theme.primaryText),
    fontSize = size,
    fontWeight = weight,
    style = haTextStyle(),
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )
}

/** Material 3 Expressive: the value on a tonal pill at the end of the row. */
@Composable
private fun ExpressiveTile(data: HaTileData, look: HaEntityLook, identity: @Composable () -> Unit) {
  val theme = haTheme()
  RemoteRow(RemoteModifier.fillMaxWidth(), verticalAlignment = RemoteAlignment.CenterVertically) {
    identity()
    TileName(data, look, modifier = RemoteModifier.weight(1f).padding(horizontal = 10.rdp))
    RemoteBox(
      modifier =
        RemoteModifier.clip(RemoteRoundedCornerShape(16.rdp))
          .background(theme.placeholderBackground)
          .padding(horizontal = HaSpace.L.rdp, vertical = HaSpace.S.rdp)
    ) {
      TileValue(data, look, HaType.BodyLarge)
    }
  }
}

/** Trinitron: a hi-fi front-panel plate, the name tracked and ruled off over the reading. */
@Composable
private fun TrinitronTile(data: HaTileData, look: HaEntityLook, identity: @Composable () -> Unit) {
  val theme = haTheme()
  RemoteRow(RemoteModifier.fillMaxWidth(), verticalAlignment = RemoteAlignment.CenterVertically) {
    identity()
    RemoteColumn(RemoteModifier.weight(1f).padding(start = 10.rdp)) {
      TileName(data, look, HaType.Caption, FontWeight.Normal, secondary = true)
      RemoteBox(
        RemoteModifier.padding(vertical = HaSpace.XXS.rdp)
          .fillMaxWidth()
          .height(HaSpace.Hairline.rdp)
          .background(theme.divider)
      )
      TileValue(data, look, HaType.BodyLarge)
    }
  }
}

/** LCD: the reading as a big number in the recessed screen, the name a caption under it. */
@Composable
private fun LcdTile(data: HaTileData, look: HaEntityLook, identity: @Composable () -> Unit) {
  val theme = haTheme()
  RemoteColumn(RemoteModifier.fillMaxWidth()) {
    RemoteRow(
      RemoteModifier.fillMaxWidth()
        .clip(RemoteRoundedCornerShape(HaSpace.XXS.rdp))
        .background(theme.dashboardBackground)
        .padding(horizontal = HaSpace.M.rdp, vertical = HaSpace.XS.rdp),
      verticalAlignment = RemoteAlignment.CenterVertically,
    ) {
      identity()
      TileValue(
        data,
        look,
        HaType.Value,
        modifier = RemoteModifier.weight(1f).padding(start = 10.rdp),
      )
    }
    TileName(
      data,
      look,
      HaType.Caption,
      FontWeight.Normal,
      secondary = true,
      modifier = RemoteModifier.padding(top = HaSpace.XS.rdp),
    )
  }
}

/** Braun: an orange index dot, the name, and the reading set to the right on one line. */
@Composable
private fun BraunTile(data: HaTileData, look: HaEntityLook, identity: @Composable () -> Unit) {
  val theme = haTheme()
  RemoteRow(RemoteModifier.fillMaxWidth(), verticalAlignment = RemoteAlignment.CenterVertically) {
    identity()
    RemoteBox(
      RemoteModifier.padding(start = 12.rdp, end = HaSpace.M.rdp)
        .size(7.rdp)
        .clip(RemoteRoundedCornerShape(4.rdp))
        .background(theme.linkText)
    )
    TileName(data, look, HaType.Label, FontWeight.Normal, modifier = RemoteModifier.weight(1f))
    TileValue(data, look, HaType.Title, modifier = RemoteModifier.padding(start = HaSpace.M.rdp))
  }
}

/** Nixie: the reading in big glowing digits, the name a small caption above it. */
@Composable
private fun NixieTile(data: HaTileData, look: HaEntityLook, identity: @Composable () -> Unit) {
  RemoteRow(RemoteModifier.fillMaxWidth(), verticalAlignment = RemoteAlignment.CenterVertically) {
    identity()
    RemoteColumn(RemoteModifier.weight(1f).padding(start = 12.rdp)) {
      TileName(data, look, HaType.Caption, FontWeight.Normal, secondary = true)
      TileValue(data, look, HaType.Value, FontWeight.Light)
    }
  }
}

/** Console: the name on service tape, the reading beside a pilot lamp. */
@Composable
private fun ConsoleTile(data: HaTileData, look: HaEntityLook, identity: @Composable () -> Unit) {
  RemoteRow(RemoteModifier.fillMaxWidth(), verticalAlignment = RemoteAlignment.CenterVertically) {
    identity()
    RemoteColumn(
      RemoteModifier.weight(1f).padding(start = 10.rdp),
      verticalArrangement = RemoteArrangement.spacedBy(HaSpace.XS.rdp),
    ) {
      ConsoleTapeLabel(entityName(data.entityId, data.name), compact = true)
      RemoteRow(verticalAlignment = RemoteAlignment.CenterVertically) {
        RemoteBox(
          RemoteModifier.size(8.rdp).clip(RemoteRoundedCornerShape(4.rdp)).background(look.accent)
        )
        TileValue(data, look, HaType.Body, modifier = RemoteModifier.padding(start = HaSpace.S.rdp))
      }
    }
  }
}

/** IKEA: a product tag — bold name over a large reading. */
@Composable
private fun IkeaTile(data: HaTileData, look: HaEntityLook, identity: @Composable () -> Unit) {
  RemoteRow(RemoteModifier.fillMaxWidth(), verticalAlignment = RemoteAlignment.CenterVertically) {
    identity()
    RemoteColumn(RemoteModifier.weight(1f).padding(start = 12.rdp)) {
      TileName(data, look, HaType.Label, FontWeight.Bold)
      TileValue(data, look, HaType.Value, FontWeight.Bold)
    }
  }
}

/** Mushroom: the identity in a round tinted cap, the name over a bold reading. */
@Composable
private fun MushroomTile(data: HaTileData, look: HaEntityLook, identity: @Composable () -> Unit) {
  RemoteRow(RemoteModifier.fillMaxWidth(), verticalAlignment = RemoteAlignment.CenterVertically) {
    RemoteBox(
      RemoteModifier.size(42.rdp)
        .clip(RemoteRoundedCornerShape(21.rdp))
        .background(look.accent.copy(alpha = look.accent.alpha * 0.2f.rf)),
      contentAlignment = RemoteAlignment.Center,
    ) {
      identity()
    }
    RemoteColumn(RemoteModifier.weight(1f).padding(start = 12.rdp)) {
      TileName(data, look, HaType.Label, FontWeight.Normal, secondary = true)
      TileValue(data, look, HaType.BodyLarge, FontWeight.Bold)
    }
  }
}

/** Catppuccin: the reading on a pastel lozenge under the name. */
@Composable
private fun CatppuccinTile(data: HaTileData, look: HaEntityLook, identity: @Composable () -> Unit) {
  val theme = haTheme()
  RemoteRow(RemoteModifier.fillMaxWidth(), verticalAlignment = RemoteAlignment.CenterVertically) {
    identity()
    RemoteColumn(
      RemoteModifier.weight(1f).padding(start = 10.rdp),
      verticalArrangement = RemoteArrangement.spacedBy(HaSpace.XS.rdp),
    ) {
      TileName(data, look, HaType.Label)
      RemoteBox(
        RemoteModifier.clip(RemoteRoundedCornerShape(HaSpace.M.rdp))
          .background(theme.placeholderBackground)
          .padding(horizontal = HaSpace.M.rdp, vertical = HaSpace.XXS.rdp)
      ) {
        TileValue(data, look, HaType.Label)
      }
    }
  }
}

/** Obsidian: editorial — the reading set large, ruled off above a small name. */
@Composable
private fun ObsidianTile(data: HaTileData, look: HaEntityLook, identity: @Composable () -> Unit) {
  val theme = haTheme()
  RemoteRow(RemoteModifier.fillMaxWidth(), verticalAlignment = RemoteAlignment.CenterVertically) {
    identity()
    RemoteColumn(RemoteModifier.weight(1f).padding(start = 12.rdp)) {
      TileValue(data, look, HaType.Title, FontWeight.Normal)
      RemoteBox(
        RemoteModifier.padding(vertical = HaSpace.XXS.rdp)
          .width(32.rdp)
          .height(HaSpace.Hairline.rdp)
          .background(theme.secondaryText)
      )
      TileName(data, look, HaType.Caption, FontWeight.Normal, secondary = true)
    }
  }
}

/** Noctis: twin rails down the leading edge, the name over the reading. */
@Composable
private fun NoctisTile(data: HaTileData, look: HaEntityLook, identity: @Composable () -> Unit) {
  val theme = haTheme()
  RemoteRow(RemoteModifier.fillMaxWidth(), verticalAlignment = RemoteAlignment.CenterVertically) {
    RemoteBox(RemoteModifier.width(2.rdp).height(38.rdp).background(look.accent))
    RemoteBox(
      RemoteModifier.padding(start = 3.rdp, end = HaSpace.XL.rdp)
        .width(1.rdp)
        .height(24.rdp)
        .background(theme.divider)
    )
    identity()
    RemoteColumn(RemoteModifier.weight(1f).padding(start = 12.rdp)) {
      TileName(data, look, HaType.Label, FontWeight.Normal, secondary = true)
      TileValue(data, look, HaType.BodyLarge)
    }
  }
}

/** LCARS: a command key — a coloured end cap, the name in the band, the reading at the end. */
@Composable
private fun LcarsTile(data: HaTileData, look: HaEntityLook, identity: @Composable () -> Unit) {
  RemoteRow(RemoteModifier.fillMaxWidth(), verticalAlignment = RemoteAlignment.CenterVertically) {
    RemoteBox(
      RemoteModifier.width(18.rdp)
        .height(36.rdp)
        .clip(RemoteRoundedCornerShape(topStart = 18.rdp, bottomStart = 18.rdp))
        .background(look.accent)
    )
    RemoteBox(RemoteModifier.padding(start = HaSpace.M.rdp)) { identity() }
    TileName(
      data,
      look,
      HaType.Label,
      FontWeight.Bold,
      modifier = RemoteModifier.weight(1f).padding(horizontal = HaSpace.M.rdp),
    )
    TileValue(data, look, HaType.Title)
  }
}

/** Aeon: a framed marquee key — a diamond index before the name, the reading under it. */
@Composable
private fun AeonTile(data: HaTileData, look: HaEntityLook, identity: @Composable () -> Unit) {
  val theme = haTheme()
  RemoteRow(
    RemoteModifier.fillMaxWidth()
      .border(1.rdp, theme.divider, RemoteRoundedCornerShape(0.rdp))
      .padding(3.rdp)
      .border(1.rdp, theme.divider, RemoteRoundedCornerShape(0.rdp))
      .padding(horizontal = HaSpace.M.rdp, vertical = HaSpace.XS.rdp),
    verticalAlignment = RemoteAlignment.CenterVertically,
  ) {
    identity()
    RemoteColumn(RemoteModifier.weight(1f).padding(start = 10.rdp)) {
      RemoteRow(verticalAlignment = RemoteAlignment.CenterVertically) {
        RemoteText(
          "◆ ".rs,
          color = look.accent,
          fontSize = HaType.Caption,
          style = haTextStyle(),
          maxLines = 1,
        )
        TileName(data, look, HaType.Label)
      }
      TileValue(data, look, HaType.BodyLarge)
    }
  }
}
