package ee.schimke.ha.rc

import android.graphics.Bitmap
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Suspending host-side hook for fetching an image URL to a [Bitmap].
 *
 * Used by `CachedCardPreview` to refresh picture-entity tiles in a
 * running player: when an entity's `entity_picture` URL rotates (HA's
 * `?token=` cycles, a camera updates its thumbnail, a media player
 * changes track), the host fetches fresh bytes and pushes them through
 * `StateUpdater.setUserLocalBitmap` so the displayed image updates
 * **without** re-capturing the `.rc` document.
 *
 * Implementations live in the host module (Coil-backed in
 * `rc-image-coil`; a fake in `previews` for tests / @Preview). The
 * interface stays neutral so this module doesn't acquire a Coil
 * dependency.
 *
 * Returning `null` skips the push: the player keeps whatever bitmap it
 * already has (placeholder, the URL-fetched first frame, or the
 * previously pushed override).
 */
fun interface RemoteImageResolver {
  suspend fun resolve(url: String): Bitmap?
}

/**
 * CompositionLocal a host provides to give the captured cards a way to
 * refresh their picture-entity bitmaps. Defaults to `null` (no
 * refresh) so unwired call sites still render — first-paint bytes come
 * from the player's `BitmapLoader` exactly as before.
 */
val LocalRemoteImageResolver = staticCompositionLocalOf<RemoteImageResolver?> { null }
