package ee.schimke.terrazzo.wear.sync

import ee.schimke.ha.client.HaAuthInvalidException
import ee.schimke.ha.model.EntityState
import ee.schimke.terrazzo.wearsync.proto.EntityValue
import ee.schimke.terrazzo.wearsync.proto.LiveValues
import ee.schimke.terrazzo.wearsync.proto.WearAccess
import ee.schimke.terrazzo.wearsync.proto.WearSettings
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The watch keeps only a token for the instance it is signed in to, asks the phone for one when it
 * has none, and reads its cards' values itself only when the phone's are stale: `https` first, and
 * a refused token is dropped and asked for again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WearDirectAccessTest {
  /** Robolectric has no AndroidKeyStore: a reversible stand-in that still isn't the plaintext. */
  private object FakeCipher : AccessCipher {
    override fun encrypt(plaintext: ByteArray) = plaintext.reversedArray() + 7

    override fun decrypt(sealed: ByteArray): ByteArray {
      require(sealed.lastOrNull() == 7.toByte()) { "not sealed by us" }
      return sealed.copyOf(sealed.size - 1).reversedArray()
    }
  }

  private val context = RuntimeEnvironment.getApplication()
  private val store = WearAccessStore(context, FakeCipher)
  private val offline = WearOfflineStore(context)
  private var clock = 10_000_000L
  private var requests = 0
  private val fetched = mutableListOf<Pair<String, Set<String>>>()
  private var answer: (String) -> Map<String, EntityState> = { emptyMap() }

  private val direct =
    WearDirectAccess(
      context,
      store = store,
      offline = offline,
      fetch = { baseUrl, token, ids ->
        assertEquals("llat", token)
        fetched += baseUrl to ids
        answer(baseUrl)
      },
      sendRequest = { requests++ },
      now = { clock },
    )

  private val signedIn = WearSettings(baseUrl = "http://homeassistant.local:8123")
  private val access =
    WearAccess(
      baseUrl = "http://homeassistant.local:8123",
      externalUrl = "https://home.example.org",
      token = "llat",
      issuedAtMs = 1,
    )
  private val card = """{"type":"tile","entity":"sensor.outside"}"""

  private fun state(id: String, value: String) =
    EntityState(id, value, JsonObject(mapOf("unit_of_measurement" to JsonPrimitive("°C"))))

  @BeforeTest
  fun reset() {
    WearDirectAccess.resetThrottles()
    store.clear()
  }

  @AfterTest fun clear() = store.clear()

  @Test
  fun theTokenIsKeptSealedAndOnlyForTheInstanceTheWatchIsSignedInTo() {
    assertFalse(direct.accept(access, null), "not signed in")
    assertFalse(direct.accept(access, signedIn.copy(demoMode = true)), "demo")
    assertFalse(direct.accept(access, WearSettings(baseUrl = "https://other.example")))
    assertNull(store.read())
    assertTrue(direct.accept(access, signedIn.copy(baseUrl = "http://homeassistant.local:8123/")))
    assertEquals("llat", store.read()?.token)
    val file = java.io.File(context.noBackupFilesDir, "terrazzo/wear-access.bin")
    assertFalse(
      "llat" in String(file.readBytes(), Charsets.ISO_8859_1),
      "never stored in the clear",
    )
    direct.forget()
    assertNull(store.read())
  }

  @Test
  fun aSealedTokenThatCantBeOpenedReadsAsNone() {
    store.write(access)
    assertNull(
      WearAccessStore(
          context,
          object : AccessCipher by FakeCipher {
            override fun decrypt(sealed: ByteArray): ByteArray =
              throw javax.crypto.AEADBadTagException()
          },
        )
        .read()
    )
  }

  @Test
  fun theWatchAsksForATokenOnlyWhenItHasNoneAndNotInALoop() = runTest {
    direct.requestIfMissing(signedIn)
    direct.requestIfMissing(signedIn)
    assertEquals(1, requests)
    clock += WearDirectAccess.REQUEST_EVERY_MS
    direct.requestIfMissing(signedIn)
    assertEquals(2, requests)
    store.write(access)
    clock += WearDirectAccess.REQUEST_EVERY_MS
    direct.requestIfMissing(signedIn)
    assertEquals(2, requests, "it has one")
    direct.requestIfMissing(WearSettings(baseUrl = "https://other.example"))
    assertNull(store.read(), "a token for another instance is forgotten")
    store.write(access)
    direct.requestIfMissing(signedIn.copy(demoMode = true))
    assertNull(store.read(), "demo mode keeps no token")
  }

  @Test
  fun staleValuesAreReadOverHttpsFirstAndKeptWithThePhones() = runTest {
    store.write(access)
    offline.writeValues(
      LiveValues(
        values = mapOf("light.kitchen" to EntityValue(state = "on")),
        capturedAtMs = clock - WearDirectAccess.STALE_AFTER_MS,
      )
    )
    answer = { mapOf("sensor.outside" to state("sensor.outside", "12.5")) }
    val values = assertNotNull(direct.refresh(listOf(card), signedIn))
    assertEquals(listOf("https://home.example.org" to setOf("sensor.outside")), fetched)
    assertEquals("12.5", values.values.getValue("sensor.outside").state)
    assertEquals("°C", values.values.getValue("sensor.outside").unit)
    assertEquals("on", values.values.getValue("light.kitchen").state, "the phone's are kept")
    assertEquals(clock, values.capturedAtMs)
    assertEquals(values, offline.readValues())

    // Fresh now, and read at most once a minute.
    assertNull(direct.refresh(listOf(card, """{"entity":"sensor.new"}"""), signedIn))
    assertEquals(1, fetched.size)
  }

  @Test
  fun anEntityHomeAssistantNoLongerHasIsDroppedNotKeptFresh() = runTest {
    store.write(access)
    offline.writeValues(
      LiveValues(
        values = mapOf("sensor.outside" to EntityValue("9"), "light.kitchen" to EntityValue("on")),
        capturedAtMs = clock - WearDirectAccess.STALE_AFTER_MS,
      )
    )
    // Home Assistant answers 404 for sensor.outside: HaEntityStates leaves it out.
    answer = { emptyMap() }
    val values = assertNotNull(direct.refresh(listOf(card), signedIn))
    assertEquals(setOf("light.kitchen"), values.values.keys)
  }

  @Test
  fun demoModeForgetsTheTokenAndReadsNothing() = runTest {
    store.write(access)
    assertNull(direct.refresh(listOf(card), signedIn.copy(demoMode = true)))
    assertNull(store.read())
    assertTrue(fetched.isEmpty())
  }

  @Test
  fun freshPhoneValuesAreNotReadAgain() = runTest {
    store.write(access)
    offline.writeValues(
      LiveValues(values = mapOf("sensor.outside" to EntityValue("3")), capturedAtMs = clock - 1000)
    )
    assertNull(direct.refresh(listOf(card), signedIn))
    assertTrue(fetched.isEmpty())
  }

  @Test
  fun theLanAddressIsTriedWhenTheExternalOneFails() = runTest {
    store.write(access)
    answer = { url ->
      if (url.startsWith("https")) throw IOException("unreachable")
      mapOf("sensor.outside" to state("sensor.outside", "4"))
    }
    assertEquals("4", direct.refresh(listOf(card), signedIn)?.values?.get("sensor.outside")?.state)
    assertEquals(
      listOf("https://home.example.org", "http://homeassistant.local:8123"),
      fetched.map { it.first },
    )
  }

  @Test
  fun aRefusedTokenIsDroppedAndAskedForAgain() = runTest {
    store.write(access)
    answer = { throw HaAuthInvalidException("401") }
    assertNull(direct.refresh(listOf(card), signedIn))
    assertNull(store.read())
    assertEquals(1, requests)
    assertEquals(1, fetched.size, "the other address isn't tried with a refused token")
  }

  @Test
  fun withoutATokenTheWatchAsksInsteadOfReading() = runTest {
    assertNull(direct.refresh(listOf(card), signedIn))
    assertEquals(1, requests)
    assertTrue(fetched.isEmpty())
    assertNull(direct.refresh(listOf(card), null), "signed out")
  }

  @Test
  fun valuesAreStaleWhenOldOrMissingButNotReadMoreThanOnceAMinute() {
    val now = 1_000_000L
    val ids = setOf("sensor.a")
    val fresh = LiveValues(mapOf("sensor.a" to EntityValue("1")), capturedAtMs = now - 1)
    assertFalse(needsDirectValues(fresh, ids, now, null))
    assertTrue(needsDirectValues(null, ids, now, null))
    // Fresh phone values lacking an entity: Home Assistant lacks it too, or it only looks like one.
    assertFalse(needsDirectValues(fresh, ids + "sensor.b", now, null))
    assertTrue(
      needsDirectValues(
        fresh.copy(capturedAtMs = now - WearDirectAccess.STALE_AFTER_MS),
        ids,
        now,
        null,
      )
    )
    assertFalse(needsDirectValues(null, ids, now, lastDirectAtMs = now - 1))
    assertFalse(needsDirectValues(null, emptySet(), now, null), "no cards, nothing to read")
  }

  @Test
  fun httpsAddressesComeFirstAndOthersAreDropped() {
    assertEquals(
      listOf("https://home.example.org", "http://homeassistant.local:8123"),
      candidateUrls(access.copyWith(externalUrl = "https://home.example.org/")),
    )
    assertEquals(
      listOf("http://homeassistant.local:8123"),
      candidateUrls(access.copyWith(externalUrl = "")),
    )
    assertEquals(
      listOf("http://homeassistant.local:8123"),
      candidateUrls(access.copyWith(externalUrl = "ftp://home.example.org")),
    )
  }

  @Test
  fun theCardsEntitiesAreTheOnesTheyNameAndTheirPrintersKnownOnes() {
    val bambu = """{"type":"custom:ha-bambulab-print_status-card","entity_prefix":"p2s_sn2"}"""
    val known =
      listOf(
        "sensor.p2s_sn2_print_status",
        "sensor.p2s_sn2_print_progress",
        "binary_sensor.p2s_sn2_online",
      )
    assertEquals(
      setOf("sensor.outside") + known,
      watchEntityIds(listOf(card, bambu), known + "sensor.unrelated"),
    )
  }

  @Test
  fun aSignOutDeletionForgetsTheToken() {
    assertTrue(signsOut(listOf("/wear/pinned")))
    assertFalse(signsOut(listOf("/wear/dashboard/home")))
  }

  private fun WearAccess.copyWith(externalUrl: String) =
    WearAccess(baseUrl, externalUrl, token, issuedAtMs)
}
