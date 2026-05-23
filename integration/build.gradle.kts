plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(libs.versions.java.get().toInt()) }

dependencies {
  testImplementation(libs.kotlin.test)
  testImplementation(platform(libs.junit.jupiter.bom))
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
  useJUnitPlatform()

  // Rendered PNGs come from the compose-preview CLI's `composePreviewRender`
  // task, which the CLI's auto-inject init script materialises on the
  // `:previews` module — run `compose-preview show` locally (or let the
  // preview-baselines / preview-comment GitHub Actions do it in CI) before
  // `./gradlew :integration:test`. The task isn't visible to a plain
  // `./gradlew` invocation, so we wire `dependsOn` only when it exists;
  // missing PNGs are tolerated by the dynamic tests' assumeTrue skips.
  rootProject.findProject(":previews")?.tasks?.findByName("composePreviewRender")?.let {
    dependsOn(it)
  }

  systemProperty(
    "ha.rendered.dir",
    rootProject.file("previews/build/compose-previews/renders").absolutePath,
  )
  systemProperty("ha.references.dir", rootProject.file("references").absolutePath)
}
