@file:Suppress("COMPOSE_APPLIER_CALL_MISMATCH", "RestrictedApi")

package ee.schimke.ha.previews

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.profile.Profile
import androidx.compose.remote.player.compose.embedded.ExperimentalRemoteDocumentPlayer
import androidx.compose.remote.player.core.RemoteDocument
import androidx.compose.remote.tooling.preview.RemoteContentPreview
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.runBlocking

/**
 * The preview module's own Remote Compose host — the one place a preview's document is handed to a
 * player, so which player draws it is *our* decision rather than a detail of upstream's tooling
 * composable.
 *
 * **Why we don't just call `RemoteContentPreview`.** Upstream's host resolves to
 * `RemoteDocumentPlayer`, which is `AndroidView { RemoteComposePlayer }` — the `remote-player-view`
 * player, an Android `View` painting glyphs onto a framework `Canvas`. Everything the card is
 * therefore reaches Compose as *one* interop leaf. That is fine for a PNG and fatal for every data
 * tier derived from the composition: the `compose/figma-svg` export builds its layers from the
 * layout-inspector tree and its `<text>` from the semantics tree, so a document with no Compose
 * `Text` in it exports as a single `<image>` — the whole card, type included, flattened to pixels
 * (compose-ai-tools#2937). Semantics-derived products (accessibility trees, the wireframe) see the
 * same single opaque node.
 *
 * [RcPreviewPlayer.EMBEDDED] plays the same document through the Compose-native player instead,
 * which walks the document's component tree and emits real Compose layout nodes, `Text` included.
 * The exported SVG then carries editable `<text>` and the card's own shape, and the semantics tree
 * describes the card rather than a black box.
 *
 * **It is not the default yet, and the reason is measured, not theoretical.** An A/B of the two
 * lanes over the whole preview set (171 of 229 renders differ; evidence in
 * `docs/previews/before-after/rc-player-*.png`) found the Compose-native lane dropping non-text
 * content the view player draws: the tinted badge circle behind an "on"-state icon disappears
 * (button, tile, glance, grid, horizontal-stack) and the grid card renders three of its four tiles.
 * Text-only cards are at parity. That is the same class of gap compose-ai-tools#2937 measured on
 * the reference catalog, still present for these cards, so defaulting to it would trade a flat SVG
 * for wrong pixels. The default stays [RcPreviewPlayer.VIEW] until the player draws these cards
 * correctly; flipping it is a one-line change here plus a re-render.
 *
 * **The document is unchanged either way.** Both lanes play the identical captured bytes; the
 * choice is only who interprets them — the A/B confirmed all 164 `.rc` sidecars byte-identical
 * across lanes. So the sidecar ([CapturingRemoteContentPreview]) and everything downstream of it —
 * the browser JS lane, the parity page — are unaffected by which lane runs.
 *
 * The Compose-native lane is reachable on demand: the render harness selects it with
 * `?rcPlayer=cmp-android`, which is how to get an SVG with live text out of a preview today.
 */
@Composable
internal fun HaRemoteContentPreview(
  profile: Profile,
  modifier: Modifier = Modifier,
  content: @Composable @RemoteComposable () -> Unit,
) {
  when (rcPreviewPlayer()) {
    RcPreviewPlayer.EMBEDDED -> EmbeddedRemoteContentPreview(profile, modifier, content)
    RcPreviewPlayer.VIEW ->
      RemoteContentPreview(modifier = modifier, profile = profile, content = content)
  }
}

/**
 * Capture [content] under [profile] and play it with the Compose-native (embedded) player.
 *
 * Deliberately shaped like upstream's `RemoteContentPreview` / `RemoteDocumentPreview` pair so the
 * two lanes differ in exactly one thing — the player. Same capture call, same `remember(profile,
 * content)` memoisation, same plain `RemoteDocument(bytes)` construction, same `Box {
 * player.fillMaxSize() }` so the document sizes to the container the caller pinned.
 *
 * The bytes are **not** density-stamped here. The stamp belongs to the published `.rc` sidecar,
 * where a browser player has no generation density to recover; a player rendering in the same
 * process that captured already has it, and stamping would make the drawn pixels depend on whether
 * the sidecar happened to be captured. See `stampGenerationDensity` in `RcDocumentCapture.kt`.
 *
 * Best-effort: a capture that the profile rejects yields an empty box rather than failing the
 * render, matching how the sidecar capture degrades.
 */
@Composable
private fun EmbeddedRemoteContentPreview(
  profile: Profile,
  modifier: Modifier,
  content: @Composable @RemoteComposable () -> Unit,
) {
  val context = LocalContext.current
  val document: RemoteDocument? =
    remember(profile, content) {
      runCatching {
          runBlocking {
            RemoteDocument(
              captureSingleRemoteDocument(context = context, profile = profile, content = content)
                .bytes
            )
          }
        }
        .getOrNull()
    }
  Box(modifier) {
    if (document != null) {
      ExperimentalRemoteDocumentPlayer(document = document, modifier = Modifier.fillMaxSize())
    }
  }
}

