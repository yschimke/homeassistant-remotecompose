import com.ncorti.ktfmt.gradle.FormattingOptionsBean
import com.ncorti.ktfmt.gradle.KtfmtExtension
import com.ncorti.ktfmt.gradle.tasks.KtfmtCheckTask
import com.ncorti.ktfmt.gradle.tasks.KtfmtFormatTask

// Shared ktfmt + dependency-hygiene conventions, applied per-project via
// `plugins { id("harc.base-conventions") }`. This replaces the former root
// `allprojects {}` block with a single per-project convention plugin. It lives
// in an included `build-logic` build (not the build scripts themselves or a
// `buildSrc`/applied-script) so the ktfmt config is written once; see
// `build-logic/build.gradle.kts` for the classloader reasoning that makes
// hosting ktfmt here work.

// Applied imperatively rather than via a `plugins {}` block to skip precompiled
// accessor generation for it; the typed `KtfmtExtension` API is still available
// because the plugin is on this build's classpath.
pluginManager.apply("com.ncorti.ktfmt.gradle")

extensions.configure<KtfmtExtension> { googleStyle() }

// Emit Java 17 bytecode (class-file v61) from every module's Kotlin compile so
// the JDK-17 preview render daemon (Robolectric, preview.coo.ee) can load the
// classes; v65 (JDK 21) bytecode throws UnsupportedClassVersionError there. The
// build toolchain stays on JDK 21 (jvmToolchain via libs.versions.java) — only
// the emitted target drops. Applied centrally here because every subproject
// already applies this convention plugin; tapmoc modules additionally align the
// same target through `tapmoc { java(17) }`.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
  compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

// ktfmt-gradle 0.26 wires its Android source-set tasks through the legacy
// AGP `BaseExtension` / `AndroidSourceSet` API, which AGP 9 removed. The
// failure is swallowed inside a `runCatching`, so `com.android.*` modules end
// up with only their `*.kts` scripts validated and every `src/**/*.kt` file
// silently unchecked (the lifecycle `ktfmtCheck` passes while formatting has
// actually drifted). Until the plugin gains AGP-9 support, register the
// missing Kotlin source-set tasks ourselves and hang them off the lifecycle
// `ktfmtCheck` / `ktfmtFormat` tasks. KMP-Android modules already get their
// common/jvm source sets from the multiplatform path, so only their
// `android*` source sets need patching here.
run {
  val project = project
  val ktfmtExt = extensions.getByType<KtfmtExtension>()

  fun registerAndroidKtfmt(taskSuffix: String, vararg includes: String) {
    if (project.tasks.findByName("ktfmtCheck$taskSuffix") != null) return
    val ktSources =
      project.fileTree("src") {
        includes.forEach { include(it) }
        exclude("**/build/**")
      }
    // Mirror whatever style the extension resolved to (currently googleStyle).
    // FormattingOptionsBean is Serializable, so build it eagerly to stay
    // configuration-cache friendly rather than capturing the extension.
    val options =
      FormattingOptionsBean(
        ktfmtExt.maxWidth.get(),
        ktfmtExt.blockIndent.get(),
        ktfmtExt.continuationIndent.get(),
        ktfmtExt.trailingCommaManagementStrategy.get(),
        ktfmtExt.removeUnusedImports.get(),
        ktfmtExt.debuggingPrintOpsAfterFormatting.get(),
      )
    val classpath = ktfmtExt.useClassloaderIsolation.get()
    val ktfmtClasspathCfg = project.configurations.named("ktfmt")
    val check =
      project.tasks.register<KtfmtCheckTask>("ktfmtCheck$taskSuffix") {
        setSource(ktSources)
        ktfmtClasspath.from(ktfmtClasspathCfg)
        formattingOptionsBean.set(options)
        includeOnly.set("")
        useClassloaderIsolation.set(classpath)
      }
    val format =
      project.tasks.register<KtfmtFormatTask>("ktfmtFormat$taskSuffix") {
        setSource(ktSources)
        ktfmtClasspath.from(ktfmtClasspathCfg)
        formattingOptionsBean.set(options)
        includeOnly.set("")
        useClassloaderIsolation.set(classpath)
      }
    project.tasks.named("ktfmtCheck") { dependsOn(check) }
    project.tasks.named("ktfmtFormat") { dependsOn(format) }
  }

  pluginManager.withPlugin("com.android.application") {
    registerAndroidKtfmt("AndroidSources", "**/*.kt")
  }
  pluginManager.withPlugin("com.android.library") {
    registerAndroidKtfmt("AndroidSources", "**/*.kt")
  }
  pluginManager.withPlugin("com.android.kotlin.multiplatform.library") {
    registerAndroidKtfmt("AndroidSources", "android*/**/*.kt")
  }
}

// Unify every `org.hamcrest:*` coord on the 2.x unified jar. JUnit 4 and
// Espresso still pull `hamcrest-core:1.3` (and the split `-library` /
// `-integration` jars) transitively; their classes overlap with the
// unified `org.hamcrest:hamcrest:2.2` that newer test deps resolve to,
// tripping a duplicate-class check at android-test build time.
//
// Align `androidx.concurrent:*` on 1.2.0. AGP resolves the androidTest
// runtime classpath consistently with the app's main runtime classpath, so
// main's resolved `concurrent-futures` version is injected as a `strictly`
// constraint on `debugAndroidTestRuntimeClasspath`. Main resolves it to
// 1.1.0, but that same test classpath pulls `androidx.test.ext:junit:1.3.0`,
// which needs 1.2.0 — the strict-1.1.0 vs 1.2.0 pair is unresolvable and
// fails the lint-model / android-test tasks. Forcing 1.2.0 (a compatible
// minor bump) makes both classpaths agree.
configurations.all {
  resolutionStrategy.eachDependency {
    if (requested.group == "org.hamcrest") {
      useTarget("org.hamcrest:hamcrest:3.0")
      because("Unify hamcrest split 1.3 jars with the 2.x unified jar")
    }
    if (requested.group == "androidx.concurrent") {
      useVersion("1.2.0")
      because(
        "Align concurrent-futures across the main + androidTest classpaths (consistent resolution)"
      )
    }
  }
}
