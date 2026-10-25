package ee.schimke.terrazzo.dashboard

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import ee.schimke.ha.model.CardConfig
import ee.schimke.terrazzo.core.prefs.CardStylePref
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DesignInstallWorkTest {
  private val context
    get() = RuntimeEnvironment.getApplication()

  private val manager
    get() = WorkManager.getInstance(context)

  @Before
  fun setUp() {
    WorkManagerTestInitHelper.initializeTestWorkManager(
      context,
      Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
    )
  }

  @Test
  fun styleInstallIsUniqueWaitsForNetworkAndCanBeRetried() {
    val name = CardStyleInstallWorker.styleTag("lcd")
    CardStyleInstallWorker.enqueue(context, CardStylePref.Lcd)
    val first = manager.getWorkInfosForUniqueWork(name).get().single()
    assertEquals(WorkInfo.State.ENQUEUED, first.state)
    assertTrue(CardStyleInstallWorker.TAG in first.tags)
    CardStyleInstallWorker.enqueue(context, CardStylePref.Lcd)
    assertEquals(first.id, manager.getWorkInfosForUniqueWork(name).get().single().id)
    manager.cancelUniqueWork(name).result.get()
    CardStyleInstallWorker.enqueue(context, CardStylePref.Lcd)
    val retry = manager.getWorkInfosForUniqueWork(name).get().single()
    assertEquals(WorkInfo.State.ENQUEUED, retry.state)
    assertTrue(first.id != retry.id)
  }

  @Test
  fun skinInstallIsTrackedForItsCardAfterThePickerCloses() {
    val card = CardConfig("tile")
    SkinInstallWorker.enqueue(context, card, "user:skin")
    SkinInstallWorker.enqueue(context, card, "user:skin")
    val work = manager.getWorkInfosByTag(SkinInstallWorker.TAG).get().single()
    assertEquals(WorkInfo.State.ENQUEUED, work.state)
    assertTrue(SkinInstallWorker.installTag(card, "user:skin") in work.tags)
    // A newly obtained manager still sees the work; no Activity or composition owns it.
    assertEquals(
      work.id,
      WorkManager.getInstance(context)
        .getWorkInfosForUniqueWork(SkinInstallWorker.installTag(card, "user:skin"))
        .get()
        .single()
        .id,
    )
  }
}
