package ee.schimke.terrazzo.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import ee.schimke.ha.client.design.CardDesignOverride
import ee.schimke.ha.client.design.CardDesignOverrides
import ee.schimke.ha.client.design.CardDesignScope
import ee.schimke.ha.client.design.DesignOption
import ee.schimke.ha.design.DesignEntry
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardDesignTemplates
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.cards.shutter.withEnhancedShutter
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.terrazzo.core.session.HaSession
import ee.schimke.terrazzo.ui.LocalCardStyle
import ee.schimke.terrazzo.ui.LocalIsDarkTheme
import ee.schimke.terrazzo.ui.LocalThemeStyle
import ee.schimke.terrazzo.widget.WidgetRefreshScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Draws one card, or every card of its type, in a card style other than the dashboard's. The card
 * itself sits at the top and redraws as a style is picked, so the choice is judged on the real card
 * rather than a style name. The choice is kept on this device ([CardDesignOverrideStore]); the
 * dashboard and launcher widgets pick it up at once, and the design sync fetches the style's
 * designs. Wear widgets keep the watch's own card style for now.
 *
 * With a design server, the sheet also lists every design that can draw the card (the library's,
 * device designs for the card's device and forks), so the card can be switched to one by its id
 * ([CardDesignOverride.designId]); "Automatic" clears it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardStyleSheet(
  session: HaSession,
  card: CardConfig,
  snapshot: HaSnapshot,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  val store = remember(context) { CardDesignOverrideStore.of(context) }
  val overrides by store.overrides.collectAsState()
  val scope = rememberCoroutineScope()
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val dashboardStyle = LocalCardStyle.current
  val cardStyle = overrides.forCard(card)?.cardStyle?.let(HaUberTheme::byId) ?: dashboardStyle
  val designs =
    rememberCardDesignOptions(
      card,
      snapshot,
      cardStyle,
      LocalThemeStyle.current,
      LocalIsDarkTheme.current,
    )
  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    CardStylePicker(
      card = card,
      overrides = overrides,
      dashboardStyle = dashboardStyle,
      onPick = { at, cardStyle ->
        scope.launch {
          val keep = overrides.at(at, card) ?: CardDesignOverride()
          store.set(at, card, keep.copy(cardStyle = cardStyle))
          // A launcher widget of this card draws in its style too.
          WidgetRefreshScheduler(context.applicationContext).refreshAllNow()
        }
      },
      onDone = onDismiss,
      designs = designs,
      onPickDesign = { designId ->
        scope.launch {
          // A design is chosen for this card alone; the design sync fetches it.
          val keep = overrides.at(CardDesignScope.Card, card) ?: CardDesignOverride()
          store.set(CardDesignScope.Card, card, keep.copy(designId = designId))
          WidgetRefreshScheduler(context.applicationContext).refreshAllNow()
        }
      },
    ) {
      DashboardCardPreview(session, card, snapshot)
    }
  }
}

/**
 * Every design in the cached manifest that can draw [card] in [cardStyle]: the library's, the
 * device designs that match the card's device ([cardDevice]) and the forks the person can see.
 * Empty with no design server, before its first sync, or for a card with no design key.
 */
@Composable
private fun rememberCardDesignOptions(
  card: CardConfig,
  snapshot: HaSnapshot,
  cardStyle: HaUberTheme,
  style: ThemeStyle,
  dark: Boolean,
): List<DesignOption> {
  val context = LocalContext.current
  val serverUrl = CardDesignPreload.designs.collectAsState().value?.loadedFor?.serverUrl
  val options by
    produceState(emptyList<DesignOption>(), card, snapshot, cardStyle, style, dark, serverUrl) {
      if (serverUrl.isNullOrBlank()) return@produceState
      value =
        withContext(Dispatchers.IO) {
          runCatching {
              val registry = defaultRegistry().withEnhancedShutter()
              val design = CardDesignTemplates.designCard(card, snapshot, registry)
              val key =
                CardDesignTemplates.keyFor(
                  design,
                  snapshot,
                  style,
                  dark,
                  registry,
                  cardStyle = cardStyle,
                ) ?: return@runCatching emptyList()
              CardDesignSync.cachedRepository(context, serverUrl)
                .designsFor(key, cardDevice(design, snapshot))
            }
            .getOrDefault(emptyList())
        }
    }
  return options
}

/**
 * The picker: the card [preview], which of "this card" or "every card of its type" a pick applies
 * to, then the card's [designs] (when the server has any; for this card alone) led by "Automatic",
 * and one radio list of styles led by "follow the dashboard". Apart from the sheet so a preview can
 * draw it.
 */
