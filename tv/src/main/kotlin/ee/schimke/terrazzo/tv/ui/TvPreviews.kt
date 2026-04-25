package ee.schimke.terrazzo.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.terrazzoColorScheme
import ee.schimke.ha.rc.components.terrazzoTypographyFor

/**
 * TV @Preview fixtures. Sized at 680 × 384 dp — Robolectric renders these
 * at ~2.625× density, so the resulting PNG is 1785 × 1008 px (under the
 * 1800 px ceiling we want to keep TV captures at). The full
 * picker-plus-kiosk shell only fits at TV-native widths; for previews we
 * crop to the kiosk pane (the part that actually changes per palette /
 * demo flag), which is also the part the user looks at on the wall.
 */

@Preview(name = "TV: kiosk (demo)", widthDp = 680, heightDp = 384, showBackground = true)
@Composable
fun TvKioskPreview_Demo() {
    TvPreviewFrame(style = ThemeStyle.TerrazzoKiosk) {
        TvKioskPreview(
            style = ThemeStyle.TerrazzoKiosk,
            demoMode = true,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Preview(name = "TV: kiosk (live)", widthDp = 680, heightDp = 384, showBackground = true)
@Composable
fun TvKioskPreview_Live() {
    TvPreviewFrame(style = ThemeStyle.TerrazzoKiosk) {
        TvKioskPreview(
            style = ThemeStyle.TerrazzoKiosk,
            demoMode = false,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Preview(name = "TV: kiosk (Home palette)", widthDp = 680, heightDp = 384, showBackground = true)
@Composable
fun TvKioskPreview_HomePalette() {
    TvPreviewFrame(style = ThemeStyle.TerrazzoHome) {
        TvKioskPreview(
            style = ThemeStyle.TerrazzoHome,
            demoMode = true,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun TvPreviewFrame(
    style: ThemeStyle,
    content: @Composable () -> Unit,
) {
    val colors = terrazzoColorScheme(style, darkTheme = true)
    MaterialTheme(colorScheme = colors, typography = terrazzoTypographyFor(style)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .padding(20.dp),
        ) {
            Column(modifier = Modifier.fillMaxSize()) { content() }
        }
    }
}