/** Which player [HaRemoteContentPreview] hands the captured document to. */
internal enum class RcPreviewPlayer {
  /** `remote-player-view`'s Android `View`, bridged in through `AndroidView`. */
  VIEW,
  /**
   * The Compose-native player, which emits real Compose nodes. Opt-in — see
   * [HaRemoteContentPreview] for the measured regressions keeping it out of the default.
   */
  EMBEDDED,
}

/**
 * The player this render asked for, defaulting to [RcPreviewPlayer.VIEW].
 *
 * Four inputs, most specific first:
 * 1. The compose-ai-tools daemon's `renderNow.overrides.remoteCompose.player` — what the preview
 *    server's `?rcPlayer=java` / `?rcPlayer=cmp-android` chips set. Read reflectively so this
 *    module neither compiles nor links against the connector: it is supplied by the render harness,
 *    is absent in the app and the IDE, and reading it through its own classloader is what
 *    guarantees we observe the same process-static state the daemon seeded rather than a second
 *    copy of it.
 * 2. The `ha.rc.player` system property, which the `haRcPlayer` Gradle property sets:
 *    `previews/build.gradle.kts` forwards it onto the render task. Verified to arrive when set in
 *    `gradle.properties`; a bare `-Dha.rc.player=` does **not** work (the compose-preview plugin
 *    curates the render fork's system properties) and neither does the CLI's `--gradle-arg`, which
 *    is silently ignored.
 * 3. The `HA_RC_PLAYER` environment variable, for a shell that exports one into the render JVM.
 *    Only reliable against a cold Gradle daemon: the render fork inherits the *daemon's*
 *    environment, not the invoking shell's, so against a warm daemon this silently does nothing —
 *    which reads exactly like the flag being ignored. Prefer `-PhaRcPlayer=`.
 * 4. The default.
 *
 * Also forced to [RcPreviewPlayer.VIEW] whenever the embedded player is missing at runtime, so a
 * consumer without that artifact still draws — an explicit `cmp-android` request included.
 */
private fun rcPreviewPlayer(): RcPreviewPlayer {
  if (!embeddedPlayerAvailable) return RcPreviewPlayer.VIEW
  daemonRequestedPlayer()?.let {
    return it
  }
  parsePlayer(System.getProperty(PLAYER_PROPERTY))?.let {
    return it
  }
  parsePlayer(System.getenv(PLAYER_ENV))?.let {
    return it
  }
  return RcPreviewPlayer.VIEW
}

/** The `-Dha.rc.player=` escape hatch; accepts either spelling of each lane. */
private const val PLAYER_PROPERTY = "ha.rc.player"

/** The `HA_RC_PLAYER=` escape hatch — the one the Gradle render fork actually inherits. */
private const val PLAYER_ENV = "HA_RC_PLAYER"

private fun parsePlayer(raw: String?): RcPreviewPlayer? =
  when (raw?.lowercase()) {
    "view",
    "java" -> RcPreviewPlayer.VIEW
    "embedded",
    "cmp-android" -> RcPreviewPlayer.EMBEDDED
    else -> null
  }

/**
 * `RemoteComposeController.player.value`, or null when the connector isn't loaded or has no player
 * seeded for this render.
 *
 * Reflection rather than a `compileOnly` dependency: the connector's state is a Kotlin `object`, so
 * "the same state the daemon wrote" means "the same loaded class". Compiling against our own copy
 * would work right up until the render classpath supplied a second one, at which point the override
 * would silently stop being observed — a failure that looks exactly like the user not having
 * clicked the chip. Going through `Class.forName` on the ambient classloader can only ever find the
 * loaded one.
 *
 * The reflective read costs a handful of `Method.invoke`s per composition and is resolved once per
 * process; a failure at any step means "no daemon override", never a broken render.
 */
private fun daemonRequestedPlayer(): RcPreviewPlayer? =
  runCatching {
      val controller = controllerAccess ?: return null
      val state = controller.player.invoke(controller.instance) ?: return null
      val kind = controller.stateValue.invoke(state) ?: return null
      parsePlayer((kind as Enum<*>).name)
    }
    .getOrNull()

private class ControllerAccess(
  val instance: Any,
  val player: java.lang.reflect.Method,
  val stateValue: java.lang.reflect.Method,
)

private val controllerAccess: ControllerAccess? by
  lazy(LazyThreadSafetyMode.PUBLICATION) {
    runCatching {
        val type = Class.forName("ee.schimke.composeai.daemon.RemoteComposeController")
        val instance = checkNotNull(type.getField("INSTANCE").get(null))
        val player = type.getMethod("getPlayer")
        val stateValue = Class.forName("androidx.compose.runtime.State").getMethod("getValue")
        ControllerAccess(instance, player, stateValue)
      }
      .getOrNull()
  }

/**
 * Whether the vendored embedded player is on the runtime classpath. Mirrors the same classloader
 * gate compose-ai-tools' connector uses before offering the lane — a consumer that doesn't ship
 * `third-party-rc-embedded-player` falls back to the view player instead of dying with
 * `NoClassDefFoundError`.
 */
private val embeddedPlayerAvailable: Boolean by
  lazy(LazyThreadSafetyMode.PUBLICATION) {
    runCatching {
        Class.forName(
          "androidx.compose.remote.player.compose.embedded.ExperimentalRemoteDocumentPlayerKt"
        )
      }
      .isSuccess
  }
