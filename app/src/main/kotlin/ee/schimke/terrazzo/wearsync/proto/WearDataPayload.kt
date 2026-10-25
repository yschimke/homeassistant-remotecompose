package ee.schimke.terrazzo.wearsync.proto

import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Small settings stay compatible with older peers; full designs use Data Layer asset transfer. */
internal fun DataMap.putProtoPayload(bytes: ByteArray) {
  if (bytes.size <= INLINE_PROTO_LIMIT) putByteArray("proto", bytes)
  else putAsset("proto_asset", Asset.createFromBytes(bytes))
}

internal const val INLINE_PROTO_LIMIT = 90_000

/** Extracted before releasing a DataEvent buffer; asset I/O can finish asynchronously. */
internal class WearDataPayload(val inlineBytes: ByteArray?, private val asset: Asset?) {
  suspend fun read(client: DataClient): ByteArray? {
    inlineBytes?.let {
      return it
    }
    val reference = asset ?: return null
    return try {
      val response =
        suspendCancellableCoroutine<DataClient.GetFdForAssetResponse> { continuation ->
          client
            .getFdForAsset(reference)
            .addOnSuccessListener { value ->
              continuation.resume(value) { _, response, _ -> response.release() }
            }
            .addOnFailureListener { continuation.resumeWith(Result.failure(it)) }
        }
      try {
        withContext(Dispatchers.IO) { response.inputStream?.use { it.readBytes() } }
      } finally {
        response.release()
      }
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      null
    }
  }
}

internal fun DataMap.protoPayload() =
  WearDataPayload(getByteArray("proto"), getAsset("proto_asset"))
