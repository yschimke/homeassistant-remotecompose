package ee.schimke.terrazzo

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ee.schimke.ha.client.design.UsageSharing
import ee.schimke.ha.design.DesignEntry
import ee.schimke.ha.design.DesignTags
import ee.schimke.terrazzo.core.prefs.CardStylePref
import ee.schimke.terrazzo.core.prefs.DarkModePref
import ee.schimke.terrazzo.core.prefs.LauncherColoursPref
import ee.schimke.terrazzo.core.prefs.PreferencesStore
import ee.schimke.terrazzo.core.prefs.ThemePref
import ee.schimke.terrazzo.core.session.DemoHaSession
import ee.schimke.terrazzo.core.session.HaSession
import ee.schimke.terrazzo.dashboard.CardDesignSources
import ee.schimke.terrazzo.dashboard.CardDesignSync
import ee.schimke.terrazzo.dashboard.CardStyleSample
import ee.schimke.terrazzo.shared.ReportFeedbackButton
import ee.schimke.terrazzo.ui.toUberTheme
import ee.schimke.terrazzo.widget.WidgetRefreshScheduler
import kotlinx.coroutines.launch

/**
 * The app's settings, in groups: the connection, how things look, how the dashboard behaves, then
 * the experimental and debug switches, and help last.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
  session: HaSession,
  onToggleDemo: (Boolean) -> Unit,
  onSignOut: () -> Unit,
  onBack: () -> Unit,
  onOpenSyncDiagnostics: (() -> Unit)?,
  onManageDashboards: () -> Unit,
) {
  val isDemo = session is DemoHaSession
  val graph = LocalTerrazzoGraph.current
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  val widgetRefresh = remember(context) { WidgetRefreshScheduler(context.applicationContext) }
  val themePref by
    graph.preferencesStore.themeStyle.collectAsState(initial = ThemePref.TerrazzoHome)
  val darkPref by graph.preferencesStore.darkMode.collectAsState(initial = DarkModePref.Follow)
  val cardStylePref by
    graph.preferencesStore.cardStyle.collectAsState(initial = CardStylePref.Classic)
  val launcherColours by
    graph.preferencesStore.launcherColours.collectAsState(initial = LauncherColoursPref.System)
  val gridLayoutEnabled by
    graph.preferencesStore.experimentalGridLayout.collectAsState(initial = false)
  val collapsedModeEnabled by graph.preferencesStore.collapsedMode.collectAsState(initial = true)
  val logsViewEnabled by graph.preferencesStore.logsViewEnabled.collectAsState(initial = false)
  val flashOnDataChange by graph.preferencesStore.flashOnDataChange.collectAsState(initial = false)
  val dataGridOverlay by graph.preferencesStore.dataGridOverlay.collectAsState(initial = false)
  val hapticsEnabled by graph.preferencesStore.haptics.collectAsState(initial = true)
  val soundsEnabled by graph.preferencesStore.interfaceSounds.collectAsState(initial = false)
  val designServerUrl by graph.preferencesStore.designServerUrl.collectAsState(initial = "")
  val designVariant by
    graph.preferencesStore.designVariant.collectAsState(
      initial = PreferencesStore.DEFAULT_DESIGN_VARIANT
    )
  val designAuthor by graph.preferencesStore.designAuthor.collectAsState(initial = "")
  val designToken by graph.preferencesStore.designToken.collectAsState(initial = "")
  val usageSharing by
    graph.preferencesStore.usageSharing.collectAsState(initial = UsageSharing.Default)
  val permanentWriteEnabled by
    graph.preferencesStore.permanentWriteMode.collectAsState(initial = false)

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Settings") },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
      )
    },
    contentWindowInsets = WindowInsets.safeDrawing,
  ) { padding ->
    Column(
      modifier =
        Modifier.fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(padding)
          .consumeWindowInsets(padding)
          .padding(horizontal = 24.dp, vertical = 16.dp),
      verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
      SettingsGroup("Connection") {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text("Connected to", style = MaterialTheme.typography.labelMedium)
          Text(
            if (isDemo) "Demo mode — offline fake data" else session.baseUrl,
            style = MaterialTheme.typography.bodyMedium,
          )
        }
        if (!isDemo) {
          val externalUrl by
            graph.remoteUrlStore.externalUrl(session.baseUrl).collectAsState(initial = null)
          externalUrl?.let { url ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
              Text("Public URL (away from home)", style = MaterialTheme.typography.labelMedium)
              Text(url, style = MaterialTheme.typography.bodyMedium)
            }
          }
        }
        SettingSwitch(
          title = "Demo mode",
          summary = "Populate the app with an interesting set of fake widgets that animate.",
          checked = isDemo,
          onCheckedChange = onToggleDemo,
        )
        SettingRow(
          title = "Manage dashboards",
          summary =
            "Pick which custom and built-in Home Assistant dashboards " +
              "appear in the picker and the top-bar switcher.",
        ) {
          OutlinedButton(onClick = onManageDashboards) { Text("Choose") }
        }
        OutlinedButton(onClick = onSignOut) { Text(if (isDemo) "Exit demo" else "Sign out") }
      }

      SettingsGroup("Appearance") {
        ThemeSection(
          selected = themePref,
          darkMode = darkPref,
          onSelectTheme = { pref ->
            scope.launch {
              graph.preferencesStore.setThemeStyle(pref)
              // Each pinned widget has a baked .rc doc using the
              // old palette; broadcast a refresh so the provider
              // re-captures under the new one.
              widgetRefresh.refreshAllNow()
            }
          },
          onSelectDark = { pref ->
            scope.launch {
              graph.preferencesStore.setDarkMode(pref)
              widgetRefresh.refreshAllNow()
            }
          },
        )

        CardStyleSection(
          selected = cardStylePref,
          onSelect = { pref ->
            scope.launch {
              graph.preferencesStore.setCardStyle(pref)
              widgetRefresh.refreshAllNow()
            }
          },
        )

        LauncherColoursSection(
          selected = launcherColours,
          onSelect = { pref ->
            scope.launch {
              graph.preferencesStore.setLauncherColours(pref)
              widgetRefresh.refreshAllNow()
            }
          },
        )
      }

      SettingsGroup("Dashboard") {
        SettingSwitch(
          title = "Permanent write mode",
          summary =
            "When off, every relaunch starts in Read so taps on the dashboard " +
              "(and on home-screen widgets) never silently fire HA service " +
              "calls. Turn on if you trust this device and prefer Write by " +
              "default; the chip in the top bar still overrides per session.",
          checked = permanentWriteEnabled,
          onCheckedChange = { scope.launch { graph.preferencesStore.setPermanentWriteMode(it) } },
        )
        SettingSwitch(
          title = "Collapsed sections",
          summary =
            "On a long single-column dashboard, keep only the first section " +
              "expanded and collapse the rest to their headings. Tap a heading " +
              "to swap which section is open.",
          checked = collapsedModeEnabled,
          onCheckedChange = { scope.launch { graph.preferencesStore.setCollapsedMode(it) } },
        )
        SettingSwitch(
          title = "Haptics",
          summary =
            "A tick when a card switches something on or off, a tap per keypad " +
              "key, and a buzz when an action is dropped or fails.",
          checked = hapticsEnabled,
          onCheckedChange = { scope.launch { graph.preferencesStore.setHaptics(it) } },
        )
        SettingSwitch(
          title = "Interface sounds",
          summary =
            "Quiet sounds for the same moments, voiced to the card style: a " +
              "mechanical switch for Console, a beep for LCD and Trinitron, a soft " +
              "tick otherwise. Silent while the ringer is on vibrate or silent.",
          checked = soundsEnabled,
          onCheckedChange = { scope.launch { graph.preferencesStore.setInterfaceSounds(it) } },
        )
      }

      SettingsGroup("Experimental & debug") {
        SettingSwitch(
          title = "Grid layout",
          summary =
            "Use Compose 1.11's Grid API for the wide-screen side-by-side " +
              "section layout instead of the chunked Row path. Only takes " +
              "effect on tablets / unfolded foldables with ≥2 sections.",
          checked = gridLayoutEnabled,
          onCheckedChange = {
            scope.launch { graph.preferencesStore.setExperimentalGridLayout(it) }
          },
        )
        SettingSwitch(
          title = "Show logs view",
          summary =
            "Exposes a debug screen via the overflow menu listing recent " +
              "dashboard-entity updates, connect / disconnect events, and " +
              "locally-dispatched service calls. In-memory only.",
          checked = logsViewEnabled,
          onCheckedChange = { scope.launch { graph.preferencesStore.setLogsViewEnabled(it) } },
        )
        SettingSwitch(
          title = "Flash cards on data change",
          summary =
            "Briefly highlight a card whenever the entity state it renders " +
              "changes. Handy with demo mode to see which cards are live. " +
              "Visual only — nothing about the rendered card changes.",
          checked = flashOnDataChange,
          onCheckedChange = { scope.launch { graph.preferencesStore.setFlashOnDataChange(it) } },
        )
        SettingSwitch(
          title = "Data grid overlay",
          summary =
            "Float a grid over the dashboard listing every entity its cards " +
              "use — the key, current value, and how long ago it last changed. " +
              "Tap ✕ on the panel to dismiss.",
          checked = dataGridOverlay,
          onCheckedChange = { scope.launch { graph.preferencesStore.setDataGridOverlay(it) } },
        )
        // Saving a setting changes the design choice, which syncs the designs it needs (see
        // TerrazzoApplication).
        DesignServerSetting(
          url = designServerUrl,
          variant = designVariant,
          author = designAuthor,
          token = designToken,
          onUrlChange = { url -> scope.launch { graph.preferencesStore.setDesignServerUrl(url) } },
          onVariantChange = { scope.launch { graph.preferencesStore.setDesignVariant(it) } },
          onAuthorChange = { scope.launch { graph.preferencesStore.setDesignAuthor(it) } },
          onTokenChange = { scope.launch { graph.preferencesStore.setDesignToken(it) } },
        )
        if (designServerUrl.isNotBlank()) {
          UsageSharingSetting(
            // The switch shows the effective value; touching it stores an explicit choice.
            checked = usageSharing.effective(),
            onCheckedChange = {
              scope.launch { graph.preferencesStore.setUsageSharing(UsageSharing.chosen(it)) }
            },
          )
        }
      }

      SettingsGroup("Help", divider = false) {
        ReportFeedbackButton(version = BuildConfig.VERSION_NAME)
        // Sync diagnostics — buried at the bottom of Settings on
        // purpose. Shows DataItem write / MessageClient send counts
        // so power users can sanity-check wear-side chatter. Hidden
        // when the device has no usable Wearable API (no paired
        // watch / wearable component) so the link doesn't dangle.
        if (onOpenSyncDiagnostics != null) {
          TextButton(onClick = onOpenSyncDiagnostics) { Text("Sync diagnostics") }
        }
      }
    }
  }
}

/**
 * "Share anonymous card usage": off until the user turns it on. Says what is sent and links to the
 * privacy policy.
 */
