import org.gradle.api.GradleException
import org.gradle.api.Project

/**
 * The one Firebase key for every app module. `:app` and `:wear` share the package id
 * (`ee.schimke.harc`), so a single Firebase Android app — and this one file — covers both.
 */
const val GOOGLE_SERVICES_JSON = "app/google-services.json"

/**
 * Resolves `-Pharc.crashReporter=<auto|firebase|sentry|none>` to the backend an app module
 * compiles in: `firebase` or `none`. Shared so the phone and watch apps can't disagree.
 *   auto      (default) firebase when [GOOGLE_SERVICES_JSON] is present, otherwise none.
 *   firebase  Crashlytics; requires [GOOGLE_SERVICES_JSON].
 *   sentry    reserved, not built yet — fails configuration (see #1001).
 *   none      no backend even when a key is present.
 *
 * The Firebase plugin classpath is added conditionally in the root build.gradle.kts, which
 * can't call this (its buildscript block resolves before build-logic is on the classpath), so
 * keep the two in step.
 */
fun Project.selectCrashReporter(): String {
  val hasGoogleServices = rootDir.resolve(GOOGLE_SERVICES_JSON).exists()
  return when (
    val requested =
      providers.gradleProperty("harc.crashReporter").orNull?.trim()?.lowercase() ?: "auto"
  ) {
    "auto" -> if (hasGoogleServices) "firebase" else "none"
    "firebase" -> {
      if (!hasGoogleServices) {
        throw GradleException("harc.crashReporter=firebase needs $GOOGLE_SERVICES_JSON")
      }
      "firebase"
    }
    "sentry" ->
      throw GradleException(
        "harc.crashReporter=sentry is not implemented yet, see " +
          "https://github.com/yschimke/homeassistant-remotecompose/issues/1001"
      )
    "none" -> "none"
    else ->
      throw GradleException(
        "Unknown harc.crashReporter '$requested'; expected auto, firebase, sentry or none"
      )
  }
}
