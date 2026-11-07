package ee.schimke.terrazzo

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.work.WorkInfo
import androidx.work.WorkManager
import ee.schimke.ha.client.design.UsageSharing
import ee.schimke.ha.client.design.privateDesignServerUrl
import ee.schimke.ha.design.DesignEntry
import ee.schimke.ha.design.DesignTags
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.terrazzo.assistant.AssistantAccess
import ee.schimke.terrazzo.auth.designTokenLogin
import ee.schimke.terrazzo.auth.rememberDesignSignIn
import ee.schimke.terrazzo.core.prefs.CardStylePref
import ee.schimke.terrazzo.core.prefs.DarkModePref
import ee.schimke.terrazzo.core.prefs.LauncherColoursPref
import ee.schimke.terrazzo.core.prefs.PreferencesStore
import ee.schimke.terrazzo.core.prefs.ThemeChoice
import ee.schimke.terrazzo.core.prefs.ThemePref
import ee.schimke.terrazzo.core.prefs.WidgetCardFontsPref
import ee.schimke.terrazzo.core.session.DemoHaSession
import ee.schimke.terrazzo.core.session.HaSession
import ee.schimke.terrazzo.dashboard.CardDesignOverrideStore
import ee.schimke.terrazzo.dashboard.CardDesignSources
import ee.schimke.terrazzo.dashboard.CardDesignSync
import ee.schimke.terrazzo.dashboard.CardStyleInstallWorker
import ee.schimke.terrazzo.dashboard.CardStyleSample
import ee.schimke.terrazzo.dashboard.CardStyleStore
import ee.schimke.terrazzo.dashboard.CardStyles
import ee.schimke.terrazzo.notify.HaNotifications
import ee.schimke.terrazzo.notify.TestNotification
import ee.schimke.terrazzo.shared.ReportFeedbackButton
import ee.schimke.terrazzo.shared.isPlayUpdateTester
import ee.schimke.terrazzo.shared.setPlayUpdateTester
import ee.schimke.terrazzo.ui.toThemeStyle
import ee.schimke.terrazzo.ui.toUberTheme
import ee.schimke.terrazzo.widget.WidgetRefreshScheduler
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * The app's settings, in groups: the connection, the theme (one design list), how the dashboard
 * behaves, feedback and privacy, then the collapsed Designers and (once unlocked) Developer
 * sections, and help last.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
  session: HaSession,
  onBack: () -> Unit,
  onOpenSyncDiagnostics: (() -> Unit)?,
  /** Which of Home Assistant's dashboards the switcher lists; null in demo mode. */
  onManageDashboards: (() -> Unit)? = null,
  /** The debug logs view, offered while "Show logs view" is on. */
  onOpenLogs: (() -> Unit)? = null,
) {
  val isDemo = session is DemoHaSession
  val graph = LocalTerrazzoGraph.current
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  val widgetRefresh = remember(context) { WidgetRefreshScheduler(context.applicationContext) }
  val themeChoice by
    graph.preferencesStore.themeChoice.collectAsState(initial = ThemeChoice.Default)
  val darkPref by graph.preferencesStore.darkMode.collectAsState(initial = DarkModePref.Follow)
  val launcherColours by
    graph.preferencesStore.launcherColours.collectAsState(initial = LauncherColoursPref.System)
  val widgetCardFonts by
    graph.preferencesStore.widgetCardFonts.collectAsState(initial = WidgetCardFontsPref.Server)
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
      initial = PreferencesStore.DEFAULT_DESIGN_VARIANT,
    )
  val designAuthor by graph.preferencesStore.designAuthor.collectAsState(initial = "")
  val designToken by graph.preferencesStore.designToken.collectAsState(initial = "")
  val privateDesignServer by graph.preferencesStore.privateDesignServer.collectAsState(initial = "")
  val usageSharing by
    graph.preferencesStore.usageSharing.collectAsState(initial = UsageSharing.Default)
  val permanentWriteEnabled by
    graph.preferencesStore.permanentWriteMode.collectAsState(initial = false)
  val developerModePref by graph.preferencesStore.developerMode.collectAsState(initial = false)
  val cardStudioEntry by graph.preferencesStore.cardStudioEntry.collectAsState(initial = false)
  // One developer-mode state, shared with MainActivity's LocalDeveloperMode. Testers who opted in
  // to early updates stay opted in; they see the switch again once they unlock developer mode.
  val developerMode = developerModePref

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
      // Sign out (and Exit demo) is in the dashboard's overflow menu.
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
        HaNotificationsSetting(session)
      }

      SettingsGroup("Theme") {
        val installedStyles by CardStyleStore.of(context).installed.collectAsState()
        val styleOverrides by CardDesignOverrideStore.of(context).overrides.collectAsState()
        // WorkManager owns installs, including when Settings is closed or the process restarts.
        // No WorkManager in a preview (Robolectric doesn't initialise it): nothing is installing.
        val inPreview = LocalInspectionMode.current
        val installs by
          remember(context, inPreview) {
              if (inPreview) flowOf(emptyList())
              else
                WorkManager.getInstance(context).getWorkInfosByTagFlow(CardStyleInstallWorker.TAG)
            }
            .collectAsState(initial = emptyList())
        val stylesInUse = CardStyles.inUse(themeChoice.cardStyle.id, styleOverrides.cardStyles)
        DesignSection(
          selected = themeChoice,
          darkMode = darkPref,
          onSelect = { choice ->
            scope.launch {
              graph.preferencesStore.setThemeChoice(choice)
              // Each pinned widget has a baked .rc doc in the old look; broadcast a refresh so
              // the provider re-captures under the new one.
              widgetRefresh.refreshAllNow()
            }
          },
          onSelectDark = { pref ->
            scope.launch {
              graph.preferencesStore.setDarkMode(pref)
              widgetRefresh.refreshAllNow()
            }
          },
          state = { pref ->
            when {
              pref.id in CardStyles.BUILT_IN -> CardStyleState.BuiltIn
              CardStyles.isInstalled(pref.id, installedStyles) ->
                if (pref.id in stylesInUse) CardStyleState.InUse else CardStyleState.Installed
              installs.any {
                CardStyleInstallWorker.styleTag(pref.id) in it.tags && !it.state.isFinished
              } -> CardStyleState.Installing
              installs.any {
                CardStyleInstallWorker.styleTag(pref.id) in it.tags &&
                  (it.state == WorkInfo.State.FAILED || it.state == WorkInfo.State.CANCELLED)
              } -> CardStyleState.Failed
              else -> CardStyleState.NotInstalled
            }
          },
          onInstall = { pref -> CardStyleInstallWorker.enqueue(context, pref) },
          onUninstall = { pref ->
            scope.launch { CardStyles.uninstall(context.applicationContext, pref.id) }
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

      if (remember(context) { AssistantAccess.isSupported(context) }) {
        SettingsGroup("Assistants") {
          var assistantAccess by
            remember(context) { mutableStateOf(AssistantAccess.isEnabled(context)) }
          AssistantAccessSetting(
            checked = assistantAccess,
            onCheckedChange = { enabled ->
              scope.launch {
                if (AssistantAccess.setEnabled(context, enabled)) assistantAccess = enabled
              }
            },
          )
        }
      }

      SettingsGroup("Dashboard") {
        onManageDashboards?.let { manage ->
          SettingRow(
            title = "Manage dashboards",
            summary = "Choose which Home Assistant dashboards the dashboard switcher lists.",
            modifier = Modifier.clickable(onClick = manage),
          ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
          }
        }
        SettingSwitch(
          title = "Start with controls unlocked",
          summary =
            "When off, every relaunch starts with controls locked, so a stray " +
              "tap on the dashboard (or a risky one on a home-screen widget) " +
              "never fires a Home Assistant service call: a locked tap offers " +
              "Unlock instead. Turn on if you trust this device; the lock in " +
              "the top bar still overrides per session.",
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
      }

      SettingsGroup("Feedback") {
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
            "Quiet sounds for the same moments, voiced to the design: a mechanical " +
              "switch for Console, a beep for LCD and Trinitron, a soft tick otherwise. " +
              "Silent while the ringer is on vibrate or silent.",
          checked = soundsEnabled,
          onCheckedChange = { scope.launch { graph.preferencesStore.setInterfaceSounds(it) } },
        )
      }

      SettingsGroup("Privacy") {
        if (designServerUrl.isNotBlank()) {
          UsageSharingSetting(
            // The switch shows the effective value; touching it stores an explicit choice.
            checked = usageSharing.effective(),
            onCheckedChange = {
              scope.launch { graph.preferencesStore.setUsageSharing(UsageSharing.chosen(it)) }
            },
          )
        } else {
          PrivacyPolicyLink()
        }
      }

      CollapsibleSettingsGroup(
        title = "Designers",
        summary = "Sign in to the card design server and connect an AI agent, to make designs.",
      ) {
        DesignersSettings(
          url = designServerUrl,
          token = designToken,
          homeAssistant = privateDesignServer,
          onTokenChange = { scope.launch { graph.preferencesStore.setDesignToken(it) } },
        )
        SettingSwitch(
          title = "Card Studio",
          summary =
            "Offer Card Studio in a card's actions (long-press a card) even with no agent " +
              "connected, to design a card yourself.",
          checked = cardStudioEntry,
          onCheckedChange = { scope.launch { graph.preferencesStore.setCardStudioEntry(it) } },
        )
      }

      if (developerMode) {
        CollapsibleSettingsGroup(
          title = "Developer",
          summary = "Debug aids, the card design server and early updates.",
        ) {
          SettingSwitch(
            title = "Grid layout (experimental)",
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
              "Adds a debug screen, opened below, listing recent " +
                "dashboard-entity updates, connect / disconnect events, and " +
                "locally-dispatched service calls. In-memory only.",
            checked = logsViewEnabled,
            onCheckedChange = { scope.launch { graph.preferencesStore.setLogsViewEnabled(it) } },
          )
          if (logsViewEnabled && onOpenLogs != null) {
            OutlinedButton(onClick = onOpenLogs) { Text("Open logs") }
          }
          SettingSwitch(
            title = "Flash cards on data change",
            summary =
              "Briefly highlight a card whenever the entity state it renders " +
                "changes. Handy with demo mode to see which cards are live. " +
                "Visual only — nothing about the rendered card changes.",
            checked = flashOnDataChange,
            onCheckedChange = {
              scope.launch { graph.preferencesStore.setFlashOnDataChange(it) }
            },
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
            homeAssistant = privateDesignServer,
            onUrlChange = { url ->
              scope.launch { graph.preferencesStore.setDesignServerUrl(url) }
            },
            onVariantChange = { scope.launch { graph.preferencesStore.setDesignVariant(it) } },
            onAuthorChange = { scope.launch { graph.preferencesStore.setDesignAuthor(it) } },
          )
          WidgetCardFontsSection(
            selected = widgetCardFonts,
            onSelect = { pref ->
              scope.launch {
                graph.preferencesStore.setWidgetCardFonts(pref)
                widgetRefresh.refreshAllNow()
              }
            },
          )
          EarlyUpdatesSetting()
          // Sync diagnostics: DataItem write / MessageClient send counts, to sanity-check the
          // watch's chatter. Hidden when the device has no usable Wearable API (no paired watch /
          // wearable component) so the link doesn't dangle.
          if (onOpenSyncDiagnostics != null) {
            TextButton(onClick = onOpenSyncDiagnostics) { Text("Sync diagnostics") }
          }
          TextButton(
            onClick = { scope.launch { graph.preferencesStore.setDeveloperMode(false) } },
          ) {
            Text("Hide developer options")
          }
        }
      }

      SettingsGroup("Help", divider = false) {
        ReportFeedbackButton(version = BuildConfig.VERSION_NAME)
        AppVersionRow(
          version = BuildConfig.VERSION_NAME,
          developerMode = developerMode,
          onUnlock = { scope.launch { graph.preferencesStore.setDeveloperMode(true) } },
        )
      }
    }
  }
}

