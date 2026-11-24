package ee.schimke.terrazzo.crash

import android.content.Context
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import ee.schimke.terrazzo.core.di.AppScope

/**
 * Thin seam over an external crash-reporting backend so the rest of the app never imports Firebase
 * directly. The in-app [ee.schimke.terrazzo.core.logs.LogStore] is always the primary sink; this is
 * the *optional* off-device backend layered on top.
 *
 * Which backend exists is a build-time decision, `-Pharc.crashReporter=<auto|firebase|sentry|none>`
 * (see `app/build.gradle.kts`): only the selected implementation and its dependencies are compiled
 * in, and Metro binds the compiled backend into [AppScope] via `@ContributesBinding` (`replaces`
 * [NoopCrashReporter]). The default `auto` picks Firebase when a Crashlytics key
 * (`google-services.json`) is present and otherwise [NoopCrashReporter]. A Sentry backend is
 * tracked in https://github.com/yschimke/homeassistant-remotecompose/issues/1001.
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

  /**
   * Logs an analytics event [name] with [params] (each a number or a string), where the backend has
   * analytics (Firebase does; the others drop it). Names and keys follow Firebase's rules: letters,
   * digits and underscores, at most 40 characters.
   */
  fun logEvent(name: String, params: Map<String, Any>) = Unit
}

/** Used when no backend is selected; the LogStore sink stands alone. */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class NoopCrashReporter : CrashReporter {
  override fun install(context: Context) = Unit

  override fun recordCrash(throwable: Throwable) = Unit
}