@Composable
internal fun UsageSharingSetting(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
  val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    SettingSwitch(
      title = "Share anonymous card usage",
      summary =
        "Once a day, send the design server which card designs drew your cards (card type, " +
          "design and style, in rough counts). Never entities, names, states or addresses.",
      checked = checked,
      onCheckedChange = onCheckedChange,
    )
    TextButton(onClick = { uriHandler.openUri(PRIVACY_POLICY_URL) }) { Text("Privacy policy") }
  }
}

/** The published privacy policy (`docs/privacy.md`). */
internal const val PRIVACY_POLICY_URL: String =
  "https://yschimke.github.io/homeassistant-remotecompose/privacy/"

/** A titled group of settings, set off from the next by a divider. */
@Composable
private fun SettingsGroup(
  title: String,
  divider: Boolean = true,
  content: @Composable () -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Text(
      title,
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.primary,
    )
    content()
    if (divider) HorizontalDivider(Modifier.padding(top = 8.dp))
  }
}

/** A setting's title and summary, with its control at the end. */
@Composable
private fun SettingRow(
  title: String,
  summary: String,
  modifier: Modifier = Modifier,
  control: @Composable () -> Unit,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(title, style = MaterialTheme.typography.titleMedium)
      Text(
        summary,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Spacer(Modifier.width(12.dp))
    control()
  }
}

