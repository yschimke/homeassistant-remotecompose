package ee.schimke.terrazzo.crash

import android.content.Context
import ee.schimke.terrazzo.BuildConfig

/**
 * Thin seam over an external crash-reporting backend so the rest of the app never imports Firebase
 * directly. The in-app [ee.schimke.terrazzo.core.logs.LogStore] is always the primary sink; this is
 * the *optional* off-device backend layered on top.
 *
 * Which backend exists is a build-time decision, `-Pharc.crashReporter=<auto|firebase|sentry|none>`
 * (see `app/build.gradle.kts`): only the selected implementation and its dependencies are compiled
 * in, and its name lands in [BuildConfig.CRASH_REPORTER]. The default `auto` picks Firebase when a
 * Crashlytics key (`google-services.json`) is present and otherwise [NoopCrashReporter]. A Sentry
 * backend is tracked in https://github.com/yschimke/homeassistant-remotecompose/issues/1001.
 */
interface CrashReporter {
  /**
   * Initialise the backend. Safe to call once, early in `Application.onCreate`. A real backend
   * typically registers its own uncaught-exception handler here.
   */
  fun install(context: Context)

  /**
   * Forward a non-fatal / handled throwable to the backend. Uncaught fatal crashes are captured by
   * the backend's own handler (installed in [install]), so this is for exceptions the app caught
   * and recovered from but still wants reported.
   */
  fun recordCrash(throwable: Throwable)

  companion object {
    /**
     * The reporter for the backend this app was built with ([BuildConfig.CRASH_REPORTER]), or a
     * no-op when none was selected.
     *
     * Each backend lives in its own source set, compiled in only when selected, so it's resolved
     * reflectively here to keep `src/main` free of any compile-time SDK dependency. A backend
     * missing from the classpath falls back to the no-op rather than failing app start.
     */
    fun create(): CrashReporter = create(BuildConfig.CRASH_REPORTER)

    internal fun create(backend: String): CrashReporter {
      val className = backendClasses[backend] ?: return NoopCrashReporter
      return runCatching {
          Class.forName(className).getDeclaredConstructor().newInstance() as CrashReporter
        }
        .getOrDefault(NoopCrashReporter)
    }

    /** Backend name (the `harc.crashReporter` value) to its implementation class. */
    private val backendClasses =
      mapOf("firebase" to "ee.schimke.terrazzo.crash.FirebaseCrashReporter")
  }
}

/** Used when no backend is selected; the LogStore sink stands alone. */
internal object NoopCrashReporter : CrashReporter {
  override fun install(context: Context) = Unit

  override fun recordCrash(throwable: Throwable) = Unit
}