/** Taps on [AppVersionRow] that unlock developer mode. */
private const val DEVELOPER_TAPS = 7

/**
 * The installed version. Tapping it [DEVELOPER_TAPS] times turns on developer mode, which shows
 * Settings' Developer section (and the JSON of a card's settings); that section turns it off again.
 */
@Composable
private fun AppVersionRow(version: String, developerMode: Boolean, onUnlock: () -> Unit) {
  var taps by remember { mutableStateOf(0) }
  Text(
    "Version $version",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier =
      Modifier.clickable(enabled = !developerMode) {
          taps++
          if (taps >= DEVELOPER_TAPS) {
            taps = 0
            onUnlock()
          }
        }
        .padding(horizontal = 12.dp, vertical = 8.dp),
  )
}

/**
 * Early Play updates for internal testers: updates as soon as they appear, rather than once they
 * are a week old.
 */
@Composable
private fun EarlyUpdatesSetting() {
  val context = LocalContext.current
  var tester by remember { mutableStateOf(isPlayUpdateTester(context)) }
  SettingSwitch(
    title = "Early updates",
    summary = "Update as soon as a new version is on Play, for testers.",
    checked = tester,
    onCheckedChange = {
      tester = it
      setPlayUpdateTester(context, it)
    },
  )
}

