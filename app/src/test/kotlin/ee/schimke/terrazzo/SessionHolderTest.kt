package ee.schimke.terrazzo

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ee.schimke.terrazzo.core.session.DemoHaSession
import ee.schimke.terrazzo.core.session.HaSession
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain

/**
 * A rotation recreates the Activity but keeps its `ViewModelStore`, so each test re-obtains the
 * holder from the same store the way a recreated `MainActivity.onCreate` does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionHolderTest {
  private val dispatcher = UnconfinedTestDispatcher()
  private val closeScope = TestScope(dispatcher)
  private val store = ViewModelStore()

  @BeforeTest
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @AfterTest
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun holder(): SessionHolder =
    ViewModelProvider.create(store, viewModelFactory { initializer { SessionHolder(closeScope) } })[
        SessionHolder::class]

  @Test
  fun recreatedActivityKeepsTheSessionItSwappedIn() {
    val stub = RecordingSession()
    val live = RecordingSession()
    var initialCalls = 0
    var resumeCalls = 0

    holder()
      .start(
        initial = {
          initialCalls++
          stub
        },
        resume = {
          resumeCalls++
          holder().session = live
        },
      )

    // Rotation: a new Activity re-runs onCreate against the same store.
    val afterRotation = holder()
    afterRotation.start(
      initial = {
        initialCalls++
        RecordingSession()
      },
      resume = { resumeCalls++ },
    )

    assertSame(live, afterRotation.session)
    assertEquals(1, initialCalls, "the cache-only stub is built once, not per Activity")
    assertEquals(1, resumeCalls, "the cold-start resume runs once, not per Activity")
    assertEquals(0, live.closeCount)
  }

  @Test
  fun resumeInFlightIsNotCancelledByRotation() {
    val live = RecordingSession()
    val tokenMinted = CompletableDeferred<Unit>()

    holder()
      .start(
        initial = { null },
        resume = {
          tokenMinted.await()
          holder().session = live
        },
      )

    // The refresh completes after the Activity has been recreated.
    val afterRotation = holder()
    tokenMinted.complete(Unit)

    assertSame(live, afterRotation.session)
  }

  @Test
  fun leavingTheActivityForGoodClosesTheSession() {
    val live = RecordingSession()
    val holder = holder()
    holder.start(initial = { live }, resume = {})

    store.clear()
    closeScope.runCurrent()

    assertEquals(1, live.closeCount)
    assertNull(holder.session)
  }

  private class RecordingSession(private val delegate: HaSession = DemoHaSession()) :
    HaSession by delegate {
    var closeCount = 0

    override suspend fun close() {
      closeCount++
      delegate.close()
    }
  }
}
