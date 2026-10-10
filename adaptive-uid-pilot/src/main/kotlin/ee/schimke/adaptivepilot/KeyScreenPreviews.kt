package ee.schimke.adaptivepilot

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

@Preview(name = "connection Phone Light", widthDp = 412, heightDp = 720)
@Preview(name = "connection Tablet Light", widthDp = 840, heightDp = 720)
@Composable
fun ConnectionLightPreview() = KeyScreen("connection", dark = false)

@Preview(name = "connection Phone Detail Light", widthDp = 412, heightDp = 720)
@Composable
fun ConnectionDetailLightPreview() = KeyScreen("connection", dark = false, initialDetail = true)

@Preview(name = "connection Tablet Detail Light", widthDp = 840, heightDp = 720)
@Composable
fun ConnectionSecondaryLightPreview() =
  KeyScreen("connection", dark = false, initialDetail = true, initialSelectedIndex = 1)

@Preview(name = "connection Phone Dark", widthDp = 412, heightDp = 720)
@Preview(name = "connection Tablet Dark", widthDp = 840, heightDp = 720)
@Composable
fun ConnectionDarkPreview() = KeyScreen("connection", dark = true)

@Preview(name = "connection Phone Detail Dark", widthDp = 412, heightDp = 720)
@Composable
fun ConnectionDetailDarkPreview() = KeyScreen("connection", dark = true, initialDetail = true)

@Preview(name = "connection Tablet Detail Dark", widthDp = 840, heightDp = 720)
@Composable
fun ConnectionSecondaryDarkPreview() =
  KeyScreen("connection", dark = true, initialDetail = true, initialSelectedIndex = 1)

@Preview(name = "dashboard-view Phone Light", widthDp = 412, heightDp = 720)
@Preview(name = "dashboard-view Tablet Light", widthDp = 840, heightDp = 720)
@Composable
fun DashboardViewLightPreview() = KeyScreen("dashboard-view", dark = false)

@Preview(name = "dashboard-view Phone Detail Light", widthDp = 412, heightDp = 720)
@Composable
fun DashboardViewDetailLightPreview() =
  KeyScreen("dashboard-view", dark = false, initialDetail = true)

@Preview(name = "dashboard-view Tablet Detail Light", widthDp = 840, heightDp = 720)
@Composable
fun DashboardViewSecondaryLightPreview() =
  KeyScreen("dashboard-view", dark = false, initialDetail = true, initialSelectedIndex = 1)

@Preview(name = "dashboard-view Phone Dark", widthDp = 412, heightDp = 720)
@Preview(name = "dashboard-view Tablet Dark", widthDp = 840, heightDp = 720)
@Composable
fun DashboardViewDarkPreview() = KeyScreen("dashboard-view", dark = true)

@Preview(name = "dashboard-view Phone Detail Dark", widthDp = 412, heightDp = 720)
@Composable
fun DashboardViewDetailDarkPreview() =
  KeyScreen("dashboard-view", dark = true, initialDetail = true)

@Preview(name = "dashboard-view Tablet Detail Dark", widthDp = 840, heightDp = 720)
@Composable
fun DashboardViewSecondaryDarkPreview() =
  KeyScreen("dashboard-view", dark = true, initialDetail = true, initialSelectedIndex = 1)

@Preview(name = "choose-dashboards Phone Light", widthDp = 412, heightDp = 720)
@Preview(name = "choose-dashboards Tablet Light", widthDp = 840, heightDp = 720)
@Composable
fun ChooseDashboardsLightPreview() = KeyScreen("choose-dashboards", dark = false)

@Preview(name = "choose-dashboards Phone Detail Light", widthDp = 412, heightDp = 720)
@Composable
fun ChooseDashboardsDetailLightPreview() =
  KeyScreen("choose-dashboards", dark = false, initialDetail = true)

@Preview(name = "choose-dashboards Tablet Detail Light", widthDp = 840, heightDp = 720)
@Composable
fun ChooseDashboardsSecondaryLightPreview() =
  KeyScreen("choose-dashboards", dark = false, initialDetail = true, initialSelectedIndex = 1)

@Preview(name = "choose-dashboards Phone Dark", widthDp = 412, heightDp = 720)
@Preview(name = "choose-dashboards Tablet Dark", widthDp = 840, heightDp = 720)
@Composable
fun ChooseDashboardsDarkPreview() = KeyScreen("choose-dashboards", dark = true)