/**
 * "Share anonymous card usage": off until the user turns it on. Says what is sent and links to the
 * privacy policy.
 */
@Composable
internal fun UsageSharingSetting(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    SettingSwitch(
      title = "Share anonymous card usage",
      summary =
        "Once a day, send the design server which card designs drew your cards (card type, " +
          "design and style, in rough counts). Never entities, names, states or addresses.",
      checked = checked,
      onCheckedChange = onCheckedChange,
    )
    PrivacyPolicyLink()
  }
}

/** Opens the published privacy policy. */
@Composable
private fun PrivacyPolicyLink() {
  val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
  TextButton(onClick = { uriHandler.openUri(PRIVACY_POLICY_URL) }) { Text("Privacy policy") }
}

/**
 * Settings → Assistants: lets an on-device agent call Terrazzo's AppFunctions
 * (`assistant/TerrazzoAppFunctions.kt`). Shown only where Android supports AppFunctions.
 */
@Composable
internal fun AssistantAccessSetting(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
  SettingSwitch(
    title = "Let on-device assistants use Home Assistant",
    summary =
      "An assistant Android allows (such as Gemini) can find your entities, read them and " +
        "turn lights, switches, scenes and scripts on or off, signed in as you. Locks and " +
        "alarm panels stay read-only.",
    checked = checked,
    onCheckedChange = onCheckedChange,
  )
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

/**
 * A titled group of settings that opens on demand: its heading and [summary] toggle the [content]
 * underneath, closed at first. For the sections few people need (Designers, Developer).
 */
@Composable
private fun CollapsibleSettingsGroup(
  title: String,
  summary: String,
  content: @Composable () -> Unit,
) {
  var expanded by rememberSaveable(title) { mutableStateOf(false) }
  Column(
    modifier = Modifier.animateContentSize(),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Row(
      modifier =
        Modifier.fillMaxWidth()
          .toggleable(value = expanded, role = Role.Button, onValueChange = { expanded = it }),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
          title,
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.primary,
        )
        Text(
          summary,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Icon(
        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
        contentDescription = if (expanded) "Collapse $title" else "Expand $title",
      )
    }
    if (expanded) content()
    HorizontalDivider(Modifier.padding(top = 8.dp))
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
/**
 * Whether Home Assistant's `notify.mobile_app_<device>` messages post as notifications: on is the
 * system's POST_NOTIFICATIONS grant, asked for here and nowhere else, so nothing posts before the
 * user allows it. Turning it off goes to the app's notification settings, where Android keeps it.
 * Once allowed, a test notification shows what HA's look like ([TestNotification]); the public demo
 * server is read-only and shared, so it can't send one.
 */
@Composable
private fun HaNotificationsSetting(session: HaSession) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var allowed by remember { mutableStateOf(HaNotifications.allowed(context)) }
  // Back from the system settings, or the permission dialog: read the grant again.
  LifecycleResumeEffect(Unit) {
    allowed = HaNotifications.allowed(context)
    onPauseOrDispose {}
  }
  val request =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      allowed = granted
    }
  SettingSwitch(
    title = "Home Assistant notifications",
    summary =
      "Messages sent to this device with notify.mobile_app. With the Terrazzo integration in " +
        "Home Assistant they arrive while the app is closed too; without it, while it's " +
        "connected. They show on a paired watch too.",
    checked = allowed,
  ) { on ->
    if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      request.launch(Manifest.permission.POST_NOTIFICATIONS)
    } else {
      context.startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
          .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
      )
    }
  }
  if (allowed) {
    TextButton(onClick = { scope.launch { TestNotification.send(context, session) } }) {
      Text("Send a test notification")
    }
  }
}

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

