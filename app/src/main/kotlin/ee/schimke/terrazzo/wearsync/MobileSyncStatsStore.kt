package ee.schimke.terrazzo.wearsync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import ee.schimke.terrazzo.wearsync.proto.SyncStats
import ee.schimke.terrazzo.wearsync.proto.protoBufSerializer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Phone-only Proto DataStore for [SyncStats]. The diagnostics screen
 * reads from this and the [MobileWearSyncManager] writes to it on every
 * data-layer event so users can sanity-check sync chatter. Bounded ring
 * (`recentSendMs`) gives a rolling-window send frequency without
 * persisting unbounded history. Held by [TerrazzoApplication] (not the
 * Metro graph — keeps the wear-sync feature contained to this module).
 */
class MobileSyncStatsStore(private val context: Context) {

    val flow: Flow<SyncStats>
        get() = context.statsStore.data

    suspend fun current(): SyncStats = context.statsStore.data.first()


    suspend fun recordWrite(nowMs: Long) {
        context.statsStore.updateData { stats ->
            stats.copy(
                datastoreWrites = stats.datastoreWrites + 1,
                lastWriteAtMs = nowMs,
            )
        }
    }

    suspend fun recordMessageSent(nowMs: Long) {
        context.statsStore.updateData { stats ->
            val ring = (stats.recentSendMs + nowMs).takeLast(RING_SIZE)
            stats.copy(
                messageSends = stats.messageSends + 1,
                lastMessageAtMs = nowMs,
                recentSendMs = ring,
            )
        }
    }

    suspend fun recordLease(nowMs: Long) {
        context.statsStore.updateData { stats ->
            stats.copy(lastLeaseAtMs = nowMs)
        }
    }

    suspend fun reset() {
        context.statsStore.updateData { SyncStats() }
    }

    companion object {
        const val RING_SIZE: Int = 32
        private val Context.statsStore: DataStore<SyncStats> by dataStore(
            fileName = "terrazzo_sync_stats.pb",
            serializer = protoBufSerializer(SyncStats()),
        )
    }
}

