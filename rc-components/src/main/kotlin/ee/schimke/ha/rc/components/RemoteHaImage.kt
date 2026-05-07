@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteImage
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.state.RemoteState
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.compose.state.rememberMutableRemoteBitmap
import androidx.compose.remote.creation.compose.state.rememberNamedRemoteBitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale

/**
 * Sample image composables that exercise the two ways RemoteCompose can
 * carry an image into a `.rc` document.
 *
 * - [RemoteHaImageInline] embeds the bitmap bytes directly. The
 *   document is fully self-contained (works in `RemotePreview` with
 *   no extra wiring) at the cost of bloating the `.rc` payload.
 * - [RemoteHaImageNamed] embeds only the image *name*. At playback
 *   time the host's `BitmapLoader` resolves the name to bytes; the
 *   placeholder is what `RemotePreview` and any player without a
 *   loader will show. Pair this with `CoilBitmapLoader` from
 *   `rc-image-coil` for HTTP / disk-cached resolution.
 */

/** Bake [bitmap] into the document. */
@Composable
@RemoteComposable
fun RemoteHaImageInline(
    bitmap: ImageBitmap,
    contentDescription: RemoteString,
    modifier: RemoteModifier = RemoteModifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val rb = rememberMutableRemoteBitmap(bitmap)
    RemoteImage(
        remoteBitmap = rb,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
    )
}

/**
 * Reference [name] in the document; the player resolves it via its
 * `BitmapLoader`. [placeholder] is rendered before the loader returns
 * (and remains in players that do not provide one).
 */
@Composable
@RemoteComposable
fun RemoteHaImageNamed(
    name: String,
    placeholder: ImageBitmap,
    contentDescription: RemoteString,
    modifier: RemoteModifier = RemoteModifier,
    contentScale: ContentScale = ContentScale.Fit,
    domain: RemoteState.Domain = RemoteState.Domain.User,
) {
    val rb = rememberNamedRemoteBitmap(
        name = name,
        domain = domain,
        content = { placeholder },
    )
    RemoteImage(
        remoteBitmap = rb,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
    )
}