/** An on / off setting; the whole row toggles it. */
@Composable
private fun SettingSwitch(
  title: String,
  summary: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
) {
  SettingRow(
    title = title,
    summary = summary,
    modifier =
      Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
  ) {
    Switch(checked = checked, onCheckedChange = null)
  }
}

@Composable
private fun ThemeSection(
  selected: ThemePref,
  darkMode: DarkModePref,
  onSelectTheme: (ThemePref) -> Unit,
  onSelectDark: (DarkModePref) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("Theme", style = MaterialTheme.typography.titleMedium)
    Text(
      "Material 3 uses the system default palette (dynamic colour on Android 12+). " +
        "The Terrazzo themes ship a curated Home-Assistant-flavoured palette plus a Google-Fonts pairing.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(Modifier.selectableGroup()) {
      ThemePref.entries.forEach { pref ->
        OptionRow(
          title = themePrefDisplayName(pref),
          summary = themePrefTagline(pref),
          selected = pref == selected,
          onClick = { onSelectTheme(pref) },
        )
      }
    }

    Text("Dark mode", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      DarkModePref.entries.forEach { pref ->
        FilterChip(
          selected = pref == darkMode,
          onClick = { onSelectDark(pref) },
          label = {
            Text(
              when (pref) {
                DarkModePref.Follow -> "Follow system"
                DarkModePref.Light -> "Light"
                DarkModePref.Dark -> "Dark"
              }
            )
          },
        )
      }
    }
  }
}

