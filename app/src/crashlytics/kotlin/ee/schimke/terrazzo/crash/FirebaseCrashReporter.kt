package ee.schimke.terrazzo.crash

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Firebase Crashlytics-backed [CrashReporter]. Compiled into the app only when it's the selected
 * backend (`harc.crashReporter=firebase`, or `auto` with `google-services.json` present) — see the
 * selector in `app/build.gradle.kts`.
 *
 * Instantiated reflectively by [CrashReporter.create], so it must keep a no-arg constructor and
 * stay `internal` to this source set.
 *
 * [install] initialises Firebase, which registers Crashlytics' own uncaught-exception handler —
 * that's what reports *fatal* crashes. [recordCrash] is only for the non-fatal / handled exceptions
 * the app forwards explicitly.
 */
internal class FirebaseCrashReporter : CrashReporter {
  private val crashlytics: FirebaseCrashlytics by lazy { FirebaseCrashlytics.getInstance() }

  override fun install(context: Context) {
    FirebaseApp.initializeApp(context)
    crashlytics.isCrashlyticsCollectionEnabled = true
  }

  override fun recordCrash(throwable: Throwable) {
    crashlytics.recordException(throwable)
  }
}
