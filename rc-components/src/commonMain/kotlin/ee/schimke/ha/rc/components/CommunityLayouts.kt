@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.*
import androidx.compose.remote.creation.compose.modifier.*
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow

/** Family chrome travels with every card type, including cards without a numeric meter. */
internal fun communityFrame(family: HaVisualFamily, ink: RemoteColor): RemoteModifier {
  val frame = RemoteModifier.drawWithContent {
    drawContent()
    when (family) {
      HaVisualFamily.Lcars -> {
        fillRound(ink, 0f.rf, 0f.rf, 9f.rf, height, 4f.rf)
        fillRect(ink, 0f.rf, 0f.rf, width * .42f.rf, 5f.rf)
        fillRect(ink, 0f.rf, height - 5f.rf, width * .7f.rf, 5f.rf)
      }
      HaVisualFamily.Catppuccin -> {
        for (i in 0..2) fillRound(ink, (9 + i * 9).rf, 5f.rf, 5f.rf, 3f.rf, 1f.rf)
      }
      HaVisualFamily.Obsidian -> fillRect(ink, 7f.rf, 0f.rf, 1f.rf, height)
      HaVisualFamily.Noctis -> {
        fillRect(ink, width - 5f.rf, 12f.rf, 2f.rf, height - 24f.rf)
      }
      HaVisualFamily.Arctic -> fillRect(ink, 0f.rf, height - 2f.rf, width * .32f.rf, 2f.rf)
      HaVisualFamily.Aeon -> {
        fillRect(ink, 5f.rf, 5f.rf, 18f.rf, 2f.rf)
        fillRect(ink, 5f.rf, 5f.rf, 2f.rf, 12f.rf)
        fillRect(ink, width - 23f.rf, height - 7f.rf, 18f.rf, 2f.rf)
        fillRect(ink, width - 7f.rf, height - 17f.rf, 2f.rf, 12f.rf)
      }
      else -> Unit
    }
  }
  return frame.then(
    when (family) {
      HaVisualFamily.Lcars -> RemoteModifier.padding(start = 10.rdp, top = 5.rdp, bottom = 5.rdp)
      HaVisualFamily.Catppuccin -> RemoteModifier.padding(top = 6.rdp)
      HaVisualFamily.Obsidian -> RemoteModifier.padding(start = 8.rdp)
      else -> RemoteModifier
    }
  )
}

/**
 * Composition belongs to the family, while values/actions remain the caller's existing bindings. A
 * single fixed-height region reflows the same semantic slots; no text is painted into a canvas.
 */
