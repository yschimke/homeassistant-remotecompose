package ee.schimke.adaptivepilot

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

// IDE handles. Custom bundle IDs come from RenderPilot, not annotation discovery.
@Preview(name = "Phone ListLight", widthDp = 412, heightDp = 720)
@Preview(name = "Tablet ListLight", widthDp = 840, heightDp = 720)
@Composable
fun DashboardBrowserListLightPreview() = DashboardBrowser(dark = false, initialDetail = false)

@Preview(name = "Phone DetailLight", widthDp = 412, heightDp = 720)
@Preview(name = "Tablet DetailLight", widthDp = 840, heightDp = 720)
@Composable
fun DashboardBrowserDetailLightPreview() = DashboardBrowser(dark = false, initialDetail = true)

@Preview(name = "Phone ListDark", widthDp = 412, heightDp = 720)
@Preview(name = "Tablet ListDark", widthDp = 840, heightDp = 720)
@Composable
fun DashboardBrowserListDarkPreview() = DashboardBrowser(dark = true, initialDetail = false)

@Preview(name = "Phone DetailDark", widthDp = 412, heightDp = 720)
@Preview(name = "Tablet DetailDark", widthDp = 840, heightDp = 720)
@Composable
fun DashboardBrowserDetailDarkPreview() = DashboardBrowser(dark = true, initialDetail = true)
