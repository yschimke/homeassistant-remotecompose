package ee.schimke.terrazzo.core

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The app's cold start, reduced: `TerrazzoApplication.onCreate` starts collecting preferences on
 * the main dispatcher (`ProcessLifecycleOwner.lifecycleScope`), then `MainActivity.onCreate` reads
 * the same store with `runBlocking` on the main thread.
 *
 * DataStore 1.3.0 runs a read on the caller's dispatcher, holding its one-time initialisation lock
 * across the hop to IO and back. The collector's read takes that lock and queues its resume on the
 * main thread; `runBlocking` then waits for the lock on the main thread, so the resume never runs.
 * The activity never draws. Every phone benchmark run from DataStore 1.3.0 (#1037) to the pin
 * (#1054) failed on it. This keeps the pattern under test, so the next DataStore upgrade can't
 * bring it back unnoticed.
 */
class DataStoreMainThreadReadTest {
  @get:Rule val folder = TemporaryFolder()

  @Test
  fun aBlockingMainThreadReadSurvivesAMainDispatchedCollector() {
    val main = Executors.newSingleThreadExecutor()
    val mainDispatcher = main.asCoroutineDispatcher()
    val store =
      PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) {
        File(folder.root, "prefs.preferences_pb")
      }
    try {
      val outcome =
        main
          .submit<String> {
            // TerrazzoApplication.onCreate starts several collectors; Main.immediate runs each
            // until it suspends. The first suspends to read the coordinator's version on IO, which
            // creates the coordinator; a later one then goes straight to the one-time
            // initialisation, takes its lock and suspends to read the file on IO, holding it.
            repeat(2) {
              CoroutineScope(mainDispatcher).launch(start = CoroutineStart.UNDISPATCHED) {
                store.data.first()
              }
              Thread.sleep(100)
            }
            // Their reads finish on IO meanwhile, and queue their resumes behind this task.
            Thread.sleep(500)
            // MainActivity.onCreate: a blocking read on the same thread.
            runBlocking {
              withTimeoutOrNull(5_000) {
                store.data.first()[KEY]
                "read"
              } ?: "deadlocked"
            }
          }
          .get(30, TimeUnit.SECONDS)
      assertEquals("read", outcome)
    } finally {
      main.shutdownNow()
    }
  }

  private companion object {
    val KEY = stringPreferencesKey("last_viewed_dashboard")
  }
}
