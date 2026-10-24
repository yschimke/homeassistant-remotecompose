package ee.schimke.ha.designs

/**
 * The Remote Compose operations a library design may use: those of the app's capture profile,
 * `androidXExperimentalWrap` (AndroidX's experimental platform profile). A capture without Android
 * names them itself; `LibraryCaptureProfileTest` checks they are still the profile's.
 */
object LibraryCaptureProfile {
  val OPERATIONS: Set<Int> =
    (listOf(0, 2, 14, 16, 58, 59, 63, 64, 66, 67, 80, 81, 83, 93) +
        (38..40) +
        (42..49) +
        (51..56) +
        (100..108) +
        (123..131) +
        (133..161) +
        (163..194) +
        (196..250))
      .toSet()
}