/**
 * The theme: one list of designs, each shown with a sample card drawn in it ([CardStyleSample]).
 * Material 3 is the cards' own look on the system's dynamic (wallpaper) colours; Classic is that
 * look in one of the curated Terrazzo palettes; every other design is a complete look with its own
 * colours, shapes and gauges, and some theme the whole app. The selected design's options open
 * underneath it ([DesignOptions]): Classic's palettes, and light / dark for a design that has both.
 *
 * Collapsed (the default), the built-in and installed designs and the current one are listed;
 * expanding lists them all, the rest offering Install.
 */
@Composable
internal fun DesignSection(
  selected: ThemeChoice,
  darkMode: DarkModePref,
  onSelect: (ThemeChoice) -> Unit,
  onSelectDark: (DarkModePref) -> Unit = {},
  initiallyExpanded: Boolean = false,
  state: (CardStylePref) -> CardStyleState = {
    if (it.id in CardStyles.BUILT_IN) CardStyleState.BuiltIn else CardStyleState.NotInstalled
  },
  onInstall: (CardStylePref) -> Unit = {},
  onUninstall: (CardStylePref) -> Unit = {},
) {
  var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
  // Material 3 and Classic draw the same cards; the design list tells them apart by palette.
  fun stateOf(design: String): CardStyleState =
    if (design == ThemeChoice.MATERIAL3) CardStyleState.BuiltIn
    else state(ThemeChoice(design).cardStyle)
  Column(
    modifier = Modifier.animateContentSize(),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("Design", style = MaterialTheme.typography.titleMedium)
    Text(
      "How the app and its cards look. Material 3 takes its colours from your wallpaper; " +
        "Classic draws the cards' own look in a palette of your choice; the others are complete " +
        "looks with their own colours, shapes and gauges. Material 3, Classic and Home " +
        "Assistant come with the app. Browse all designs to install more; a new design is " +
        "selected when its background download finishes.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val designs = listOf(ThemeChoice.MATERIAL3) + CardStylePref.entries.map { it.id }
    val shown = designs.filter {
      expanded ||
        it == selected.design ||
        stateOf(it) in
          setOf(
            CardStyleState.BuiltIn,
            CardStyleState.Installed,
            CardStyleState.InUse,
          )
    }
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      shown.forEach { design ->
        key(design) {
          val isSelected = design == selected.design
          val pref = ThemeChoice(design).cardStyle
          DesignOption(
            design = design,
            // Classic's sample shows its chosen palette; another design's, Classic's default.
            palette =
              if (isSelected) selected.palette.toThemeStyle()
              else ThemeChoice(design).normalized().palette.toThemeStyle(),
            selected = isSelected,
            state = stateOf(design),
            onClick = { if (!isSelected) onSelect(ThemeChoice(design).normalized()) },
            onInstall = { onInstall(pref) },
            onUninstall = { onUninstall(pref) },
          )
          if (isSelected) {
            DesignOptions(
              choice = selected,
              darkMode = darkMode,
              onSelect = onSelect,
              onSelectDark = onSelectDark,
            )
          }
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
      Text(if (expanded) "Show installed designs" else "Browse all designs")
    }
  }
}

/**
 * The selected design's options, under it in the list: Classic's palettes, and light / dark for a
 * design with both (Material 3, Classic and most others). A single-mode design (dark-only
 * Trinitron) has nothing to choose, so shows nothing.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun DesignOptions(
  choice: ThemeChoice,
  darkMode: DarkModePref,
  onSelect: (ThemeChoice) -> Unit,
  onSelectDark: (DarkModePref) -> Unit,
) {
  val variants =
    if (choice.cardStyle == CardStylePref.Classic && choice.design != ThemeChoice.MATERIAL3)
      ThemeChoice.CLASSIC_VARIANTS
    else emptyList()
  val bothModes = !choice.cardStyle.toUberTheme().singleMode
  if (variants.isEmpty() && !bothModes) return
  Column(
    modifier = Modifier.fillMaxWidth().padding(start = 48.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    if (variants.isNotEmpty()) {
      Text("Palette", style = MaterialTheme.typography.labelLarge)
      Column(Modifier.selectableGroup()) {
        variants.forEach { palette ->
          OptionRow(
            title = themePrefDisplayName(palette),
            summary = themePrefTagline(palette),
            selected = palette == choice.palette,
            onClick = { onSelect(ThemeChoice.classic(palette)) },
          )
        }
      }
    }
    if (bothModes) {
      Text("Dark mode", style = MaterialTheme.typography.labelLarge)
      androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
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
                },
              )
            },
          )
        }
      }
    }
  }
}

/** Whether a card style can be picked, and what its row offers: see [CardStyles]. */
internal enum class CardStyleState {
  /** Comes with the app: always installed, never removed. */
  BuiltIn,
  /** Installed and unused: it can be picked or removed. */
  Installed,
  /** Installed and drawn with (the dashboard's or a card's style): it can't be removed. */
  InUse,
  /** Listed with its sample; installing downloads its designs. */
  NotInstalled,
  Installing,
  Failed,
}

/**
 * One design: its radio button beside a sample card drawn in it (in [palette], for the designs
 * drawn in the app's palette), the card itself naming and describing the design. The whole row
 * selects it once the design is installed; until then the row offers Install, and an installed
 * design nothing uses offers Remove.
 */
@Composable
private fun DesignOption(
  design: String,
  palette: ThemeStyle,
  selected: Boolean,
  state: CardStyleState,
  onClick: () -> Unit,
  onInstall: () -> Unit,
  onUninstall: () -> Unit,
) {
  val uber = ThemeChoice(design).cardStyle.toUberTheme()
  val name = if (design == ThemeChoice.MATERIAL3) "Material 3" else uber.displayName
  // A design that themes the whole app, or has one mode only, says so before it is picked.
  val tagline =
    designTagline(design) +
      (if (uber.appTheme) " · Themes the whole app" else "") +
      when {
        !uber.singleMode -> ""
        uber.palette(darkTheme = true).isDark -> " · Dark only"
        else -> " · Light only"
      }
  val pickable =
    selected ||
      state == CardStyleState.BuiltIn ||
      state == CardStyleState.Installed ||
      state == CardStyleState.InUse
  Column {
    DesignOptionRow(uber, name, palette, tagline, selected, pickable, onClick)
    CardStyleInstallRow(name, state, selected, onInstall, onUninstall)
  }
}

@Composable
private fun DesignOptionRow(
  uber: HaUberTheme,
  name: String,
  palette: ThemeStyle,
  tagline: String,
  selected: Boolean,
  pickable: Boolean,
  onClick: () -> Unit,
) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .selectable(
          selected = selected,
          enabled = pickable,
          role = Role.RadioButton,
          onClick = onClick,
        )
        .semantics { contentDescription = "$name. $tagline" },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    RadioButton(
      selected = selected,
      onClick = null,
      enabled = pickable,
      modifier = Modifier.padding(12.dp),
    )
    // The sample is a picture of the design, not a control: a tap on it picks the design rather
    // than reaching the card, and screen readers hear the option, not the sample's contents.
    Box(Modifier.weight(1f).clearAndSetSemantics {}) {
      CardStyleSample(uber, name = name, description = tagline, palette = palette)
      Box(
        Modifier.matchParentSize()
          .clickable(
            enabled = pickable,
            interactionSource = null,
            indication = null,
            onClick = onClick,
          ),
      )
    }
  }
}

