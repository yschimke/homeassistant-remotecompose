package ee.schimke.terrazzo.wearsync

import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataMap
import ee.schimke.terrazzo.wearsync.proto.INLINE_PROTO_LIMIT
import ee.schimke.terrazzo.wearsync.proto.WearDesignBlob
import ee.schimke.terrazzo.wearsync.proto.encodeProto
import ee.schimke.terrazzo.wearsync.proto.putProtoPayload
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WearDataPayloadTest {
  @Test
  fun smallSettingsStayInlineAndLargeSelectedDesignsUseAssetTransfer() {
    val small = ByteArray(INLINE_PROTO_LIMIT) { 1 }
    val inline = DataMap().apply { putProtoPayload(small) }
    assertContentEquals(small, inline.getByteArray("proto"))
    assertNull(inline.getAsset("proto_asset"))
    val large = encodeProto(WearDesignBlob("hash", ByteArray(INLINE_PROTO_LIMIT + 1) { 2 }))
    val transferred = DataMap().apply { putProtoPayload(large) }
    assertNull(transferred.getByteArray("proto"))
    assertNotNull(transferred.getAsset("proto_asset"))
    assertEquals(Asset.createFromBytes(large), transferred.getAsset("proto_asset"))
  }
}
