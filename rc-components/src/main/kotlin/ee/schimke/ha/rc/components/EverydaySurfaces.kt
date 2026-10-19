@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.*
import androidx.compose.remote.creation.compose.modifier.*
import androidx.compose.remote.creation.compose.shapes.RemoteCircleShape
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.*
import androidx.compose.remote.creation.compose.text.RemoteFontFamily
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

// ---------------------------------------------------------------------------------------------
// The everyday text cards per visual family (docs/design/everyday-cards.md §3 and §4): a heading's
// treatment and the material a markdown note is made of. Classic and Home Assistant keep HA's own
// look; every other family changes structure — what the text sits on, what marks it — not just
// its palette. Text stays live: these wrap the caller's RemoteStrings, never repaint them.
// ---------------------------------------------------------------------------------------------

/** The theme's text style with [trackingSp] of letter spacing and, optionally, another family. */
@Composable
internal fun trackedTextStyle(
  trackingSp: Float,
  family: RemoteFontFamily? = null,
): RemoteTextStyle {
  val theme = currentHaUberTheme().theme
  return RemoteTextStyle.Default.merge(
    RemoteTextStyle(fontFamily = family ?: theme.fontFamily, letterSpacing = trackingSp.rsp)
  )
}

/** The theme's ink (LCD, Nixie) or its primary text. */
@Composable
private fun inkOrPrimary(): RemoteColor = currentHaUberTheme().ink?.rc ?: haTheme().primaryText

/** The theme's accent: Braun's orange, Mushroom's tint, LCARS's band — its link colour. */
@Composable private fun familyAccent(): RemoteColor = haTheme().linkText

/** True when the families' own decoration should draw (embedded cards skip their chrome). */
@Composable private fun decorated(): Boolean = LocalCardChromeEnabled.current

/**
 * A heading in the active family's treatment. [size] and [weight] are the HA style's; families that
 * restyle the type keep the size's rank (a subtitle stays smaller than a title).
 */