@Preview(name = "choose-dashboards Phone Detail Dark", widthDp = 412, heightDp = 720)
@Composable
fun ChooseDashboardsDetailDarkPreview() =
  KeyScreen("choose-dashboards", dark = true, initialDetail = true)

@Preview(name = "choose-dashboards Tablet Detail Dark", widthDp = 840, heightDp = 720)
@Composable
fun ChooseDashboardsSecondaryDarkPreview() =
  KeyScreen("choose-dashboards", dark = true, initialDetail = true, initialSelectedIndex = 1)

@Preview(name = "settings Phone Light", widthDp = 412, heightDp = 720)
@Preview(name = "settings Tablet Light", widthDp = 840, heightDp = 720)
@Composable
fun SettingsLightPreview() = KeyScreen("settings", dark = false)

@Preview(name = "settings Phone Detail Light", widthDp = 412, heightDp = 720)
@Composable
fun SettingsDetailLightPreview() = KeyScreen("settings", dark = false, initialDetail = true)

@Preview(name = "settings Tablet Detail Light", widthDp = 840, heightDp = 720)
@Composable
fun SettingsSecondaryLightPreview() =
  KeyScreen("settings", dark = false, initialDetail = true, initialSelectedIndex = 1)

@Preview(name = "settings Phone Dark", widthDp = 412, heightDp = 720)
@Preview(name = "settings Tablet Dark", widthDp = 840, heightDp = 720)
@Composable
fun SettingsDarkPreview() = KeyScreen("settings", dark = true)

@Preview(name = "settings Phone Detail Dark", widthDp = 412, heightDp = 720)
@Composable
fun SettingsDetailDarkPreview() = KeyScreen("settings", dark = true, initialDetail = true)

@Preview(name = "settings Tablet Detail Dark", widthDp = 840, heightDp = 720)
@Composable
fun SettingsSecondaryDarkPreview() =
  KeyScreen("settings", dark = true, initialDetail = true, initialSelectedIndex = 1)

@Preview(name = "widgets Phone Light", widthDp = 412, heightDp = 720)
@Preview(name = "widgets Tablet Light", widthDp = 840, heightDp = 720)
@Composable
fun WidgetsLightPreview() = KeyScreen("widgets", dark = false)

@Preview(name = "widgets Phone Detail Light", widthDp = 412, heightDp = 720)
@Composable
fun WidgetsDetailLightPreview() = KeyScreen("widgets", dark = false, initialDetail = true)

@Preview(name = "widgets Tablet Detail Light", widthDp = 840, heightDp = 720)
@Composable
fun WidgetsSecondaryLightPreview() =
  KeyScreen("widgets", dark = false, initialDetail = true, initialSelectedIndex = 1)

@Preview(name = "widgets Phone Dark", widthDp = 412, heightDp = 720)
@Preview(name = "widgets Tablet Dark", widthDp = 840, heightDp = 720)
@Composable
fun WidgetsDarkPreview() = KeyScreen("widgets", dark = true)

@Preview(name = "widgets Phone Detail Dark", widthDp = 412, heightDp = 720)
@Composable
fun WidgetsDetailDarkPreview() = KeyScreen("widgets", dark = true, initialDetail = true)

@Preview(name = "widgets Tablet Detail Dark", widthDp = 840, heightDp = 720)
@Composable
fun WidgetsSecondaryDarkPreview() =
  KeyScreen("widgets", dark = true, initialDetail = true, initialSelectedIndex = 1)

@Preview(name = "pinned Phone Light", widthDp = 412, heightDp = 720)
@Preview(name = "pinned Tablet Light", widthDp = 840, heightDp = 720)
@Composable
fun PinnedLightPreview() = KeyScreen("pinned", dark = false)

@Preview(name = "pinned Phone Detail Light", widthDp = 412, heightDp = 720)
@Composable
fun PinnedDetailLightPreview() = KeyScreen("pinned", dark = false, initialDetail = true)

@Preview(name = "pinned Tablet Detail Light", widthDp = 840, heightDp = 720)
@Composable
fun PinnedSecondaryLightPreview() =
  KeyScreen("pinned", dark = false, initialDetail = true, initialSelectedIndex = 1)

@Preview(name = "pinned Phone Dark", widthDp = 412, heightDp = 720)
@Preview(name = "pinned Tablet Dark", widthDp = 840, heightDp = 720)
@Composable
fun PinnedDarkPreview() = KeyScreen("pinned", dark = true)

