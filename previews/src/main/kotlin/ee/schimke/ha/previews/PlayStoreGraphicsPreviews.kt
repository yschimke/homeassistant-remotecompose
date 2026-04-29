package ee.schimke.ha.previews

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ee.schimke.ha.rc.components.R as ComponentsR

/**
 * Play Store listing graphics rendered from the same adaptive-icon
 * resources (`ic_launcher_foreground` / `ic_launcher_background`) the
 * launcher uses, so a recolour of the brand only needs editing in one
 * place.
 *
 * - **Icon**: 512×512 PNG, opaque background, no transparency. Drawn
 *   as a full-bleed background square with the foreground vector
 *   centred at native size — this matches the in-app icon as users
 *   see it on a square launcher (no adaptive masking, no per-OEM
 *   safe-zone clip).
 * - **Feature graphic**: 1024×500 PNG, opaque background, no
 *   transparency. Banner laid out as icon + wordmark + tagline.
 *
 * Device dpi is fixed at 320 (2.0x density) so widthDp / heightDp
 * map cleanly to the required pixel dimensions:
 * 256dp×256dp → 512×512, 512dp×250dp → 1024×500.
 *
 * Rendered by `:previews:renderAllPreviews`; the
 * `scripts/render-play-assets.sh` helper copies the PNGs into
 * `app/src/main/play/listings/en-GB/graphics/{icon,feature-graphic}/`.
 */
private const val PLAY_ICON_DEVICE = "spec:width=256dp,height=256dp,dpi=320"
private const val PLAY_FEATURE_DEVICE = "spec:width=512dp,height=250dp,dpi=320"

@Preview(name = "play · icon · 512", showBackground = false, device = PLAY_ICON_DEVICE)
@Composable
fun Play_Icon_512() {
    // Full-bleed background — Play's hi-res icon is uploaded as a
    // square, not an adaptive icon, so the corner radius is applied
    // by Play itself when rendering on a phone listing.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(ComponentsR.color.ic_launcher_background)),
        contentAlignment = Alignment.Center,
    ) {
        // Foreground vector is sized for the 108dp adaptive canvas with
        // a 72dp safe zone; rendering at the full canvas size lets the
        // icon fill the square comfortably without the launcher's
        // safe-zone crop.
        Image(
            painter = painterResource(ComponentsR.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Preview(name = "play · feature · 1024x500", showBackground = false, device = PLAY_FEATURE_DEVICE)
@Composable
fun Play_Feature_1024x500() {
    val bg = colorResource(ComponentsR.color.ic_launcher_background)
    Row(
        modifier = Modifier.fillMaxSize().background(bg).padding(horizontal = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left-side icon block at ~320 px (160dp @ 2.0x). Sits on the
        // same cream as the surrounding banner so it reads as one
        // continuous brand surface, not a separate sticker.
        Box(
            modifier = Modifier.size(160.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(ComponentsR.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(24.dp))
        // Right column gets the remaining ~280dp of banner width. Cap
        // the wordmark + tagline to a single line each so neither wraps
        // into a second line at 512 dp banner width.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Terrazzo",
                color = Color(0xFF3A1E12),
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.displayLarge,
                maxLines = 1,
            )
            Text(
                text = "Compose dashboards for Home Assistant",
                color = Color(0xFF8C3A1C),
                fontSize = 16.sp,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
            )
        }
    }
}
