@file:Suppress("RestrictedApi", "INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.capture.RemoteComposeCreationState
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.RemoteConstantCacheKey

/**
 * A [RemoteColor] whose value is **written into the document** by [writeId] and referenced by id,
 * rather than baked into the operation that uses it.
 *
 * This is the seam the alpha does not expose publicly, and getting it wrong is silent. Every
 * `RemoteColor` either carries a constant value or provides an id, and consumers branch on which:
 * `BackgroundModifier` checks `hasConstantValue` and writes literal red/green/blue through
 * `SolidBackgroundModifier` when there is one, taking `DynamicSolidBackgroundModifier` — the id
 * path — only when there is not. So a colour that means to write itself into the document must be
 * built *without* a constant.
 *
 * Subclassing `RemoteColor(fallbackArgb)` and overriding `writeToDocument` looks like it does this
 * and does not: the constructor sets a constant, so the modifier never asks for an id and the
 * override is dead code everywhere except a direct call. That is not hypothetical — it is how
 * [SystemRemoteHaTheme] was first written, and it produced launcher documents containing no
 * `ColorTheme` operation at all while rendering correctly, because the constant it fell back to was
 * the light fallback a host without the resources would have drawn anyway.
 *
 * [cacheKeyIdentity] is what the document dedupes on: two colours with equal identities share one
 * entry, so it must capture everything that distinguishes the written value.
 *
 * The `internal` reach is deliberate and confined here rather than spread across call sites.
 * Upstream marks both the id-provider constructor and `RemoteConstantCacheKey` internal, and offers
 * no public route to a document-written colour at `remote-compose` 1.0.0-alpha17 — the only
 * alternative is the low-level writer (`RecordingModifier().backgroundId(...)`), which the
 * `RemoteModifier` composable API these components are built on cannot reach. Same reach, and same
 * rationale, as [LocalNamedRemoteBitmap]. When a public API appears, this file is the single place
 * to retire.
 */
fun remoteDocumentColor(
  cacheKeyIdentity: String,
  writeId: (RemoteComposeCreationState) -> Int,
): RemoteColor = RemoteColor(RemoteConstantCacheKey(cacheKeyIdentity), writeId)
