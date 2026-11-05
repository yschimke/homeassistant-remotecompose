package ee.schimke.terrazzo.shared

/**
 * Days an update must have been available on Play before everyone is prompted for it. Internal
 * releases reach closed and production tracks later, so waiting this long lets a release settle
 * with testers first.
 */
const val PLAY_UPDATE_SETTLE_DAYS = 7

/**
 * Whether to start Play's update flow. Play cannot tell the app which track it was installed from
 * (internal, closed, production) and the same bundle is promoted between them, so testers opt in on
 * the device ([tester]) to be prompted as soon as an update appears. Everyone else is prompted once
 * Play reports the update has been available for [PLAY_UPDATE_SETTLE_DAYS]. An update Play already
 * started always resumes.
 *
 * @param stalenessDays Play's days since this update became available to this user, or null when it
 *   does not know yet.
 */
fun shouldStartPlayUpdate(
  available: Boolean,
  inProgress: Boolean,
  tester: Boolean,
  stalenessDays: Int?,
): Boolean =
  inProgress || (available && (tester || (stalenessDays ?: 0) >= PLAY_UPDATE_SETTLE_DAYS))
