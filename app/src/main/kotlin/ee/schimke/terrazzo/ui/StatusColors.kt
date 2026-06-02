package ee.schimke.terrazzo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * Semantic status colours for app chrome — connection state, log
 * severity, and the read/write indicator.
 *
 * These are deliberately **fixed across the Terrazzo palettes** (the
 * same rationale as
 * [HaStateColor][ee.schimke.ha.rc.HaStateColor]: a "failed" red reads as
 * red whatever palette the user picked — the palette describes the
 * chrome, not the status). But unlike a baked `Color(0xFF…)` literal,
 * they **adapt to light / dark** so a label or chip stays legible on
 * either surface. The light tones are Material 700-level; the dark tones
 * are the 200/300-level brightenings that hold contrast on a dark
 * background.
 *
 * This is the single home for what used to be duplicated hex literals
 * scattered across `TerrazzoApp` and `LogsScreen` — per the style guide,
 * screens never hardcode `Color(0xFF…)`; a missing role is added here.
 */
@Immutable
data class StatusColorSet(
    /** Failure / error / fatal crash. */
    val error: Color,
    /** Caught (non-fatal) problem, or the write-enabled indicator. */
    val warning: Color,
    /** In-progress / connecting. */
    val success: Color,
    /** Healthy / connected. */
    val info: Color,
    /** Paused / disconnected / inert. */
    val neutral: Color,
    /** Read-only indicator (muted blue-grey). */
    val readOnly: Color,
)

private val LightStatusColors = StatusColorSet(
    error = Color(0xFFD32F2F),
    warning = Color(0xFFE65100),
    success = Color(0xFF2E7D32),
    info = Color(0xFF1976D2),
    neutral = Color(0xFF757575),
    readOnly = Color(0xFF455A64),
)

private val DarkStatusColors = StatusColorSet(
    error = Color(0xFFF2B8B5),
    warning = Color(0xFFFFB74D),
    success = Color(0xFFA5D6A7),
    info = Color(0xFF90CAF9),
    neutral = Color(0xFFBDBDBD),
    readOnly = Color(0xFFB0BEC5),
)

/**
 * The active [StatusColorSet], resolved against [LocalIsDarkTheme] so it
 * tracks the user's dark-mode choice the same way the rest of the app
 * chrome does.
 */
val statusColors: StatusColorSet
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) DarkStatusColors else LightStatusColors
