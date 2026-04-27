package ee.schimke.ha.previews

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.ha.rc.components.R as ComponentsR

@Preview(name = "app-icon (light)", widthDp = 360, heightDp = 200, showBackground = true, backgroundColor = 0xFFF5F1E6)
@Composable
fun AppIcon_Light() = AppIconShowcase(darkTheme = false)

@Preview(name = "app-icon (dark)", widthDp = 360, heightDp = 200, showBackground = true, backgroundColor = 0xFF1C1C1E, uiMode = 0x21)
@Composable
fun AppIcon_Dark() = AppIconShowcase(darkTheme = true)

@Composable
private fun AppIconShowcase(darkTheme: Boolean) {
    Surface(color = Color.Transparent) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (darkTheme) "Adaptive icon · dark" else "Adaptive icon · light",
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AdaptiveIcon(shape = CircleShape, label = "circle")
                AdaptiveIcon(shape = RoundedCornerShape(24.dp), label = "squircle")
                AdaptiveIcon(shape = RoundedCornerShape(8.dp), label = "rounded")
                AdaptiveIcon(shape = RoundedCornerShape(0.dp), label = "square")
            }
        }
    }
}

@Composable
private fun AdaptiveIcon(shape: Shape, label: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(shape)
                .background(colorResource(ComponentsR.color.ic_launcher_background)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(ComponentsR.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(72.dp),
            )
        }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
