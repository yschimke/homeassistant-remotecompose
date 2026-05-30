@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package ee.schimke.terrazzo.core.logs

import androidx.datastore.core.Serializer
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.serializer

/**
 * Persisted form of the [LogStore] ring. Flat shape with a [kind] tag
 * (rather than polymorphic serialisation over the sealed [LogEntry])
 * because proto-wire polymorphism wants a `SerializersModule`
 * registration we'd otherwise carry just for this one store. Empty
 * strings stand in for nulls — proto3 has no `optional` so a missing
 * field reads as an empty string anyway.
 *
 * Wire numbers are stable; do not reorder. Adding new fields is fine
 * as long as they get a fresh number.
 */
@Serializable
internal data class PersistedLogBuffer(
    @ProtoNumber(1) val entries: List<PersistedLogEntry> = emptyList(),
)

@Serializable
internal data class PersistedLogEntry(
    @ProtoNumber(1) val timestamp: Long = 0L,
    /** 0 = connection · 1 = local action · 2 = data update · 3 = crash. */
    @ProtoNumber(2) val kind: Int = 0,
    /** Ordinal of [LogConnectionStatus]; -1 when [kind] != 0. */
    @ProtoNumber(3) val connectionStatus: Int = -1,
    @ProtoNumber(4) val message: String = "",
    @ProtoNumber(5) val summary: String = "",
    @ProtoNumber(6) val entityId: String = "",
    @ProtoNumber(7) val fromState: String = "",
    @ProtoNumber(8) val toState: String = "",
    @ProtoNumber(9) val stackTrace: String = "",
    @ProtoNumber(10) val threadName: String = "",
    @ProtoNumber(11) val fatal: Boolean = false,
)

internal const val KIND_CONNECTION: Int = 0
internal const val KIND_LOCAL_ACTION: Int = 1
internal const val KIND_DATA_UPDATE: Int = 2
internal const val KIND_CRASH: Int = 3

internal fun LogEntry.toPersisted(): PersistedLogEntry = when (this) {
    is LogEntry.Connection -> PersistedLogEntry(
        timestamp = timestamp,
        kind = KIND_CONNECTION,
        connectionStatus = status.ordinal,
        message = message.orEmpty(),
    )
    is LogEntry.LocalAction -> PersistedLogEntry(
        timestamp = timestamp,
        kind = KIND_LOCAL_ACTION,
        summary = summary,
        entityId = entityId.orEmpty(),
    )
    is LogEntry.DataUpdate -> PersistedLogEntry(
        timestamp = timestamp,
        kind = KIND_DATA_UPDATE,
        entityId = entityId,
        fromState = fromState,
        toState = toState,
    )
    is LogEntry.Crash -> PersistedLogEntry(
        timestamp = timestamp,
        kind = KIND_CRASH,
        summary = summary,
        stackTrace = stackTrace,
        threadName = threadName,
        fatal = fatal,
    )
}

internal fun PersistedLogEntry.toModel(): LogEntry? = when (kind) {
    KIND_CONNECTION -> {
        val status = LogConnectionStatus.entries.getOrNull(connectionStatus) ?: return null
        LogEntry.Connection(
            timestamp = timestamp,
            status = status,
            message = message.takeIf { it.isNotEmpty() },
        )
    }
    KIND_LOCAL_ACTION -> LogEntry.LocalAction(
        timestamp = timestamp,
        summary = summary,
        entityId = entityId.takeIf { it.isNotEmpty() },
    )
    KIND_DATA_UPDATE -> LogEntry.DataUpdate(
        timestamp = timestamp,
        entityId = entityId,
        fromState = fromState,
        toState = toState,
    )
    KIND_CRASH -> LogEntry.Crash(
        timestamp = timestamp,
        threadName = threadName,
        summary = summary,
        stackTrace = stackTrace,
        fatal = fatal,
    )
    else -> null
}

/**
 * Bridges `androidx.datastore.core.Serializer<T>` to
 * `kotlinx-serialization-protobuf`. Defensive read: empty file (first
 * run) or corrupt bytes fall back to [defaultValue] rather than
 * propagating a `CorruptionException` that would crash the activity.
 *
 * Mirrors the same adapter in `app/wearsync/proto/ProtoSerializers.kt`
 * — kept duplicated to avoid pulling :app's wearsync module into
 * :terrazzo-core.
 */
@OptIn(ExperimentalSerializationApi::class)
internal class ProtoBufStoreSerializer<T : Any>(
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
internal inline fun <reified T : Any> protoBufStoreSerializer(default: T): ProtoBufStoreSerializer<T> =
    ProtoBufStoreSerializer(serializer(), default)
