package ee.schimke.terrazzo.wear.widget

/**
 * Hands a Wear widget's encoded RemoteCompose document to the compose-preview render harness, which
 * writes it next to the rendered PNG as the `renders/<stem>.rc` sidecar and packs it as the
 * preview's **IR** in the portable bundle.
 *
 * A Wear widget's value is its encoded document — the `RemoteDocument` byte stream the widget host
 * replays. With the sidecar present the widget travels in a bundle as *data*; without it the bundle
 * can only carry the compiled `@Preview` bytecode. The Glance Wear preview helpers already build
 * that document (`WearWidgetData.captureRawContent(...).rcDocument`) but keep the bytes to
 * themselves and only rasterise, so the IR was being thrown away right after it was computed.
 *
 * Reached **reflectively**, on purpose. `IrSidecarChannel` lives in
 * `ee.schimke.composeai:data-render-core`, which the compose-preview CLI injects onto the *render*
 * classpath; it is not — and should not become — a dependency of the shipped Wear APK for the sake
 * of a preview-only concern. So this resolves the class if it is there and no-ops if it is not,
 * which is also exactly the channel's own contract: an offer outside a daemon/test render (bare
 * unit test, Android Studio preview pane, the real app on a watch) is a no-op anyway.
 *
 * Best-effort but not silent: a linkage or signature failure is reported on stderr rather than
 * swallowed, so "renders fine, emits no `.rc`" is diagnosable from the render log instead of
 * invisible.
 */
internal object WearWidgetIrSidecar {

  private const val CHANNEL_CLASS = "ee.schimke.composeai.data.render.IrSidecarChannel"
  private const val FORMAT_REMOTECOMPOSE = "remotecompose"

  /** Resolved once per process: the channel's `offer` method, or null when it isn't on the path. */
  private val offerMethod by lazy {
    runCatching {
        val cls = Class.forName(CHANNEL_CLASS)
        // `offer(format: String, bytes: ByteArray, resourcesBytes: ByteArray?)` — the full arity,
        // since Kotlin default arguments aren't visible as overloads through reflection.
        cls.getMethod("offer", String::class.java, ByteArray::class.java, ByteArray::class.java)
      }
      .getOrNull()
  }

  /** Resolved once per process: the `IrSidecarChannel` singleton instance. */
  private val channelInstance by lazy {
    runCatching { Class.forName(CHANNEL_CLASS).getField("INSTANCE").get(null) }.getOrNull()
  }

  /**
   * Offer [rcDocument] as the current render's Remote Compose IR. No-op when the render harness
   * isn't on the classpath, or when there is no render in progress.
   */
  fun offer(rcDocument: ByteArray) {
    val method = offerMethod ?: return
    val instance = channelInstance ?: return
    try {
      method.invoke(instance, FORMAT_REMOTECOMPOSE, rcDocument, null)
    } catch (t: Throwable) {
      System.err.println(
        "WearWidgetIrSidecar: failed to offer the widget's RemoteCompose document; this render " +
          "will emit no .rc sidecar. Cause: $t"
      )
    }
  }
}
