package ee.schimke.terrazzo.wearsync.proto

import androidx.datastore.core.Serializer
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.serializer

/**
 * Bridge `androidx.datastore.core.Serializer<T>` to
 * `kotlinx-serialization-protobuf`. Each Proto DataStore (`/wear/...`)
 * gets one of these.
 *
 * Defensive read: if the file is empty (first read) or corrupt, we
 * return [defaultValue] rather than throwing — DataStore would otherwise
 * propagate as `CorruptionException` and crash the activity.
 */
@OptIn(ExperimentalSerializationApi::class)
class ProtoBufSerializer<T : Any>(
    private val ksz: KSerializer<T>,
    override val defaultValue: T,
) : Serializer<T> {
    override suspend fun readFrom(input: InputStream): T {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) return defaultValue
        return runCatching { ProtoBuf.decodeFromByteArray(ksz, bytes) }
            .getOrDefault(defaultValue)
    }

    override suspend fun writeTo(t: T, output: OutputStream) {
        output.write(ProtoBuf.encodeToByteArray(ksz, t))
    }
}

@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T : Any> protoBufSerializer(default: T): ProtoBufSerializer<T> =
    ProtoBufSerializer(serializer(), default)

/** Encode an arbitrary @Serializable value to proto bytes. */
@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T> encodeProto(value: T): ByteArray =
    ProtoBuf.encodeToByteArray(serializer(), value)

/** Decode proto bytes back into [T]. Returns null on corruption. */
@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T> decodeProto(bytes: ByteArray): T? =
    runCatching { ProtoBuf.decodeFromByteArray(serializer<T>(), bytes) }.getOrNull()
