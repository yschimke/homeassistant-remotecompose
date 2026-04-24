package ee.schimke.terrazzo.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.terrazzoColorScheme
import ee.schimke.ha.rc.components.terrazzoTypographyFor

/**
 * TV stub. HA dashboards on a TV want the "kiosk" treatment: large type,
 * high-contrast surfaces, minimal chrome. The stub renders the active
 * Terrazzo palette in its dark (10-ft) variant so the wall-dashboard
 * tokens can be previewed on a 4K panel without the full app.
 *
 * Hard-wires [ThemeStyle.TerrazzoKiosk] to start — TV is the natural
 * home for the kiosk treatment. A picker and the rest of the TV app
 * land in a follow-up.
 */
class TvMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TvStub(style = ThemeStyle.TerrazzoKiosk) }
    }
}

@Composable
private fun TvStub(style: ThemeStyle) {
    val colors = terrazzoColorScheme(style, darkTheme = true)
    MaterialTheme(colorScheme = colors, typography = terrazzoTypographyFor(style)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .padding(horizontal = 64.dp, vertical = 48.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Terrazzo",
                    style = MaterialTheme.typography.displayLarge,
                    color = colors.primary,
                )
                Text(
                    text = "TV kiosk dashboard · coming soon",
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.onSurface,
                )
                Text(
                    text = "Active theme: ${style.displayName}",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}