/**
 * The card style: how dashboard cards are drawn. Classic keeps the cards' own look in the chosen
 * palette; each uber theme brings its own palette, shapes, type and instrument designs (an LCD's
 * segment bars, a Trinitron VU needle). Dashboard cards and launcher widgets: widgets take the
 * style on the launcher's colours unless [LauncherColoursSection] says otherwise. The app's own
 * screens keep the palette above.
 *
 * Each style is shown with a sample card drawn in it ([CardStyleSample]). Collapsed (the default),
 * only the current style is listed; expanding lists them all.
 */
@Composable
internal fun CardStyleSection(
  selected: CardStylePref,
  onSelect: (CardStylePref) -> Unit,
  initiallyExpanded: Boolean = false,
) {
  var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
  Column(
    modifier = Modifier.animateContentSize(),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("Card style", style = MaterialTheme.typography.titleMedium)
    Text(
      "How dashboard cards are drawn. Classic follows the theme above; the others are complete " +
        "looks with their own colours, shapes and gauges.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val shown = if (expanded) CardStylePref.entries else listOf(selected)
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      shown.forEach { pref ->
        key(pref) {
          CardStyleOption(pref = pref, selected = pref == selected, onClick = { onSelect(pref) })
        }
      }
    }
    TextButton(onClick = { expanded = !expanded }) {
      Icon(
        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
      )
      Spacer(Modifier.width(8.dp))
      Text(
        if (expanded) "Show only the current style"
        else "Show all ${CardStylePref.entries.size} styles"
      )
    }
  }
}

/**
 * One card style: its radio button beside a sample card drawn in it, the card itself naming and
 * describing the style. The whole row selects it.
 */
@Composable
private fun CardStyleOption(pref: CardStylePref, selected: Boolean, onClick: () -> Unit) {
  val uber = pref.toUberTheme()
  val tagline = cardStyleTagline(pref)
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
        .semantics { contentDescription = "${uber.displayName}. $tagline" },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(12.dp))
    // The sample is a picture of the style, not a control: a tap on it picks the style rather
    // than reaching the card, and screen readers hear the option, not the sample's contents.
    Box(Modifier.weight(1f).clearAndSetSemantics {}) {
      CardStyleSample(uber, name = uber.displayName, description = tagline)
      Box(
        Modifier.matchParentSize()
          .clickable(interactionSource = null, indication = null, onClick = onClick)
      )
    }
  }
}

/**
 * Whether launcher widgets keep the launcher's colours (the default) or draw the card style with
 * its palette, as the dashboard does.
 */
