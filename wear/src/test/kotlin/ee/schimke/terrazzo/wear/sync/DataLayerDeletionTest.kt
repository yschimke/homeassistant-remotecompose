package ee.schimke.terrazzo.wear.sync

import ee.schimke.terrazzo.wearsync.proto.DashboardData
import ee.schimke.terrazzo.wearsync.proto.WearSyncPaths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the watch forgets when the phone deletes a Data Layer item. */
class DataLayerDeletionTest {

  @Test
  fun eachMirroredPathIsRecognised() {
    assertEquals(ForgottenItem.Pinned, forgottenItemFor(WearSyncPaths.PINNED))
    assertEquals(ForgottenItem.Sections, forgottenItemFor(WearSyncPaths.SECTIONS))
    assertEquals(ForgottenItem.Values, forgottenItemFor(WearSyncPaths.VALUES))
    assertEquals(ForgottenItem.Settings, forgottenItemFor(WearSyncPaths.SETTINGS))
    assertEquals(
      ForgottenItem.Picture("camera.door"),
      forgottenItemFor(WearSyncPaths.picturePath("camera.door")),
    )
    assertEquals(ForgottenItem.SlotDesigns, forgottenItemFor(WearSyncPaths.SLOT_DESIGNS))
    assertEquals(ForgottenItem.Design("ab12"), forgottenItemFor(WearSyncPaths.designPath("ab12")))
    assertEquals(
      ForgottenItem.Dashboard("energy"),
      forgottenItemFor(WearSyncPaths.dashboardPath("energy")),
    )
  }

  @Test
  fun unknownPathsAndBarePrefixesAreIgnored() {
    assertNull(forgottenItemFor("/somebody/else"))
    // An older phone's widget slot assignments: the watch no longer mirrors them.
    assertNull(forgottenItemFor(WearSyncPaths.SLOTS))
    assertNull(forgottenItemFor(WearSyncPaths.DASHBOARD_PREFIX))
    assertNull(forgottenItemFor(WearSyncPaths.PICTURE_PREFIX))
    assertNull(forgottenItemFor(WearSyncPaths.DESIGN_PREFIX))
  }

  @Test
  fun aDeletedDashboardMatchesItsOwnDataOnly() {
    val default = forgottenItemFor(WearSyncPaths.dashboardPath(null)) as ForgottenItem.Dashboard
    assertTrue(default.matches(DashboardData(urlPath = "")))
    assertFalse(default.matches(DashboardData(urlPath = "energy")))
    val nested = forgottenItemFor(WearSyncPaths.dashboardPath("a/b")) as ForgottenItem.Dashboard
    assertTrue(nested.matches(DashboardData(urlPath = "a/b")))
  }

  @Test
  fun signOutForgetsValuesAndPicturesWithThePins() {
    // The listener service isn't woken for values or pictures, only for pins and the like.
    val forgotten = forgottenOnDeletion(listOf(WearSyncPaths.PINNED, WearSyncPaths.SLOTS))
    assertTrue(signsOut(listOf(WearSyncPaths.SECTIONS)))
    // An older phone deletes `/wear/slots` only with the pins: alone, it isn't a sign-out.
    assertFalse(signsOut(listOf(WearSyncPaths.SLOTS)))
    assertTrue(WearSyncPaths.VALUES in forgotten)
    assertTrue(WearSyncPaths.PICTURE_PREFIX in forgotten)
    assertTrue(WearSyncPaths.SLOT_DESIGNS in forgotten)
    assertTrue(WearSyncPaths.PINNED in forgotten)
  }

  @Test
  fun newDesignsOrCardsRedrawTheWidgetsAndOtherChangesDoNot() {
    assertTrue(redrawsOnChange(listOf(WearSyncPaths.SLOT_DESIGNS)))
    // A placed widget may show any pinned card or pinned section's card.
    assertTrue(redrawsOnChange(listOf(WearSyncPaths.PINNED)))
    assertTrue(redrawsOnChange(listOf(WearSyncPaths.SECTIONS)))
    assertFalse(redrawsOnChange(listOf(WearSyncPaths.SLOTS, WearSyncPaths.dashboardPath("x"))))
  }

  @Test
  fun aRemovedDashboardForgetsThatDashboardOnly() {
    val path = WearSyncPaths.dashboardPath("old-board")
    assertEquals(listOf(path), forgottenOnDeletion(listOf(path)))
  }
}