/**
 * Install (with its progress or failure) for a style that isn't installed; Remove for one unused.
 */
@Composable
private fun CardStyleInstallRow(
  name: String,
  state: CardStyleState,
  selected: Boolean,
  onInstall: () -> Unit,
  onUninstall: () -> Unit,
) {
  if (selected) return
  Row(
    modifier = Modifier.fillMaxWidth().padding(start = 52.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    when (state) {
      CardStyleState.BuiltIn,
      CardStyleState.InUse -> Unit
      CardStyleState.Installed ->
        TextButton(
          onClick = onUninstall,
          modifier = Modifier.semantics { contentDescription = "Remove $name" },
        ) {
          Text("Remove")
        }
      CardStyleState.NotInstalled ->
        TextButton(
          onClick = onInstall,
          modifier = Modifier.semantics { contentDescription = "Install $name" },
        ) {
          Text("Install")
        }
      CardStyleState.Installing -> {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text("Installing designs…", style = MaterialTheme.typography.bodySmall)
      }
      CardStyleState.Failed -> {
        Text(
          "Couldn't download this style's designs",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.weight(1f),
        )
        TextButton(
          onClick = onInstall,
          modifier = Modifier.semantics { contentDescription = "Retry installing $name" },
        ) {
          Text("Retry")
        }
      }
    }
  }
}

/**
 * Where widgets' card fonts (Rugged Digital's Digital-7 and G-SPOT) are drawn as bitmap fonts,
 * since no widget host can load them: by the design server, on this phone, or not at all.
 */
@Composable
private fun WidgetCardFontsSection(
  selected: WidgetCardFontsPref,
  onSelect: (WidgetCardFontsPref) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("Widget card fonts", style = MaterialTheme.typography.titleMedium)
    Column(Modifier.selectableGroup()) {
      WidgetCardFontsPref.entries.forEach { pref ->
        OptionRow(
          title =
            when (pref) {
              WidgetCardFontsPref.Server -> "Drawn by the design server"
              WidgetCardFontsPref.OnDevice -> "Drawn on this phone"
              WidgetCardFontsPref.Off -> "Off"
            },
          summary =
            when (pref) {
              WidgetCardFontsPref.Server ->
                "Sends only the card's layout, never its names or states. Cards without a layout keep the theme's font"
              WidgetCardFontsPref.OnDevice ->
                "Every widget, offline, from the fonts this phone has downloaded. Nothing is sent"
              WidgetCardFontsPref.Off -> "Widgets keep the theme's own font"
            },
          selected = pref == selected,
          onClick = { onSelect(pref) },
        )
      }
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

/** A radio option with a title and summary; the whole row selects it, unless not [enabled]. */
@Composable
private fun OptionRow(
  title: String,
  summary: String,
  selected: Boolean,
  onClick: () -> Unit,
  enabled: Boolean = true,
) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .selectable(
          selected = selected,
          enabled = enabled,
          role = Role.RadioButton,
          onClick = onClick,
        )
        .alpha(if (enabled) 1f else 0.38f),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    RadioButton(
      selected = selected,
      onClick = null,
      enabled = enabled,
      modifier = Modifier.padding(12.dp),
    )
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

private fun designTagline(design: String): String =
  if (design == ThemeChoice.MATERIAL3) "Dynamic colour from your wallpaper · the cards' own look"
  else cardStyleTagline(ThemeChoice(design).cardStyle)

private fun cardStyleTagline(pref: CardStylePref): String =
  when (pref) {
    CardStylePref.Classic -> "The cards' own look, in a Terrazzo palette"
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
    CardStylePref.Aeon,
    CardStylePref.EvDash,
    CardStylePref.Aurora,
    CardStylePref.Engineered,
    CardStylePref.Cockpit,
    CardStylePref.RuggedDigital -> pref.toUberTheme().description
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
    ThemePref.Material3 -> "Dynamic colour from your wallpaper"
    ThemePref.TerrazzoHome -> "Home Assistant blue · Roboto Flex + Inter"
    ThemePref.TerrazzoMushroom -> "Warm salmon · Figtree"
    ThemePref.TerrazzoMinimalist -> "Neutral slate · IBM Plex Sans"
    ThemePref.TerrazzoKiosk -> "High-contrast teal · Atkinson Hyperlegible"
  }

/**
 * Developer: base URL of a card design server ([PreferencesStore.DEFAULT_DESIGN_SERVER_URL] until
 * changed; empty turns designs off), with the variant and author to prefer. Saved when the field
 * loses focus or the keyboard's Done is pressed, not on every keystroke, so a half-typed URL never
 * triggers a fetch. Signing in to it is in the Designers section ([DesignersSettings]).
 */
@Composable
private fun DesignServerSetting(
  url: String,
  variant: String,
  author: String,
  /**
   * The Home Assistant (base URL) whose own design server is used in place of [url] while designs
   * are on; blank for none.
   */
  homeAssistant: String,
  onUrlChange: (String) -> Unit,
  onVariantChange: (String) -> Unit,
  onAuthorChange: (String) -> Unit,
) {
  val context = LocalContext.current
  var draft by remember(url) { mutableStateOf(url) }
  val revision by CardDesignSync.revision.collectAsState()
  val home = homeAssistant.takeIf { it.isNotBlank() && url.isNotBlank() }
  // What the dashboard draws from: this Home Assistant's own server when it has one.
  val served = home?.let { "${privateDesignServerUrl(it)}/designs/v1" } ?: url
  val tags by
    produceState<DesignTags?>(initialValue = null, served, revision) {
      value = CardDesignSync.tags(context, served)
    }
  Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Card design server", style = MaterialTheme.typography.titleMedium)
    Text(
      "Card designs come from this server (manifest.json + blobs/), chosen by your design, " +
        "its palette, variant and author; one it lacks is fetched by its key as a card needs it, " +
        "and the card draws from the app's converter meanwhile. The manifest is checked nightly " +
        "while charging. Leave empty to draw every card from the app's converters.",
      style = MaterialTheme.typography.bodySmall,
    )
    androidx.compose.material3.OutlinedTextField(
      value = draft,
      onValueChange = { draft = it },
      singleLine = true,
      placeholder = { Text("Off") },
      keyboardOptions =
        androidx.compose.foundation.text.KeyboardOptions(
          imeAction = androidx.compose.ui.text.input.ImeAction.Done,
        ),
      keyboardActions =
        androidx.compose.foundation.text.KeyboardActions(onDone = { onUrlChange(draft) }),
      modifier =
        Modifier.fillMaxWidth().onFocusChanged {
          if (!it.isFocused && draft != url) onUrlChange(draft)
        },
    )
    if (home != null) {
      Text(
        "Your Home Assistant at ${Uri.parse(home).host ?: home} runs its own design server " +
          "(the Card Designs add-on): your designs are kept there, signed in as you are to Home " +
          "Assistant, and its library follows ha.coo.ee. Clear the address to turn designs off.",
        style = MaterialTheme.typography.bodySmall,
      )
    }
    if (url.isNotBlank()) DesignSourcesSummary()
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
 * Designers: signing in to the card design server ([DesignTokenField]) and connecting an AI agent
 * ([ee.schimke.terrazzo.shared.AgentConnection]), whose connection is what offers "Ask your agent"
 * and "Plan a card" on the dashboard.
 */
@Composable
private fun DesignersSettings(
  url: String,
  token: String,
  /** The Home Assistant whose own design server is in use, signed in as Home Assistant is. */
  homeAssistant: String,
  onTokenChange: (String) -> Unit,
) {
  val home = homeAssistant.takeIf { it.isNotBlank() && url.isNotBlank() }
  when {
    url.isBlank() ->
      Text(
        "The card design server is off: turn it on in Developer to sign in.",
        style = MaterialTheme.typography.bodySmall,
      )
    home != null ->
      Text(
        "Your designs are kept on your Home Assistant's own design server, signed in as you " +
          "are to Home Assistant.",
        style = MaterialTheme.typography.bodySmall,
      )
    else -> DesignTokenField(url, token, onTokenChange)
  }
  // Connect an AI agent once; Card Studio's requests then go straight to it.
  val context = LocalContext.current
  ee.schimke.terrazzo.shared.AgentConnection(
    url.ifBlank { "https://ha.coo.ee" },
    onCopy = { text ->
      runCatching {
        context.startActivity(
          Intent.createChooser(
              Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
              "Send to your agent",
            )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
      }
    },
  )
}

/**
 * Signing in to the design server: with its device token the manifest also lists the designs this
 * person published privately, and the card studio can publish. "Sign in with GitHub" gets one in a
 * Custom Tab ([rememberDesignSignIn]); a token made on the server's `/my` page can still be pasted.
 */
@Composable
private fun DesignTokenField(url: String, token: String, onTokenChange: (String) -> Unit) {
  var problem by remember { mutableStateOf<String?>(null) }
  var pasting by rememberSaveable { mutableStateOf(false) }
  val signIn = rememberDesignSignIn(onError = { problem = it.message ?: "Sign-in failed" })
  if (token.isNotBlank()) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        designTokenLogin(token)?.let { "Signed in as $it" } ?: "Signed in with a device token",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.weight(1f),
      )
      TextButton(onClick = { onTokenChange("") }) { Text("Sign out") }
    }
    return
  }
  Text(
    "Sign in to see your private designs and publish from the card studio.",
    style = MaterialTheme.typography.bodySmall,
  )
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Button(
      onClick = {
        problem = null
        signIn(url)
      },
    ) {
      Text("Sign in with GitHub")
    }
    if (!pasting) TextButton(onClick = { pasting = true }) { Text("Paste a token") }
  }
  problem?.let {
    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
  }
  if (pasting) {
    var draft by remember { mutableStateOf("") }
    val myPage = url.trimEnd('/').removeSuffix("/designs/v1") + "/my"
    androidx.compose.material3.OutlinedTextField(
      value = draft,
      onValueChange = { draft = it },
      singleLine = true,
      label = { Text("Device token") },
      supportingText = { Text("From $myPage") },
      visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
      keyboardOptions =
        androidx.compose.foundation.text.KeyboardOptions(
          imeAction = androidx.compose.ui.text.input.ImeAction.Done,
        ),
      keyboardActions =
        androidx.compose.foundation.text.KeyboardActions(
          onDone = { if (draft.isNotBlank()) onTokenChange(draft) },
        ),
      modifier = Modifier.fillMaxWidth(),
    )
  }
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
      "${summary.local.size} drawn by the app while their design is fetched.",
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