@Composable
private fun LauncherColoursSection(
  selected: LauncherColoursPref,
  onSelect: (LauncherColoursPref) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("Launcher widget colours", style = MaterialTheme.typography.titleMedium)
    Column(Modifier.selectableGroup()) {
      LauncherColoursPref.entries.forEach { pref ->
        OptionRow(
          title =
            when (pref) {
              LauncherColoursPref.System -> "System colours"
              LauncherColoursPref.App -> "Card style colours"
            },
          summary =
            when (pref) {
              LauncherColoursPref.System ->
                "Your launcher's colours, with the card style's gauges, type and shapes"
              LauncherColoursPref.App -> "The card style's own colours, as on your dashboard"
            },
          selected = pref == selected,
          onClick = { onSelect(pref) },
        )
      }
    }
  }
}

/** A radio option with a title and summary; the whole row selects it. */
@Composable
private fun OptionRow(title: String, summary: String, selected: Boolean, onClick: () -> Unit) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(12.dp))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(title, style = MaterialTheme.typography.bodyLarge)
      Text(
        summary,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

private fun cardStyleTagline(pref: CardStylePref): String =
  when (pref) {
    CardStylePref.Classic -> "The cards' own look, in your theme's palette"
    CardStylePref.HomeAssistant ->
      "Built-in HA · light and dark cards, half gauges and thermostat rings"
    CardStylePref.Expressive -> "Material 3 Expressive · big shapes, capsule meters"
    CardStylePref.Trinitron -> "Graphite hi-fi · VU needles, LED level meters"
    CardStylePref.Lcd -> "Segment LCD · bar graphs with ghost segments"
    CardStylePref.Braun -> "Dieter Rams · dials and tuning scales"
    CardStylePref.Nixie -> "Glowing tubes · black glass, orange filaments"
    CardStylePref.Ikea -> "IKEA showroom · birch shelves, tactile smart-light controls"
    CardStylePref.Console -> "Skeuomorphic studio desk · VU meters, faders, knobs"
    CardStylePref.Mushroom,
    CardStylePref.Bubble,
    CardStylePref.Minimalist,
    CardStylePref.Cupertino,
    CardStylePref.Catppuccin,
    CardStylePref.Obsidian,
    CardStylePref.Noctis,
    CardStylePref.Lcars,
    CardStylePref.Arctic,
    CardStylePref.Aeon -> pref.toUberTheme().description
  }

private fun themePrefDisplayName(pref: ThemePref): String =
  when (pref) {
    ThemePref.Material3 -> "Material 3"
    ThemePref.TerrazzoHome -> "Home"
    ThemePref.TerrazzoMushroom -> "Mushroom"
    ThemePref.TerrazzoMinimalist -> "Minimalist"
    ThemePref.TerrazzoKiosk -> "Kiosk"
  }

private fun themePrefTagline(pref: ThemePref): String =
  when (pref) {
    ThemePref.Material3 -> "Defaults · dynamic colour on Android 12+"
    ThemePref.TerrazzoHome -> "Home Assistant blue · Roboto Flex + Inter"
    ThemePref.TerrazzoMushroom -> "Warm salmon · Figtree"
    ThemePref.TerrazzoMinimalist -> "Neutral slate · IBM Plex Sans"
    ThemePref.TerrazzoKiosk -> "High-contrast teal · Atkinson Hyperlegible"
  }

/**
 * Experimental: base URL of a card design server. Empty (the default) keeps the feature off. Saved
 * when the field loses focus or the keyboard's Done is pressed, not on every keystroke, so a
 * half-typed URL never triggers a fetch.
 */
@Composable
private fun DesignServerSetting(
  url: String,
  variant: String,
  author: String,
  token: String,
  onUrlChange: (String) -> Unit,
  onVariantChange: (String) -> Unit,
  onAuthorChange: (String) -> Unit,
  onTokenChange: (String) -> Unit,
) {
  val context = LocalContext.current
  var draft by remember(url) { mutableStateOf(url) }
  val revision by CardDesignSync.revision.collectAsState()
  val tags by
    produceState<DesignTags?>(initialValue = null, url, revision) {
      value = CardDesignSync.tags(context, url)
    }
  Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Card design server", style = MaterialTheme.typography.titleMedium)
    Text(
      "Card designs come from this server (manifest.json + blobs/), chosen by your palette, " +
        "card style, variant and author; the app's own designs cover anything it lacks. The " +
        "manifest is checked nightly while charging. Leave empty to use only the app's designs.",
      style = MaterialTheme.typography.bodySmall,
    )
    androidx.compose.material3.OutlinedTextField(
      value = draft,
      onValueChange = { draft = it },
      singleLine = true,
      placeholder = { Text("Off") },
      keyboardOptions =
        androidx.compose.foundation.text.KeyboardOptions(
          imeAction = androidx.compose.ui.text.input.ImeAction.Done
        ),
      keyboardActions =
        androidx.compose.foundation.text.KeyboardActions(onDone = { onUrlChange(draft) }),
      modifier =
        Modifier.fillMaxWidth().onFocusChanged {
          if (!it.isFocused && draft != url) onUrlChange(draft)
        },
    )
    if (url.isNotBlank()) {
      DesignTokenField(url, token, onTokenChange)
      DesignSourcesSummary()
    }
    tags?.let { tags ->
      DesignTagPicker(
        title = "Variant",
        // The chosen variant stays listed even when this server has none of it yet.
        options = (tags.variant.keys + variant).distinct().map { it to it },
        selected = variant,
        onSelect = onVariantChange,
      )
      DesignTagPicker(
        title = "Author",
        options =
          listOf("" to "Built-in") +
            (tags.author.keys - DesignEntry.BUILTIN_AUTHOR + author.ifBlank { null })
              .filterNotNull()
              .distinct()
              .map { it to it.removePrefix("github:") },
        selected = author,
        onSelect = onAuthorChange,
      )
    }
  }
}

