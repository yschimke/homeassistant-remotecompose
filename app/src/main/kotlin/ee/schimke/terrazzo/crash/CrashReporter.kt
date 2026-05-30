package ee.schimke.terrazzo.crash

import android.content.Context
import ee.schimke.terrazzo.BuildConfig

/**
 * Thin seam over an external crash-reporting backend so the rest of the
 * app never imports Firebase directly. The in-app
 * [ee.schimke.terrazzo.core.logs.LogStore] is always the primary sink;
 * this is the *optional* off-device backend layered on top.
 *
 * Whether a real backend exists is a build-time decision: the Firebase
 * implementation and its dependencies are only compiled into the app
 * when a Crashlytics key (`google-services.json`) is present — see the
 * `crashlyticsEnabled` wiring in `app/build.gradle.kts`. Without a key
 * the app builds and runs exactly as before, against [NoopCrashReporter].
 */
interface CrashReporter {
    /**
     * Initialise the backend. Safe to call once, early in
     * `Application.onCreate`. A real backend typically registers its own
     * uncaught-exception handler here.
     */
    fun install(context: Context)

    /**
     * Forward a non-fatal / handled throwable to the backend. Uncaught
     * fatal crashes are captured by the backend's own handler (installed
     * in [install]), so this is for exceptions the app caught and
     * recovered from but still wants reported.
     */
    fun recordCrash(throwable: Throwable)

    companion object {
        /**
         * The Firebase-backed reporter when the app was built with a
         * Crashlytics key ([BuildConfig.CRASHLYTICS_ENABLED]), otherwise
         * a no-op.
         *
         * The Firebase impl lives in the `src/crashlytics` source set,
         * compiled in only when the key is present, so it's resolved
         * reflectively here to keep `src/main` free of any compile-time
         * Firebase dependency. The reflection can only succeed when the
         * source set (and thus the class) is on the classpath, and the
         * `BuildConfig` guard short-circuits before we even try in the
         * common no-key build.
         */
        fun create(): CrashReporter {
            if (!BuildConfig.CRASHLYTICS_ENABLED) return NoopCrashReporter
            return runCatching {
                Class.forName("ee.schimke.terrazzo.crash.FirebaseCrashReporter")
                    .getDeclaredConstructor()
                    .newInstance() as CrashReporter
            }.getOrDefault(NoopCrashReporter)
        }
    }
}

/** Used when no Crashlytics key is configured; the LogStore sink stands alone. */
internal object NoopCrashReporter : CrashReporter {
    override fun install(context: Context) = Unit

    override fun recordCrash(throwable: Throwable) = Unit
}
