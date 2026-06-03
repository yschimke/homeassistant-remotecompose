plugins { `kotlin-dsl` }

dependencies {
  // ktfmt and the Kotlin Gradle plugin must share this convention plugin's
  // classloader: ktfmt's `KtfmtPlugin` decorates against `KotlinSourceSet`, and
  // it only detects a consuming module's Kotlin source sets when the Kotlin
  // plugin classes match. Because this is an *included build* (not `buildSrc`)
  // and both come from the same catalog versions the modules use, Gradle reuses
  // one plugin classloader — so a module's `alias(libs.plugins.kotlin.*)` lines
  // up with what ktfmt sees here, with no "already on the classpath" clash that
  // a `buildSrc` dependency would cause.
  implementation(
    "com.ncorti.ktfmt.gradle:com.ncorti.ktfmt.gradle.gradle.plugin:${libs.versions.ktfmt.gradle.get()}"
  )
  implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
}
