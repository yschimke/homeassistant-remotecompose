package ee.schimke.terrazzo.crash

import android.content.Context
import android.os.Bundle
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
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
  @Volatile private var analytics: FirebaseAnalytics? = null

  override fun install(context: Context) {
    FirebaseApp.initializeApp(context)
    analytics = FirebaseAnalytics.getInstance(context)
    crashlytics.isCrashlyticsCollectionEnabled = true
  }

  override fun recordCrash(throwable: Throwable) {
    crashlytics.recordException(throwable)
  }

  override fun logEvent(name: String, params: Map<String, Long>) {
    val bundle =
      Bundle(params.size).apply { params.forEach { (key, value) -> putLong(key, value) } }
    analytics?.logEvent(name, bundle)
  }
}
