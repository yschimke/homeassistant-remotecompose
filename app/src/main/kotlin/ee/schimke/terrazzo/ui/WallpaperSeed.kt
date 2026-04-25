package ee.schimke.terrazzo.ui

import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode

/**
 * Read the system wallpaper's primary colour and observe live updates.
 *
 * `WallpaperManager.getWallpaperColors(FLAG_SYSTEM)` exposes the dominant
 * colour the platform extracts for Material You. Feeding that seed
 * into materialkolor's `dynamicColorScheme(...)` lets us derive every
 * M3 role with our own [PaletteStyle] + contrast tuning, instead of
 * accepting whatever the platform `dynamicLightColorScheme(context)`
 * decides — same source, our derivation.
 *
 * Returns `null` below Android 8.1 (the API floor for `getWallpaperColors`)
 * or when the user hasn't set a wallpaper colour the platform can sample.
 *
 * Inside Compose tooling / preview-render harnesses (`LocalInspectionMode`
 * is on), returns a fixed [PREVIEW_WALLPAPER_SEED] instead. Whatever the
 * harness's `WallpaperManager` returns (Robolectric defaults, sometimes
 * null, sometimes a platform fallback) would otherwise re-seed the M3
 * ColorScheme on every render and produce false preview diffs — but
 * returning `null` for previews collapses the M3 dashboard onto stock
 * `lightColorScheme()` / `darkColorScheme()`, defeating the point of
 * having an M3 variant in the gallery. A pinned seed keeps the
 * materialkolor `dynamicColorScheme(...)` path live and deterministic.
 */
@Composable
fun rememberWallpaperSeedColor(): Color? {
    if (LocalInspectionMode.current) return PREVIEW_WALLPAPER_SEED
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return null
    val context = LocalContext.current
    var seed by remember(context) { mutableStateOf(readWallpaperSeed(context)) }
    DisposableEffect(context) {
        val wm = WallpaperManager.getInstance(context)
        val listener = WallpaperManager.OnColorsChangedListener { colors, which ->
            if (which and WallpaperManager.FLAG_SYSTEM != 0) {
                seed = colors?.primaryColor?.toArgb()?.let(::Color)
            }
        }
        wm.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper()))
        onDispose { wm.removeOnColorsChangedListener(listener) }
    }
    return seed
}

/**
 * Stand-in wallpaper seed used when `LocalInspectionMode` is on. Picked to
 * be distinct from every Terrazzo palette seed (HA blue, warm ochre, slate,
 * teal) and from the M3 baseline purple, so the Material3 dashboard
 * preview reads as a recognisably-different "dynamic wallpaper" variant
 * rather than collapsing onto another palette in the gallery.
 */
private val PREVIEW_WALLPAPER_SEED = Color(0xFFB58392)

private fun readWallpaperSeed(context: Context): Color? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return null
    return runCatching {
        WallpaperManager.getInstance(context)
            .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            ?.primaryColor
            ?.toArgb()
            ?.let(::Color)
    }.getOrNull()
}
