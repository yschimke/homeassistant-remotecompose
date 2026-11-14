plugins {
  id("harc.base-conventions")
  alias(libs.plugins.kotlin.jvm)
}

// End-to-end tests that need real services: Docker (Home Assistant via Testcontainers) and a
// connected Android emulator or device (adb). Like OkHttp's container tests, they are off unless
// asked for, so `./gradlew test` never needs either:
//
//   ./gradlew :app-container-tests:test -PcontainerTests=true
//
// CI runs them in `.github/workflows/app-restore-emulator.yml`.

kotlin { jvmToolchain(libs.versions.java.get().toInt()) }

dependencies {
  testImplementation(libs.kotlin.test)
  testImplementation(libs.junit)
  testImplementation(libs.testcontainers)
  testImplementation(libs.kotlinx.serialization.json)
}

val containerTests =
  providers.gradleProperty("containerTests").map { it.toBoolean() }.orElse(false).get()

tasks.test {
  // Skipped unless -PcontainerTests=true: it needs Docker and an emulator.
  enabled = containerTests
  // The APKs the test installs. Built only when the tests run, so a plain `test` doesn't pay for
  // them.
  if (containerTests) dependsOn(":app:assembleDebug", ":app:assembleDebugAndroidTest")
  val apkDir = rootProject.layout.projectDirectory.dir("app/build/outputs/apk")
  systemProperty("terrazzo.appApk", apkDir.file("debug/app-debug.apk").asFile.absolutePath)
  systemProperty(
    "terrazzo.testApk",
    apkDir.file("androidTest/debug/app-debug-androidTest.apk").asFile.absolutePath,
  )
  systemProperty(
    "terrazzo.evidenceDir",
    layout.buildDirectory.dir("container-tests").get().asFile.absolutePath,
  )
  doNotTrackState("Runs against a fresh container and a device whose state Gradle can't see")
  testLogging {
    showStandardStreams = true
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
  }
}