@Composable
internal fun CardStylePicker(
  card: CardConfig,
  overrides: CardDesignOverrides,
  dashboardStyle: HaUberTheme,
  onPick: (CardDesignScope, String?) -> Unit,
  onDone: () -> Unit,
  modifier: Modifier = Modifier,
  designs: List<DesignOption> = emptyList(),
  onPickDesign: (String?) -> Unit = {},
  preview: @Composable () -> Unit,
) {
  // Open on the scope that already decides this card's style, so the selected row is the one in
  // use.
  var at by
    remember(card) {
      mutableStateOf(
        if (
          overrides.at(CardDesignScope.Card, card) == null &&
            overrides.at(CardDesignScope.CardType, card) != null
        )
          CardDesignScope.CardType
        else CardDesignScope.Card
      )
    }
  val typeLabel = card.type.removePrefix("custom:").replace('-', ' ')
  val current = overrides.at(at, card)?.cardStyle
  // With no style of its own, a card follows its type's override when there is one.
  val inherited =
    if (at == CardDesignScope.Card) overrides.at(CardDesignScope.CardType, card)?.cardStyle
    else null
  val followLabel =
    if (inherited != null) "Same as other $typeLabel cards" else "Same as your dashboard"
  val followStyle = HaUberTheme.byId(inherited ?: dashboardStyle.id).displayName

  Column(modifier.fillMaxWidth().padding(bottom = 16.dp)) {
    Column(
      Modifier.padding(horizontal = 24.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text("Card style", style = MaterialTheme.typography.titleLarge)
      OutlinedCard(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
          preview()
        }
      }
      SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        listOf(
            CardDesignScope.Card to "This card",
            CardDesignScope.CardType to "All $typeLabel cards",
          )
          .forEachIndexed { index, (value, label) ->
            SegmentedButton(
              selected = at == value,
              onClick = { at = value },
              shape = SegmentedButtonDefaults.itemShape(index, 2),
              label = { Text(label, maxLines = 1) },
            )
          }
      }
    }
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
      // A design is chosen per card: the section shows while "This card" is picked.
      if (designs.isNotEmpty() && at == CardDesignScope.Card) {
        val chosen = overrides.at(CardDesignScope.Card, card)?.designId
        SectionHeader("Design")
        Column(Modifier.selectableGroup()) {
          StyleOption(
            "Automatic",
            "The design for this device, else the usual one",
            selected = chosen == null || designs.none { it.designId == chosen },
          ) {
            onPickDesign(null)
          }
          designs.forEach { design ->
            StyleOption(
              design.title ?: design.designId,
              designSupportingText(design),
              selected = chosen == design.designId,
            ) {
              onPickDesign(design.designId)
            }
          }
        }
        SectionHeader("Style")
      }
      Column(Modifier.selectableGroup()) {
        StyleOptions(at, current, followLabel, followStyle, onPick)
      }
    }
    Text(
      "Kept on this phone. Home screen widgets follow it; your watch keeps its own style.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
    TextButton(onClick = onDone, modifier = Modifier.align(Alignment.End).padding(end = 12.dp)) {
      Text("Done")
    }
  }
}

@Composable
private fun StyleOptions(
  at: CardDesignScope,
  current: String?,
  followLabel: String,
  followStyle: String,
  onPick: (CardDesignScope, String?) -> Unit,
) {
  StyleOption(followLabel, followStyle, selected = current == null) { onPick(at, null) }
  HaUberTheme.all.forEach { theme ->
    StyleOption(theme.displayName, null, selected = current == theme.id) { onPick(at, theme.id) }
  }
}

@Composable
private fun SectionHeader(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
  )
}

/** Who made [design] and, for a device design, which devices: "by octocat · Bambu Lab X1*". */
internal fun designSupportingText(design: DesignOption): String {
  val author =
    when {
      design.author == DesignEntry.BUILTIN_AUTHOR -> "Terrazzo"
      design.author.startsWith("github:") -> "by ${design.author.removePrefix("github:")}"
      else -> "by ${design.author}"
    }
  val device =
    design.device?.let { match ->
      listOfNotNull(match.manufacturer ?: match.integration, match.model, match.translationKey)
        .joinToString(" ")
        .takeIf { it.isNotBlank() }
        ?.let { "for $it" }
    }
  return listOfNotNull(author, device).joinToString(" · ")
}

@Composable
private fun StyleOption(
  label: String,
  supporting: String?,
  selected: Boolean,
  onClick: () -> Unit,
) {
  ListItem(
    headlineContent = { Text(label) },
    supportingContent = supporting?.let { { Text(it) } },
    leadingContent = { RadioButton(selected = selected, onClick = null) },
    modifier =
      Modifier.fillMaxWidth()
        .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
  )
}
