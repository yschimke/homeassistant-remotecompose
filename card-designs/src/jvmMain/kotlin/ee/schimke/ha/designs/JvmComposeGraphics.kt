package ee.schimke.ha.designs

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.registerSkikoComposeImplementation

/**
 * Compose's graphics on a plain JVM (#1464). Compose desktop registers its Skiko implementation
 * when a scene starts, and a host that captures cards before any scene (the library generator, the
 * design server's capture on request) starts none, so card code that draws an `ImageBitmap` there
 * threw "No Compose UI graphics implementation is registered", or fell back to a look the app never
 * shows. Every JVM host that captures or draws cards calls [ensureRegistered] first.
 *
 * Internal API, but the jars it binds are pinned to the one Compose version this build uses.
 */
object JvmComposeGraphics {
  private val registered by lazy {
    @OptIn(InternalComposeUiApi::class) registerSkikoComposeImplementation()
  }

  /** Registers Compose's Skiko graphics, once per process. */
  fun ensureRegistered() = registered
}
