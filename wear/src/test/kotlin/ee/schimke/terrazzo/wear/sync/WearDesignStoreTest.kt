package ee.schimke.terrazzo.wear.sync

import ee.schimke.terrazzo.wearsync.proto.WearDesignBlob
import ee.schimke.terrazzo.wearsync.proto.WearSlotDesign
import ee.schimke.terrazzo.wearsync.proto.WearSlotDesigns
import ee.schimke.terrazzo.wearsync.proto.WearSyncPaths
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The slot cards' Wear designs on the watch: verified, and kept only while the index names them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WearDesignStoreTest {
  private val store = WearOfflineStore(RuntimeEnvironment.getApplication())

  private fun blob(vararg bytes: Byte): WearDesignBlob {
    val sha =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    return WearDesignBlob(sha, bytes)
  }

  @Test
  fun aDesignIsKeptOnlyWhenItHashesToItsName() {
    val good = blob(1, 2, 3)
    store.writeDesign(good)
    assertContentEquals(good.rc, store.readDesign(good.sha256))
    val forged = WearDesignBlob(blob(9).sha256, byteArrayOf(4, 5))
    store.writeDesign(forged)
    assertNull(store.readDesign(forged.sha256))
    assertNull(store.readDesign("../slots"))
  }

  @Test
  fun anIndexForgetsTheDesignsItNoLongerNamesAndSignOutForgetsThemAll() {
    val small = blob(1)
    val large = blob(2)
    store.writeDesign(small)
    store.writeDesign(large)
    store.writeSlotDesigns(
      WearSlotDesigns(listOf(WearSlotDesign("pin", "small", small.sha256)), updatedAtMs = 1)
    )
    assertContentEquals(small.rc, store.readDesign(small.sha256))
    assertNull(store.readDesign(large.sha256))
    assertEquals(small.sha256, store.readSlotDesigns()?.designs?.single()?.sha256)

    store.forget(WearSyncPaths.SLOT_DESIGNS)
    assertNull(store.readSlotDesigns())
    assertNull(store.readDesign(small.sha256))
  }
}