@Preview(name = "pinned Phone Detail Dark", widthDp = 412, heightDp = 720)
@Composable
fun PinnedDetailDarkPreview() = KeyScreen("pinned", dark = true, initialDetail = true)

@Preview(name = "pinned Tablet Detail Dark", widthDp = 840, heightDp = 720)
@Composable
fun PinnedSecondaryDarkPreview() =
  KeyScreen("pinned", dark = true, initialDetail = true, initialSelectedIndex = 1)

@Preview(name = "wear-widgets Phone Light", widthDp = 412, heightDp = 720)
@Preview(name = "wear-widgets Tablet Light", widthDp = 840, heightDp = 720)
@Composable
fun WearWidgetsLightPreview() = KeyScreen("wear-widgets", dark = false)

@Preview(name = "wear-widgets Phone Detail Light", widthDp = 412, heightDp = 720)
@Composable
fun WearWidgetsDetailLightPreview() = KeyScreen("wear-widgets", dark = false, initialDetail = true)

@Preview(name = "wear-widgets Tablet Detail Light", widthDp = 840, heightDp = 720)
@Composable
fun WearWidgetsSecondaryLightPreview() =
  KeyScreen("wear-widgets", dark = false, initialDetail = true, initialSelectedIndex = 1)

@Preview(name = "wear-widgets Phone Dark", widthDp = 412, heightDp = 720)
@Preview(name = "wear-widgets Tablet Dark", widthDp = 840, heightDp = 720)
@Composable
fun WearWidgetsDarkPreview() = KeyScreen("wear-widgets", dark = true)

@Preview(name = "wear-widgets Phone Detail Dark", widthDp = 412, heightDp = 720)
@Composable
fun WearWidgetsDetailDarkPreview() = KeyScreen("wear-widgets", dark = true, initialDetail = true)

@Preview(name = "wear-widgets Tablet Detail Dark", widthDp = 840, heightDp = 720)
@Composable
fun WearWidgetsSecondaryDarkPreview() =
  KeyScreen("wear-widgets", dark = true, initialDetail = true, initialSelectedIndex = 1)

@Preview(name = "sync-diagnostics Phone Light", widthDp = 412, heightDp = 720)
@Preview(name = "sync-diagnostics Tablet Light", widthDp = 840, heightDp = 720)
@Composable
fun SyncDiagnosticsLightPreview() = KeyScreen("sync-diagnostics", dark = false)

@Preview(name = "sync-diagnostics Phone Detail Light", widthDp = 412, heightDp = 720)
@Composable
fun SyncDiagnosticsDetailLightPreview() =
  KeyScreen("sync-diagnostics", dark = false, initialDetail = true)

@Preview(name = "sync-diagnostics Tablet Detail Light", widthDp = 840, heightDp = 720)
@Composable
fun SyncDiagnosticsSecondaryLightPreview() =
  KeyScreen("sync-diagnostics", dark = false, initialDetail = true, initialSelectedIndex = 1)

@Preview(name = "sync-diagnostics Phone Dark", widthDp = 412, heightDp = 720)
@Preview(name = "sync-diagnostics Tablet Dark", widthDp = 840, heightDp = 720)
@Composable
fun SyncDiagnosticsDarkPreview() = KeyScreen("sync-diagnostics", dark = true)

@Preview(name = "sync-diagnostics Phone Detail Dark", widthDp = 412, heightDp = 720)
@Composable
fun SyncDiagnosticsDetailDarkPreview() =
  KeyScreen("sync-diagnostics", dark = true, initialDetail = true)

@Preview(name = "sync-diagnostics Tablet Detail Dark", widthDp = 840, heightDp = 720)
@Composable
fun SyncDiagnosticsSecondaryDarkPreview() =
  KeyScreen("sync-diagnostics", dark = true, initialDetail = true, initialSelectedIndex = 1)

@Preview(name = "logs Phone Light", widthDp = 412, heightDp = 720)
@Preview(name = "logs Tablet Light", widthDp = 840, heightDp = 720)
@Composable
fun LogsLightPreview() = KeyScreen("logs", dark = false)

@Preview(name = "logs Phone Detail Light", widthDp = 412, heightDp = 720)
@Composable
fun LogsDetailLightPreview() = KeyScreen("logs", dark = false, initialDetail = true)

