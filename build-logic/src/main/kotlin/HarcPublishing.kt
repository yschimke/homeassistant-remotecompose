/** Coordinates shared by `harc.publishing` and the `:ha-bom` platform. */
object HarcPublishing {
  /**
   * The Maven group every library publishes under. Publishing needs this namespace (or a parent,
   * such as `ee.schimke`) verified on the Central Portal for the account behind
   * `MAVEN_CENTRAL_USERNAME`.
   */
  const val GROUP = "ee.schimke.ha"

  const val URL = "https://github.com/yschimke/homeassistant-remotecompose"

  /**
   * The project paths that publish, in dependency order. `:ha-bom` reads this to write its
   * constraints, so a module cannot publish and be missing from the BOM; each listed module must
   * also apply `harc.publishing`, which `:ha-bom` checks.
   */
  val LIBRARIES =
    listOf(
      ":ha-model",
      ":ha-client",
      ":rc-mdi-icons",
      ":rc-material3-motion",
      ":rc-components",
      ":rc-player-ui",
      ":rc-converter",
      ":room-ui",
    )

  /** The BOM: published whenever any library is, at the release version, never planned itself. */
  const val BOM = ":ha-bom"

  /**
   * The Gradle property carrying the artifact ids a planned release publishes, as
   * `.github/scripts/maven-publish-plan.sh` prints them (comma separated).
   */
  const val PUBLISH_SET_PROPERTY = "harc.publishSet"

  /**
   * The file, at the repository root, recording the version each coordinate is published at.
   * Written by the release from Maven Central (`maven-publish-plan.sh --write-manifest`), never
   * committed.
   */
  const val PUBLISH_MANIFEST = "publishing-manifest.json"

  /**
   * Parses [PUBLISH_SET_PROPERTY]. Absent and empty must not be collapsed:
   * - absent (`null`): no plan ran, so everything publishes (a manual recovery run);
   * - present but empty: the plan found nothing to publish, so nothing does, the BOM included.
   */
  fun parsePublishSet(property: String?): Set<String>? =
    property?.split(",")?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()

  /**
   * The version [artifactId] carries on this release. Without a plan everything takes
   * [tagVersion]; a module the plan skipped keeps the version it was last published at, so its
   * own POM, the POMs naming it and `:ha-bom` never name a version that was never uploaded. A
   * skipped module with no record is an error, never a guess.
   */
  fun resolveVersion(
    artifactId: String,
    tagVersion: String,
    publishSet: Set<String>?,
    manifestText: String?,
  ): String {
    if (publishSet == null || artifactId in publishSet) return tagVersion
    return manifestText?.let { recordedVersion(artifactId, it) }
      ?: error(
        "'$artifactId' is not in the publish set and has no entry in $PUBLISH_MANIFEST, so " +
          "there is no version it can safely carry. The release writes that file from Maven " +
          "Central (maven-publish-plan.sh --write-manifest) before it publishes."
      )
  }

  /** The version [manifestText] records for [artifactId], or null. A regex: no JSON library here. */
  fun recordedVersion(artifactId: String, manifestText: String): String? =
    Regex("\"${Regex.escape(artifactId)}\"\\s*:\\s*\"([^\"]+)\"")
      .find(manifestText)
      ?.groupValues
      ?.get(1)
}