@Composable
@RemoteComposable
internal fun EverydayHeading(
  text: RemoteString,
  size: RemoteTextUnit,
  weight: FontWeight,
  modifier: RemoteModifier,
) {
  val theme = haTheme()
  val family = visualFamily()
  val plain: @Composable (RemoteModifier) -> Unit = { m ->
    RemoteText(
      text = text,
      modifier = m,
      color = theme.primaryText,
      fontSize = size,
      fontWeight = weight,
      style = haTextStyle(),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
  val row: @Composable (@Composable RemoteRowScope.() -> Unit) -> Unit = { content ->
    RemoteRow(
      modifier = modifier.fillMaxWidth().padding(vertical = 8.rdp),
      verticalAlignment = RemoteAlignment.CenterVertically,
      horizontalArrangement = RemoteArrangement.spacedBy(HaSpace.S.rdp),
    ) {
      content()
    }
  }
  val column: @Composable (@Composable () -> Unit) -> Unit = { content ->
    RemoteColumn(
      modifier = modifier.fillMaxWidth().padding(vertical = 8.rdp),
      verticalArrangement = RemoteArrangement.spacedBy(HaSpace.XS.rdp),
    ) {
      content()
    }
  }
  when (family) {
    HaVisualFamily.Classic,
    HaVisualFamily.HomeAssistant ->
      RemoteText(
        text = text,
        modifier = modifier.padding(vertical = 8.rdp),
        color = theme.primaryText,
        fontSize = size,
        fontWeight = weight,
        style = haTextStyle(),
      )
    // A vertical tonal capsule leading a heavy headline: M3 Expressive's section marker.
    HaVisualFamily.Expressive ->
      row {
        RemoteBox(
          RemoteModifier.size(6.rdp, 24.rdp).clip(RemoteCircleShape).background(familyAccent())
        )
        plain(RemoteModifier.weight(1f))
      }
    // A tracked label, a red power LED before it and a hairline rule running to the edge.
    HaVisualFamily.Trinitron ->
      row {
        RemoteBox(
          RemoteModifier.size(7.rdp)
            .clip(RemoteCircleShape)
            .background((currentHaUberTheme().hotInk ?: HaStatusColors.Critical).rc)
        )
        RemoteText(
          text = text,
          color = theme.primaryText,
          fontSize = HaType.Title,
          fontWeight = FontWeight.Medium,
          style = trackedTextStyle(2.4f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        RemoteBox(RemoteModifier.weight(1f).height(HaSpace.Hairline.rdp).background(theme.divider))
      }
    // The title in a recessed screen strip, a block cursor after it.
    HaVisualFamily.Lcd ->
      RemoteRow(
        modifier =
          modifier
            .fillMaxWidth()
            .padding(vertical = 6.rdp)
            .clip(RemoteRoundedCornerShape(HaSpace.XXS.rdp))
            .background(theme.dashboardBackground)
            .padding(horizontal = HaSpace.M.rdp, vertical = HaSpace.XS.rdp),
        verticalAlignment = RemoteAlignment.CenterVertically,
        horizontalArrangement = RemoteArrangement.spacedBy(HaSpace.XS.rdp),
      ) {
        RemoteText(
          text = text,
          color = inkOrPrimary(),
          fontSize = size,
          style = haTextStyle(),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        RemoteBox(RemoteModifier.size(9.rdp, 18.rdp).background(inkOrPrimary()))
      }
    // Small tracked type beside Braun's orange index dot.
    HaVisualFamily.Braun ->
      row {
        RemoteBox(RemoteModifier.size(9.rdp).clip(RemoteCircleShape).background(familyAccent()))
        RemoteText(
          text = text,
          color = theme.primaryText,
          fontSize = HaType.Title,
          fontWeight = FontWeight.Medium,
          style = trackedTextStyle(1.6f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    // Glowing numerals: a heavy halo pass in the faded ink under the lit text.
    HaVisualFamily.Nixie -> {
      val ink = currentHaUberTheme().ink
      RemoteBox(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.rdp),
        contentAlignment = RemoteAlignment.CenterStart,
      ) {
        RemoteText(
          text = text,
          color = ink?.copy(alpha = 0.35f)?.rc ?: theme.secondaryText,
          fontSize = size,
          fontWeight = FontWeight.Black,
          style = trackedTextStyle(1.2f),
          maxLines = 1,
        )
        RemoteText(
          text = text,
          color = inkOrPrimary(),
          fontSize = size,
          fontWeight = FontWeight.Normal,
          style = trackedTextStyle(1.2f),
          maxLines = 1,
        )
      }
    }
    // Tape stuck to the desk above the section.
    HaVisualFamily.Console ->
      RemoteBox(modifier.padding(vertical = 6.rdp)) { ConsoleTapeLabel(text) }
    // An assembly leaflet's step marker: a black disc with an arrow, then the bold title.
    HaVisualFamily.Ikea ->
      row {
        RemoteBox(
          RemoteModifier.size(26.rdp).clip(RemoteCircleShape).background(theme.primaryText),
          contentAlignment = RemoteAlignment.Center,
        ) {
          RemoteText(
            text = "→".rs,
            color = theme.cardBackground,
            fontSize = HaType.Body,
            fontWeight = FontWeight.Bold,
            style = haTextStyle(),
          )
        }
        RemoteText(
          text = text,
          color = theme.primaryText,
          fontSize = size,
          fontWeight = FontWeight.Bold,
          style = haTextStyle(),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    // A big friendly title over a rounded accent underline.
    HaVisualFamily.Mushroom ->
      column {
        RemoteText(
          text = text,
          color = theme.primaryText,
          fontSize = HaType.Headline,
          fontWeight = FontWeight.Bold,
          style = haTextStyle(),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        RemoteBox(
          RemoteModifier.size(36.rdp, 5.rdp).clip(RemoteCircleShape).background(familyAccent())
        )
      }
    // The title floating in its own capsule.
    HaVisualFamily.Bubble ->
      RemoteBox(modifier.padding(vertical = 6.rdp)) {
        RemoteBox(
          RemoteModifier.clip(RemoteCircleShape)
            .background(theme.cardBackground)
            .padding(horizontal = HaSpace.L.rdp, vertical = HaSpace.S.rdp)
        ) {
          RemoteText(
            text = text,
            color = theme.primaryText,
            fontSize = HaType.Title,
            fontWeight = FontWeight.SemiBold,
            style = haTextStyle(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    // A quiet module caption: small, muted, widely tracked.
    HaVisualFamily.Minimalist ->
      RemoteText(
        text = text,
        modifier = modifier.padding(top = 12.rdp, bottom = 4.rdp),
        color = theme.secondaryText,
        fontSize = HaType.Label,
        fontWeight = FontWeight.SemiBold,
        style = trackedTextStyle(2.4f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    // iOS Large Title: big, heavy, tight.
    HaVisualFamily.Cupertino ->
      RemoteText(
        text = text,
        modifier = modifier.padding(top = 10.rdp, bottom = 4.rdp),
        color = theme.primaryText,
        fontSize = if (size == HaType.Title) HaType.ValueSmall else HaType.Value,
        fontWeight = FontWeight.Bold,
        style = trackedTextStyle(-0.4f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    // The title on a pastel lozenge chip.
    HaVisualFamily.Catppuccin ->
      RemoteBox(modifier.padding(vertical = 6.rdp)) {
        RemoteBox(
          RemoteModifier.clip(RemoteRoundedCornerShape(8.rdp))
            .background(theme.placeholderBackground)
            .padding(horizontal = HaSpace.M.rdp, vertical = HaSpace.XS.rdp)
        ) {
          RemoteText(
            text = text,
            color = theme.primaryText,
            fontSize = HaType.Title,
            fontWeight = FontWeight.Medium,
            style = haTextStyle(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    // A notebook heading: the muted `#` marker kept, serif title, hairline under it.
    HaVisualFamily.Obsidian ->
      column {
        RemoteRow(verticalAlignment = RemoteAlignment.CenterVertically) {
          RemoteText(
            text = "# ".rs,
            color = theme.secondaryText,
            fontSize = size,
            style = trackedTextStyle(0f, RemoteFontFamily.Monospace),
          )
          RemoteText(
            text = text,
            color = theme.primaryText,
            fontSize = size,
            fontWeight = FontWeight.Normal,
            style = trackedTextStyle(0f, RemoteFontFamily.Serif),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        RemoteBox(
          RemoteModifier.fillMaxWidth().height(HaSpace.Hairline.rdp).background(theme.divider)
        )
      }
    // Twin navigation rails before the title.
    HaVisualFamily.Noctis ->
      row {
        TwinRails(familyAccent(), 20)
        plain(RemoteModifier.weight(1f))
      }
    // An LCARS elbow band: a coloured bar rounded at the left, the title at its right end.
    HaVisualFamily.Lcars ->
      row {
        RemoteBox(
          RemoteModifier.weight(1f)
            .height(22.rdp)
            .clip(RemoteRoundedCornerShape(11.rdp, 0.rdp, 0.rdp, 11.rdp))
            .background(familyAccent())
        )
        RemoteText(
          text = text,
          color = theme.primaryText,
          fontSize = HaType.Title,
          fontWeight = FontWeight.Bold,
          style = trackedTextStyle(1.8f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    // Thin, widely tracked type over a short ice rule.
    HaVisualFamily.Arctic ->
      column {
        RemoteText(
          text = text,
          color = theme.primaryText,
          fontSize = size,
          fontWeight = FontWeight.Light,
          style = trackedTextStyle(3f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        RemoteBox(RemoteModifier.size(56.rdp, 2.rdp).background(familyAccent()))
      }
    // A cinema marquee: the title between diamond indexes, ruled above and below.
    HaVisualFamily.Aeon ->
      column {
        RemoteBox(
          RemoteModifier.fillMaxWidth().height(HaSpace.Hairline.rdp).background(familyAccent())
        )
        RemoteRow(
          modifier = RemoteModifier.fillMaxWidth(),
          verticalAlignment = RemoteAlignment.CenterVertically,
          horizontalArrangement =
            RemoteArrangement.spacedBy(HaSpace.S.rdp, RemoteAlignment.CenterHorizontally),
        ) {
          RemoteText(
            "◆".rs,
            color = familyAccent(),
            fontSize = HaType.Caption,
            style = haTextStyle(),
          )
          RemoteText(
            text = text,
            color = theme.primaryText,
            fontSize = HaType.Title,
            style = trackedTextStyle(2f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          RemoteText(
            "◆".rs,
            color = familyAccent(),
            fontSize = HaType.Caption,
            style = haTextStyle(),
          )
        }
        RemoteBox(
          RemoteModifier.fillMaxWidth().height(HaSpace.Hairline.rdp).background(familyAccent())
        )
      }
  }
}

/** Two thin vertical rails [heightDp] tall: Noctis's navigation mark. */
@Composable
@RemoteComposable
internal fun TwinRails(color: RemoteColor, heightDp: Int) {
  RemoteRow(horizontalArrangement = RemoteArrangement.spacedBy(3.rdp)) {
    RemoteBox(RemoteModifier.size(2.rdp, heightDp.rdp).background(color))
    RemoteBox(RemoteModifier.size(2.rdp, heightDp.rdp).background(color.copy(alpha = 0.5f.rf)))
  }
}

// ---------------------------------------------------------------------------------------------
// Markdown material
// ---------------------------------------------------------------------------------------------

/** How a markdown note's family dresses it: its surface, its title and its block markers. */
internal class MarkdownMaterial(
  /** The note's outer surface (replaces the theme's card chrome). */
  val surface: RemoteModifier,
  /**
   * The family's marks (a rule, a bar, a seam), drawn on their own layer over the whole surface: a
   * community family's frame already draws through the surface, and a second draw pass chained
   * after it is dropped.
   */
  val decoration: RemoteModifier,
  /** Padding inside [surface]. */
  val padding: RemoteModifier,
  /** An inner panel the blocks sit in (LCD and Console's recessed screen), else none. */
  val screen: RemoteModifier?,
  /** The body ink. */
  val ink: RemoteColor,
  /** Body weight for paragraphs and bullets. */
  val bodyWeight: FontWeight,
  /** The body's text style (Obsidian's serif, the theme's own otherwise). */
  val bodyStyle: RemoteTextStyle,
  /** Obsidian keeps the source markers (`#`, `-`) visible, muted. */
  val showsMarkers: Boolean,
  /** IKEA numbers its headings like assembly steps. */
  val numbersHeadings: Boolean,
  /** Cupertino separates blocks with inset hairlines like a grouped list. */
  val dividesBlocks: Boolean,
)

@Composable
internal fun markdownMaterial(): MarkdownMaterial {
  val theme = haTheme()
  val family = visualFamily()
  val chromeOn = decorated()
  val standardPadding = RemoteModifier.padding(horizontal = 12.rdp, vertical = 10.rdp)
  val chrome = cardChrome(theme.cardBackground, theme.divider)
  fun material(
    surface: RemoteModifier = chrome,
    decoration: RemoteModifier? = null,
    padding: RemoteModifier = standardPadding,
    screen: RemoteModifier? = null,
    ink: RemoteColor = theme.primaryText,
    bodyWeight: FontWeight = FontWeight.Normal,
    bodyStyle: RemoteTextStyle? = null,
    showsMarkers: Boolean = false,
    numbersHeadings: Boolean = false,
    dividesBlocks: Boolean = false,
  ) =
    MarkdownMaterial(
      surface = surface,
      decoration = decoration?.takeIf { chromeOn } ?: RemoteModifier,
      padding = padding,
      screen = screen,
      ink = ink,
      bodyWeight = bodyWeight,
      bodyStyle = bodyStyle ?: RemoteTextStyle.Default,
      showsMarkers = showsMarkers,
      numbersHeadings = numbersHeadings,
      dividesBlocks = dividesBlocks,
    )
  val haStyle = haTextStyle()
  return when (family) {
    HaVisualFamily.Classic,
    HaVisualFamily.HomeAssistant,
    HaVisualFamily.Trinitron -> material(bodyStyle = haStyle)
    // A tonal container: the section tone, a large corner, no stroke.
    HaVisualFamily.Expressive ->
      material(
        surface = cardChrome(theme.sectionBackground, theme.divider, 28, 0),
        padding = RemoteModifier.padding(horizontal = 18.rdp, vertical = 14.rdp),
        bodyStyle = haStyle,
      )
    // The note on the segment screen itself.
    HaVisualFamily.Lcd ->
      material(
        screen = screenPanel(theme.dashboardBackground),
        ink = inkOrPrimary(),
        bodyStyle = trackedTextStyle(0.6f, RemoteFontFamily.Monospace),
      )
    // A plain sheet, an index rule with ticks down its left edge.
    HaVisualFamily.Braun ->
      material(
        decoration = indexRule(theme.secondaryText),
        padding =
          RemoteModifier.padding(start = 24.rdp, end = 12.rdp, top = 10.rdp, bottom = 10.rdp),
        bodyStyle = haStyle,
      )
    // Amber on black glass, a sheen across the top of the glass.
    HaVisualFamily.Nixie ->
      material(
        decoration = glassSheen(),
        ink = inkOrPrimary(),
        bodyStyle = haStyle,
      )
    // The note on the desk's recessed screen; the title is tape stuck above it.
    HaVisualFamily.Console -> material(screen = screenPanel(ConsoleSlot.rc), bodyStyle = haStyle)
    // An assembly leaflet: bold, numbered steps.
    HaVisualFamily.Ikea ->
      material(bodyWeight = FontWeight.Medium, bodyStyle = haStyle, numbersHeadings = true)
    // A soft card with a coloured bar leading the note.
    HaVisualFamily.Mushroom ->
      material(
        decoration = leadingBar(familyAccent()),
        padding =
          RemoteModifier.padding(start = 24.rdp, end = 12.rdp, top = 12.rdp, bottom = 12.rdp),
        bodyStyle = haStyle,
      )
    // A speech bubble: round capsule corners (the tail is drawn by the card).
    HaVisualFamily.Bubble ->
      material(
        surface = cardChrome(theme.cardBackground, theme.divider, 22),
        padding = RemoteModifier.padding(horizontal = 18.rdp, vertical = 14.rdp),
        bodyStyle = haStyle,
      )
    // No card at all: a hairline down the left.
    HaVisualFamily.Minimalist ->
      material(
        surface = RemoteModifier,
        decoration =
          RemoteModifier.drawWithContent {
            fillRect(theme.divider, 0f.rf, 0f.rf, 4f.rf, height)
            drawContent()
          },
        padding = RemoteModifier.padding(start = 14.rdp, top = 4.rdp, bottom = 4.rdp),
        ink = theme.secondaryText,
        bodyStyle = haStyle,
      )
    // An inset grouped panel, blocks split by inset hairlines.
    HaVisualFamily.Cupertino ->
      material(
        surface = cardChrome(theme.sectionBackground, theme.divider, 12, 0),
        bodyStyle = haStyle,
        dividesBlocks = true,
      )
    // Stitched: a dashed seam inset from the edge.
    HaVisualFamily.Catppuccin ->
      material(
        decoration = stitchedSeam(theme.secondaryText),
        padding = RemoteModifier.padding(horizontal = 16.rdp, vertical = 14.rdp),
        bodyStyle = haStyle,
      )
    // A notebook: serif body, the source markers kept in the margin.
    HaVisualFamily.Obsidian ->
      material(bodyStyle = trackedTextStyle(0f, RemoteFontFamily.Serif), showsMarkers = true)
    // Twin rails down the left edge.
    HaVisualFamily.Noctis ->
      material(
        decoration = twinRailsEdge(familyAccent()),
        padding =
          RemoteModifier.padding(start = 24.rdp, end = 12.rdp, top = 10.rdp, bottom = 10.rdp),
        bodyStyle = haStyle,
      )
    // The family frame already draws the elbow and the side bar.
    HaVisualFamily.Lcars ->
      material(
        padding =
          RemoteModifier.padding(start = 14.rdp, end = 12.rdp, top = 12.rdp, bottom = 12.rdp),
        bodyStyle = haStyle,
      )
    // Frosted: the card faded into the sky, no stroke, light type.
    HaVisualFamily.Arctic ->
      material(
        surface = cardChrome(theme.cardBackground.copy(alpha = 0.55f.rf), theme.divider, null, 0),
        bodyWeight = FontWeight.Light,
        bodyStyle = haStyle,
      )
    // A double-ruled frame.
    HaVisualFamily.Aeon ->
      material(
        decoration = doubleFrame(familyAccent()),
        padding = RemoteModifier.padding(horizontal = 16.rdp, vertical = 14.rdp),
        bodyStyle = haStyle,
      )
  }
}

/** A recessed screen the note's blocks sit in. */
private fun screenPanel(color: RemoteColor): RemoteModifier =
  RemoteModifier.fillMaxWidth()
    .clip(RemoteRoundedCornerShape(3.rdp))
    .background(color)
    .padding(horizontal = 10.rdp, vertical = 8.rdp)

/** A vertical index rule at the left edge, a tick every sixteenth of its length. */
private fun indexRule(color: RemoteColor): RemoteModifier = RemoteModifier.drawWithContent {
  val rule = color.copy(alpha = 0.6f.rf)
  val top = height * 0.18f.rf
  val span = height * 0.64f.rf
  fillRect(rule, 30f.rf, top, 2.5f.rf, span)
  for (i in 0..16) {
    val y = top + span * (i / 16f).rf
    val major = i % 4 == 0
    fillRect(rule, (if (major) 14f else 20f).rf, y, (if (major) 16f else 10f).rf, 2f.rf)
  }
  drawContent()
}

/** A coloured bar with round ends leading the content. */
private fun leadingBar(color: RemoteColor): RemoteModifier = RemoteModifier.drawWithContent {
  fillRound(color, 20f.rf, height * 0.16f.rf, 12f.rf, height * 0.68f.rf, 6f.rf)
  drawContent()
}

/** Two rails down the left edge. */
private fun twinRailsEdge(color: RemoteColor): RemoteModifier = RemoteModifier.drawWithContent {
  fillRect(color, 20f.rf, height * 0.16f.rf, 5f.rf, height * 0.68f.rf)
  fillRect(color.copy(alpha = 0.5f.rf), 32f.rf, height * 0.16f.rf, 5f.rf, height * 0.68f.rf)
  drawContent()
}

/** A glass sheen over the top third, under the text. */
private fun glassSheen(): RemoteModifier = RemoteModifier.drawWithContent {
  fillRect(Color.White.copy(alpha = 0.07f).rc, 0f.rf, 0f.rf, width, height * 0.32f.rf)
  fillRound(Color.White.copy(alpha = 0.16f).rc, 24f.rf, 8f.rf, width - 48f.rf, 3f.rf, 1.5f.rf)
  drawContent()
}

/** A dashed seam 12 px in from every edge: dashes spaced by fractions so any size closes. */
private fun stitchedSeam(color: RemoteColor): RemoteModifier = RemoteModifier.drawWithContent {
  drawContent()
  val seam = color.copy(alpha = 0.55f.rf)
  val inset = 12f.rf
  val w = width - inset * 2f.rf
  val h = height - inset * 2f.rf
  val across = 36
  val down = 12
  for (i in 0 until across) {
    val x = inset + w * (i.toFloat() / across).rf
    fillRect(seam, x, inset, w / (across * 2f).rf, 2.5f.rf)
    fillRect(seam, x, height - inset - 2.5f.rf, w / (across * 2f).rf, 2.5f.rf)
  }
  for (i in 0 until down) {
    val y = inset + h * (i.toFloat() / down).rf
    fillRect(seam, inset, y, 2.5f.rf, h / (down * 2f).rf)
    fillRect(seam, width - inset - 2.5f.rf, y, 2.5f.rf, h / (down * 2f).rf)
  }
}

/** Two ruled frames, the inner one 10 px inside the outer. */
private fun doubleFrame(color: RemoteColor): RemoteModifier = RemoteModifier.drawWithContent {
  drawContent()
  outlineRect(color, 8f.rf, 8f.rf, width - 16f.rf, height - 16f.rf, 2.5f.rf)
  outlineRect(color.copy(alpha = 0.6f.rf), 18f.rf, 18f.rf, width - 36f.rf, height - 36f.rf, 1.5f.rf)
}

/** The note's title in its family's treatment. */
@Composable
@RemoteComposable
internal fun MarkdownTitle(title: RemoteString, size: RemoteTextUnit, ink: RemoteColor) {
  val theme = haTheme()
  when (visualFamily()) {
    HaVisualFamily.Trinitron ->
      RemoteColumn(modifier = RemoteModifier.fillMaxWidth()) {
        RemoteText(
          text = title,
          color = theme.secondaryText,
          fontSize = HaType.Label,
          fontWeight = FontWeight.Medium,
          style = trackedTextStyle(2.4f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        RemoteBox(
          RemoteModifier.padding(top = HaSpace.XXS.rdp)
            .fillMaxWidth()
            .height(HaSpace.Hairline.rdp)
            .background(theme.divider)
        )
      }
    HaVisualFamily.Console -> ConsoleTapeLabel(title, compact = true)
    HaVisualFamily.Obsidian ->
      RemoteRow(verticalAlignment = RemoteAlignment.CenterVertically) {
        RemoteText(
          "# ".rs,
          color = theme.secondaryText,
          fontSize = size,
          style = trackedTextStyle(0f, RemoteFontFamily.Monospace),
        )
        RemoteText(
          text = title,
          color = ink,
          fontSize = size,
          style = trackedTextStyle(0f, RemoteFontFamily.Serif),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    HaVisualFamily.Ikea,
    HaVisualFamily.Mushroom ->
      RemoteText(
        text = title,
        color = ink,
        fontSize = size,
        fontWeight = FontWeight.Bold,
        style = haTextStyle(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    HaVisualFamily.Lcars,
    HaVisualFamily.Aeon,
    HaVisualFamily.Arctic ->
      RemoteText(
        text = title,
        color = ink,
        fontSize = size,
        fontWeight =
          if (visualFamily() == HaVisualFamily.Arctic) FontWeight.Light else FontWeight.Medium,
        style = trackedTextStyle(2f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    else ->
      RemoteText(
        text = title,
        color = ink,
        fontSize = size,
        fontWeight = FontWeight.Medium,
        style = haTextStyle(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
  }
}

/** IKEA's step number: a black disc with a white numeral. */
@Composable
@RemoteComposable
internal fun LeafletStep(number: Int) {
  val theme = haTheme()
  RemoteBox(
    RemoteModifier.size(22.rdp).clip(RemoteCircleShape).background(theme.primaryText),
    contentAlignment = RemoteAlignment.Center,
  ) {
    RemoteText(
      text = number.toString().rs,
      color = theme.cardBackground,
      fontSize = HaType.Label,
      fontWeight = FontWeight.Bold,
      style = haTextStyle(),
    )
  }
}

/** Bubble's speech tail: two shrinking dots trailing from the bubble's lower left. */
@Composable
@RemoteComposable
internal fun BubbleTail() {
  val theme = haTheme()
  RemoteRow(
    modifier = RemoteModifier.padding(start = 18.rdp, top = 3.rdp),
    verticalAlignment = RemoteAlignment.Top,
    horizontalArrangement = RemoteArrangement.spacedBy(3.rdp),
  ) {
    RemoteBox(RemoteModifier.size(13.rdp).clip(RemoteCircleShape).background(theme.cardBackground))
    RemoteBox(
      RemoteModifier.padding(top = 10.rdp)
        .size(8.rdp)
        .clip(RemoteCircleShape)
        .background(theme.cardBackground)
    )
  }
}