@Composable
internal fun CommunityReadingLayout(
  name: RemoteString,
  value: RemoteString,
  detail: RemoteString?,
  fraction: RemoteFloat,
  color: RemoteColor,
  modifier: RemoteModifier,
  small: Boolean = false,
  meter: @Composable (RemoteModifier) -> Unit,
) {
  val theme = haTheme()
  val family = visualFamily()
  val label: @Composable () -> Unit = {
    RemoteText(
      name,
      color = theme.secondaryText,
      fontSize = HaType.Label,
      style = haTextStyle(),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
  val reading: @Composable () -> Unit = {
    RemoteText(
      value,
      color = theme.primaryText,
      fontSize = if (small) HaType.ValueSmall else HaType.ValueLarge,
      fontWeight = haValueWeight(),
      style = haTextStyle(),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
  val foot: @Composable () -> Unit = {
    if (detail != null)
      RemoteText(
        detail,
        color = theme.secondaryText,
        fontSize = HaType.Micro,
        style = haTextStyle(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
  }
  val rule: @Composable () -> Unit = {
    RemoteBox(RemoteModifier.fillMaxWidth().height(1.rdp).background(theme.divider))
  }
  RemoteColumn(modifier) {
    when (family) {
      HaVisualFamily.Mushroom -> {
        RemoteRow(
          RemoteModifier.fillMaxWidth().weight(1f),
          verticalAlignment = RemoteAlignment.CenterVertically,
        ) {
          RemoteBox(
            RemoteModifier.size((if (small) 40 else 64).rdp)
              .clip(RemoteRoundedCornerShape((if (small) 20 else 32).rdp))
              .background(theme.placeholderBackground)
              .padding(8.rdp)
          ) {
            meter(RemoteModifier.fillMaxSize())
          }
          RemoteColumn(RemoteModifier.weight(1f).padding(start = 12.rdp)) {
            label()
            reading()
          }
        }
        foot()
      }
      HaVisualFamily.Bubble -> {
        if (small) {
          RemoteColumn(
            RemoteModifier.fillMaxWidth().weight(1f),
            verticalArrangement = RemoteArrangement.Center,
          ) {
            label()
            RemoteBox(
              RemoteModifier.clip(RemoteRoundedCornerShape(28.rdp))
                .background(theme.placeholderBackground)
                .padding(6.rdp)
            ) {
              reading()
            }
            foot()
          }
        } else {
          RemoteRow(
            RemoteModifier.fillMaxWidth().weight(1f),
            verticalAlignment = RemoteAlignment.CenterVertically,
          ) {
            RemoteColumn(RemoteModifier.weight(1f)) {
              label()
              foot()
            }
            RemoteBox(
              RemoteModifier.clip(RemoteRoundedCornerShape(28.rdp))
                .background(theme.placeholderBackground)
                .padding(10.rdp)
            ) {
              reading()
            }
          }
        }
        RemoteHaBarMeter(fraction, color, RemoteModifier.fillMaxWidth().height(12.rdp))
      }
      HaVisualFamily.Minimalist -> {
        reading()
        label()
        RemoteBox(RemoteModifier.weight(1f))
        RemoteRow(
          RemoteModifier.fillMaxWidth(),
          verticalAlignment = RemoteAlignment.CenterVertically,
        ) {
          RemoteBox(RemoteModifier.weight(1f)) { foot() }
          meter(RemoteModifier.size(48.rdp, 28.rdp))
        }
      }
      HaVisualFamily.Cupertino -> {
        RemoteRow(
          RemoteModifier.fillMaxWidth().weight(1f),
          verticalAlignment = RemoteAlignment.CenterVertically,
        ) {
          RemoteColumn(RemoteModifier.weight(1f).padding(end = 10.rdp)) {
            reading()
            label()
            foot()
          }
          RemoteBox(
            RemoteModifier.width((if (small) 36 else 48).rdp)
              .fillMaxHeight()
              .clip(RemoteRoundedCornerShape(24.rdp))
              .background(theme.placeholderBackground)
              .padding(6.rdp)
          ) {
            RemoteHaBarMeter(fraction, color, RemoteModifier.fillMaxSize(), vertical = true)
          }
        }
      }
      HaVisualFamily.Catppuccin -> {
        RemoteBox(
          RemoteModifier.clip(RemoteRoundedCornerShape(5.rdp))
            .background(theme.placeholderBackground)
            .padding(horizontal = 8.rdp, vertical = 3.rdp)
        ) {
          label()
        }
        RemoteRow(
          RemoteModifier.fillMaxWidth().weight(1f),
          verticalAlignment = RemoteAlignment.CenterVertically,
        ) {
          RemoteColumn(RemoteModifier.weight(1f)) {
            reading()
            foot()
          }
          meter(RemoteModifier.size((if (small) 32 else 56).rdp, (if (small) 32 else 48).rdp))
        }
        RemoteHaBarMeter(fraction, color, RemoteModifier.fillMaxWidth().height(7.rdp))
      }
      HaVisualFamily.Obsidian -> {
        label()
        rule()
        RemoteBox(RemoteModifier.weight(1f), contentAlignment = RemoteAlignment.CenterStart) {
          reading()
        }
        RemoteHaBarMeter(fraction, color, RemoteModifier.fillMaxWidth().height(8.rdp))
        foot()
      }
      HaVisualFamily.Noctis -> {
        label()
        RemoteRow(
          RemoteModifier.fillMaxWidth().weight(1f),
          verticalAlignment = RemoteAlignment.CenterVertically,
        ) {
          meter(RemoteModifier.size((if (small) 40 else 72).rdp, (if (small) 48 else 64).rdp))
          RemoteColumn(RemoteModifier.weight(1f).padding(start = 10.rdp)) {
            reading()
            foot()
          }
        }
      }
      HaVisualFamily.Lcars -> {
        RemoteHaBarMeter(fraction, color, RemoteModifier.fillMaxWidth().height(18.rdp))
        RemoteBox(
          RemoteModifier.fillMaxWidth().weight(1f),
          contentAlignment = RemoteAlignment.CenterEnd,
        ) {
          reading()
        }
        rule()
        label()
        foot()
      }
      HaVisualFamily.Arctic -> {
        label()
        reading()
        RemoteBox(RemoteModifier.weight(1f))
        RemoteHaBarMeter(fraction, color, RemoteModifier.fillMaxWidth().height(18.rdp))
        foot()
      }
      HaVisualFamily.Aeon -> {
        RemoteRow(
          RemoteModifier.fillMaxWidth().weight(1f),
          verticalAlignment = RemoteAlignment.CenterVertically,
        ) {
          RemoteColumn(RemoteModifier.weight(1f).padding(end = 8.rdp)) {
            label()
            reading()
            foot()
          }
          RemoteBox(
            RemoteModifier.width((if (small) 28 else 40).rdp)
              .fillMaxHeight()
              .background(theme.dashboardBackground)
              .padding(5.rdp)
          ) {
            RemoteHaBarMeter(fraction, color, RemoteModifier.fillMaxSize(), vertical = true)
          }
        }
        rule()
      }
      else -> error("Not a community family")
    }
  }
}