@Preview(name = "logs Tablet Detail Light", widthDp = 840, heightDp = 720)
@Composable
fun LogsSecondaryLightPreview() =
  KeyScreen("logs", dark = false, initialDetail = true, initialSelectedIndex = 1)

@Preview(name = "logs Phone Dark", widthDp = 412, heightDp = 720)
@Preview(name = "logs Tablet Dark", widthDp = 840, heightDp = 720)
@Composable
fun LogsDarkPreview() = KeyScreen("logs", dark = true)

@Preview(name = "logs Phone Detail Dark", widthDp = 412, heightDp = 720)
@Composable
fun LogsDetailDarkPreview() = KeyScreen("logs", dark = true, initialDetail = true)

@Preview(name = "logs Tablet Detail Dark", widthDp = 840, heightDp = 720)
@Composable
fun LogsSecondaryDarkPreview() =
  KeyScreen("logs", dark = true, initialDetail = true, initialSelectedIndex = 1)

@Preview(name = "card-history Phone Light", widthDp = 412, heightDp = 720)
@Preview(name = "card-history Tablet Light", widthDp = 840, heightDp = 720)
@Composable
fun CardHistoryLightPreview() = KeyScreen("card-history", dark = false)

@Preview(name = "card-history Phone Detail Light", widthDp = 412, heightDp = 720)
@Composable
fun CardHistoryDetailLightPreview() = KeyScreen("card-history", dark = false, initialDetail = true)

@Preview(name = "card-history Tablet Detail Light", widthDp = 840, heightDp = 720)
@Composable
fun CardHistorySecondaryLightPreview() =
  KeyScreen("card-history", dark = false, initialDetail = true, initialSelectedIndex = 1)

@Preview(name = "card-history Phone Dark", widthDp = 412, heightDp = 720)
@Preview(name = "card-history Tablet Dark", widthDp = 840, heightDp = 720)
@Composable
fun CardHistoryDarkPreview() = KeyScreen("card-history", dark = true)

@Preview(name = "card-history Phone Detail Dark", widthDp = 412, heightDp = 720)
@Composable
fun CardHistoryDetailDarkPreview() = KeyScreen("card-history", dark = true, initialDetail = true)

@Preview(name = "card-history Tablet Detail Dark", widthDp = 840, heightDp = 720)
@Composable
fun CardHistorySecondaryDarkPreview() =
  KeyScreen("card-history", dark = true, initialDetail = true, initialSelectedIndex = 1)

@Preview(name = "connection failure Phone Light", widthDp = 412, heightDp = 720)
@Preview(name = "connection failure Tablet Light", widthDp = 840, heightDp = 720)
@Composable
fun ConnectionFailureLightPreview() =
  KeyScreen("connection", dark = false, initialDetail = true, unavailable = true)

@Preview(name = "connection failure Phone Dark", widthDp = 412, heightDp = 720)
@Preview(name = "connection failure Tablet Dark", widthDp = 840, heightDp = 720)
@Composable
fun ConnectionFailureDarkPreview() =
  KeyScreen("connection", dark = true, initialDetail = true, unavailable = true)

@Preview(name = "dashboard-view offline Phone Light", widthDp = 412, heightDp = 720)
@Preview(name = "dashboard-view offline Tablet Light", widthDp = 840, heightDp = 720)
@Composable
fun DashboardViewOfflineLightPreview() =
  KeyScreen("dashboard-view", dark = false, initialDetail = true, unavailable = true)

@Preview(name = "dashboard-view offline Phone Dark", widthDp = 412, heightDp = 720)
@Preview(name = "dashboard-view offline Tablet Dark", widthDp = 840, heightDp = 720)
@Composable
fun DashboardViewOfflineDarkPreview() =
  KeyScreen("dashboard-view", dark = true, initialDetail = true, unavailable = true)

@Preview(name = "No dashboards selected Phone Light", widthDp = 412, heightDp = 720)
@Preview(name = "No dashboards selected Tablet Light", widthDp = 840, heightDp = 720)
@Composable
fun DashboardSelectionEmptyLightPreview() =
  KeyScreen("choose-dashboards", initialDetail = true, emptySelection = true)

@Preview(name = "No dashboards selected Phone Dark", widthDp = 412, heightDp = 720)
@Preview(name = "No dashboards selected Tablet Dark", widthDp = 840, heightDp = 720)
@Composable
fun DashboardSelectionEmptyDarkPreview() =
  KeyScreen("choose-dashboards", dark = true, initialDetail = true, emptySelection = true)