/**
 * The design server's device token: with it, the manifest also lists the designs this person
 * published privately. Made on the server's `/my` page while signed in with GitHub.
 */
@Composable
private fun DesignTokenField(url: String, token: String, onTokenChange: (String) -> Unit) {
  var draft by remember(token) { mutableStateOf(token) }
  val myPage = url.trimEnd('/').removeSuffix("/designs/v1") + "/my"
  androidx.compose.material3.OutlinedTextField(
    value = draft,
    onValueChange = { draft = it },
    singleLine = true,
    label = { Text("Device token") },
    supportingText = { Text("Shows your private designs. Create one at $myPage") },
    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
    keyboardOptions =
      androidx.compose.foundation.text.KeyboardOptions(
        imeAction = androidx.compose.ui.text.input.ImeAction.Done
      ),
    keyboardActions =
      androidx.compose.foundation.text.KeyboardActions(onDone = { onTokenChange(draft) }),
    modifier =
      Modifier.fillMaxWidth().onFocusChanged {
        if (!it.isFocused && draft != token) onTokenChange(draft)
      },
  )
}

/**
 * Where this session's card designs came from, and the card types and shapes the server had no
 * design for: the gaps it should fill. Read once when Settings opens.
 */
@Composable
private fun DesignSourcesSummary() {
  // Only the current choice's record: until a dashboard loads a changed choice there is none.
  val prefs = LocalTerrazzoGraph.current.preferencesStore
  val context = LocalContext.current
  val choice by
    remember(prefs) { CardDesignSync.choice(context, prefs) }.collectAsState(initial = null)
  val summary = remember(choice) { CardDesignSources.summary(choice) }
  if (summary.server == 0 && summary.local.isEmpty()) return
  Text(
    "This session: ${summary.server} card designs from the server, " +
      "${summary.local.size} from the app.",
    style = MaterialTheme.typography.bodySmall,
  )
  summary.local.take(MAX_LISTED_GAPS).forEach { key ->
    Text(
      "· ${key.cardType}" + key.shape.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty(),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
  }
  if (summary.local.size > MAX_LISTED_GAPS) {
    Text(
      "and ${summary.local.size - MAX_LISTED_GAPS} more",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

private const val MAX_LISTED_GAPS = 8

/** One design tag's values as chips, [selected] checked; each option is a value and its label. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DesignTagPicker(
  title: String,
  options: List<Pair<String, String>>,
  selected: String,
  onSelect: (String) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(title, style = MaterialTheme.typography.labelLarge)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      options.forEach { (value, label) ->
        FilterChip(
          selected = value == selected,
          onClick = { onSelect(value) },
          label = { Text(label) },
        )
      }
    }
  }
}
