package ee.schimke.ha.client.design

import ee.schimke.ha.design.DesignEntry
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignManifest
import ee.schimke.ha.design.DesignSurface
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class DesignRepositoryTest {

  private val key =
    DesignKey(
      cardType = "tile",
      surface = DesignSurface.MobileApp,
      rcApiLevel = 7,
      shape = "domain=light",
      theme = DesignKey.bakedTheme("TerrazzoHome", dark = false),
    )
  private val template = "fake rc document".encodeToByteArray()
  private val config = DesignServerConfig(baseUrl = "https://designs.test/v1", allowUnsigned = true)

  private var now = 1_000_000L

  /** A static host serving [files]; counts requests per path. */
  private class FakeHost(var files: Map<String, ByteArray>) {
    val hits = HashMap<String, Int>()

    val client =
      HttpClient(
        MockEngine { request ->
          val path = request.url.encodedPath.removePrefix("/v1/")
          hits[path] = (hits[path] ?: 0) + 1
          val body = files[path] ?: return@MockEngine respondError(HttpStatusCode.NotFound)
          respond(body, HttpStatusCode.OK, headersOf("ETag", "\"${body.size}\""))
        }
      )
  }

  private fun published(serial: Long = 1, designs: List<BuiltDesign> = listOf(design())) =
    DesignPublisher.publish(designs, serial = serial, nowEpochMs = now)

  private fun design(bytes: ByteArray = template) = BuiltDesign(key, slots = 1, bytes = bytes)

  private fun repo(host: FakeHost?, store: DesignStore = InMemoryDesignStore()) =
    DesignRepository(config, store, host?.client, clock = { now })

  @Test
  fun sha256MatchesKnownVectors() {
    assertEquals(
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
      Sha256.hex(ByteArray(0)),
    )
    assertEquals(
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
      Sha256.hex("abc".encodeToByteArray()),
    )
    assertEquals(
      "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
      Sha256.hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()),
    )
  }

  @Test
  fun offByDefault_neverFetches_andResolvesNothing() = runTest {
    val host = FakeHost(published().files)
    val repository =
      DesignRepository(DesignServerConfig.Off, InMemoryDesignStore(), host.client) { now }
    assertEquals(RefreshResult.Disabled, repository.refresh(force = true))
    assertNull(repository.resolve(key))
    assertEquals(emptyMap(), host.hits)
  }

  @Test
  fun refreshDownloadsVerifiedBlobs_andResolvesFresh() = runTest {
    val host = FakeHost(published().files)
    val repository = repo(host)
    assertEquals(RefreshResult.Updated, repository.refresh())
    val blob = assertNotNull(repository.resolve(key))
    assertContentEquals(template, blob.bytes)
    assertEquals(DesignSourceKind.Fresh, blob.source)
  }

  @Test
  fun refreshIsSkippedWithinTtl_andStaleCacheStillResolves() = runTest {
    val host = FakeHost(published().files)
    val repository = repo(host)
    repository.refresh()
    assertEquals(RefreshResult.NotDue, repository.refresh())
    assertEquals(1, host.hits["manifest.json"])

    // Past the TTL with the host gone: the stale design still renders.
    now += DesignManifest.DEFAULT_TTL_SECONDS * 1000 + 1
    host.files = emptyMap()
    assertEquals(RefreshResult.Failed, repository.refresh())
    assertEquals(DesignSourceKind.Stale, repository.resolve(key)?.source)
  }

  @Test
  fun tamperedBlobIsNeverStored() = runTest {
    val output = published()
    val files = output.files.toMutableMap()
    files[DesignManifest.blobPath(output.manifest.designs.single().sha256)] =
      "evil".encodeToByteArray()
    val repository = repo(FakeHost(files))
    repository.refresh()
    assertNull(repository.resolve(key))
  }

  @Test
  fun newManifestWithAMissingBlobKeepsThePreviousDesigns() = runTest {
    val host = FakeHost(published(serial = 1).files)
    val store = InMemoryDesignStore()
    repo(host, store).refresh()

    val next = published(serial = 2, designs = listOf(design("newer".encodeToByteArray())))
    host.files = next.files - DesignManifest.blobPath(next.manifest.designs.single().sha256)
    assertEquals(RefreshResult.Failed, repo(host, store).refresh(force = true))
    assertContentEquals(template, repo(host, store).resolve(key)?.bytes)
  }

  @Test
  fun revocationAppliesEvenWhenAnotherBlobIsMissing() = runTest {
    val host = FakeHost(published(serial = 1).files)
    val store = InMemoryDesignStore()
    repo(host, store).refresh()

    // Serial 2 revokes the cached design and adds another whose blob the host doesn't have.
    val otherKey = key.copy(shape = "domain=switch")
    val next =
      published(
        serial = 2,
        designs = listOf(design(), BuiltDesign(otherKey, 1, "other".encodeToByteArray())),
      )
    val revoked =
      next.manifest.copy(
        designs = next.manifest.designs.map { if (it.key == key) it.copy(revoked = true) else it }
      )
    host.files =
      mapOf(
        "manifest.json" to
          kotlinx.serialization.json.Json.encodeToString(DesignManifest.serializer(), revoked)
            .encodeToByteArray()
      )
    assertEquals(RefreshResult.Failed, repo(host, store).refresh(force = true))
    assertNull(repo(host, store).resolve(key))
  }

  @Test
  fun olderSerialIsRejected() = runTest {
    val host = FakeHost(published(serial = 5).files)
    val store = InMemoryDesignStore()
    repo(host, store).refresh()

    host.files = published(serial = 4, designs = listOf(design("older".encodeToByteArray()))).files
    assertEquals(RefreshResult.Rejected, repo(host, store).refresh(force = true))
    assertContentEquals(template, repo(host, store).resolve(key)?.bytes)
  }

  @Test
  fun unsignedManifestIsRejectedUnlessAllowed() = runTest {
    val host = FakeHost(published().files)
    val strict =
      DesignRepository(config.copy(allowUnsigned = false), InMemoryDesignStore(), host.client) {
        now
      }
    assertEquals(RefreshResult.Rejected, strict.refresh())
  }

  @Test
  fun revokedEntryFallsThrough_evenWithInbuiltCopy() = runTest {
    val output = published()
    val entry = output.manifest.designs.single()
    val revoked = output.manifest.copy(serial = 2, designs = listOf(entry.copy(revoked = true)))
    val store = InMemoryDesignStore()
    store.writeManifest(StoredManifest(revoked, fetchedAtEpochMs = now))
    val inbuilt = InbuiltDesigns { k -> if (k == key) entry to template else null }
    val repository = DesignRepository(config, store, null, inbuilt) { now }
    assertNull(repository.resolve(key))
  }

  @Test
  fun inbuiltIsTheLastFallback() = runTest {
    val entry = DesignEntry(key, Sha256.hex(template), template.size, slots = 1)
    val repository =
      DesignRepository(
        DesignServerConfig.Off,
        InMemoryDesignStore(),
        null,
        InbuiltDesigns { k -> if (k == key) entry to template else null },
      ) {
        now
      }
    assertEquals(DesignSourceKind.Inbuilt, repository.resolve(key)?.source)
  }
}
