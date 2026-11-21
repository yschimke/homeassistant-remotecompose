import com.vanniktech.maven.publish.MavenPublishBaseExtension

// Maven Central publishing for the reusable library modules, after yschimke/livery and
// yschimke/rc-players: the vanniktech plugin, Central Portal upload with automatic release, signing
// only for non-snapshot versions, and one POM block for every artifact.
//
// A module opts in by applying `id("harc.publishing")` and naming itself:
//
//     mavenPublishing { pom { name.set("…"); description.set("…") } }
//
// Its artifact id is the project name (`:ha-model` → `ee.schimke.ha:ha-model`). Kotlin
// Multiplatform modules publish one root coordinate plus per-target variants (`-android`, `-jvm`,
// `-wasm-js`, Apple); consumers name the root and Gradle module metadata picks the variant.
//
// The published set is listed in `docs/architecture/library-publishing.md`; `:ha-bom` constrains
// all of it at one version. Every module in the set must depend only on published coordinates or
// on other modules in the set: a POM naming an unpublished project is a resolution failure in the
// consumer's build, not in ours.
//
// Version: `-Pharc.libraryVersion=<x.y.z>` (the release job passes the tag without its `v`).
// Without it, the next patch after `.github/.release-please-manifest.json` as a `-SNAPSHOT`, so a
// local `publishToMavenLocal` never claims a released version.

pluginManager.apply("com.vanniktech.maven.publish")

group = HarcPublishing.GROUP

version = harcLibraryVersion()

extensions.configure<MavenPublishBaseExtension> {
  publishToMavenCentral(automaticRelease = true)
  if (!version.toString().endsWith("SNAPSHOT")) signAllPublications()
  coordinates(HarcPublishing.GROUP, project.name, version.toString())
  pom {
    url.set(HarcPublishing.URL)
    inceptionYear.set("2026")
    licenses {
      license {
        name.set("The Apache License, Version 2.0")
        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
        distribution.set("repo")
      }
    }
    developers {
      developer {
        id.set("yschimke")
        name.set("Yuri Schimke")
        url.set("https://github.com/yschimke")
      }
    }
    scm {
      url.set(HarcPublishing.URL)
      connection.set("scm:git:https://github.com/yschimke/homeassistant-remotecompose.git")
      developerConnection.set(
        "scm:git:ssh://git@github.com/yschimke/homeassistant-remotecompose.git"
      )
    }
  }
}

fun harcLibraryVersion(): String {
  providers
    .gradleProperty("harc.libraryVersion")
    .orNull
    ?.trim()
    ?.removePrefix("v")
    ?.takeIf { it.isNotEmpty() }
    ?.let {
      return it
    }
  val manifest = rootDir.resolve(".github/.release-please-manifest.json")
  // Builds from a partial checkout have no manifest: the add-on image copies only the modules it
  // builds (addon-server/addon/Dockerfile). They publish nothing, so any snapshot version will do.
  if (!manifest.isFile) return "0.0.0-SNAPSHOT"
  val current =
    Regex(""""\.":\s*"(\d+)\.(\d+)\.(\d+)"""").find(manifest.readText())
      ?: error("No root version in $manifest")
  val (major, minor, patch) = current.destructured
  return "$major.$minor.${patch.toInt() + 1}-SNAPSHOT"
}
