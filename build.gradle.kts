import com.ncorti.ktfmt.gradle.KtfmtExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ktfmt) apply false
}

allprojects {
    apply(plugin = "com.ncorti.ktfmt.gradle")
    extensions.configure<KtfmtExtension> {
        googleStyle()
    }
}

// Wires .githooks/ as the repository's hooks directory, so the pre-commit
// ktfmt check runs locally. One-time bootstrap: `./gradlew installGitHooks`.
tasks.register<Exec>("installGitHooks") {
    group = "git hooks"
    description = "Configure git to use the .githooks directory in this repo."
    workingDir = rootDir
    commandLine("git", "config", "core.hooksPath", ".githooks")
}
