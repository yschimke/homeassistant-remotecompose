package ee.schimke.terrazzo.image

import android.graphics.Bitmap
import android.graphics.Color
import ee.schimke.terrazzo.core.network.LanConnectionPolicy
import ee.schimke.terrazzo.core.network.RemoteUrlStore
import java.io.ByteArrayOutputStream
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real local HTTP images exercise the same loader used by launcher baking and direct capture. */
@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class WidgetImageLoadingTest {
  @Test
  fun boundedFreshImagesKeepSameHostAuthenticationAndSignedQueriesWithoutLoggingSecrets() =
    runBlocking {
      val app =
        androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
      // A second disk-cache owner must not share the process-wide app cache directory.
      val cache = java.io.File(app.cacheDir, "widget-http-test").apply { mkdirs() }
      val context =
        object : android.content.ContextWrapper(app) {
          override fun getCacheDir() = cache

          override fun getApplicationContext(): android.content.Context = this
        }
      val requests = LinkedBlockingQueue<Pair<String, String?>>()
      fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(1200, 600, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return ByteArrayOutputStream()
          .also { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
          .toByteArray()
          .also { bitmap.recycle() }
      }
      val image = java.util.concurrent.atomic.AtomicReference(png(Color.MAGENTA))
      val server = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
      val serving = Thread {
        try {
          while (!server.isClosed) server.accept().use { socket ->
            val input = socket.getInputStream().bufferedReader()
            val path = input.readLine().split(' ')[1]
            var bearer: String? = null
            while (true) {
              val line = input.readLine() ?: break
              if (line.isEmpty()) break
              if (line.startsWith("Authorization:", ignoreCase = true))
                bearer = line.substringAfter(':').trim()
            }
            requests.add(path to bearer)
            val bytes = image.get()
            socket.getOutputStream().apply {
              write(
                "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                  .toByteArray()
              )
              write(bytes)
              flush()
            }
          }
        } catch (_: java.net.SocketException) {
          check(server.isClosed)
        }
      }
        .apply { start() }
      val stack = HaImageStack(context, LanConnectionPolicy(context), RemoteUrlStore(context))
      val base = "http://localhost:${server.localPort}"
      val signed = "/picture?token=private-picture-token"
      stack.setAuth(base, "private-bearer-token")
      try {
        val first = checkNotNull(stack.resolve(base + signed, fresh = true, maxDimensionPx = 512))
        assertTrue(
          "Decode must be bounded before PNG encoding",
          maxOf(first.width, first.height) <= 512,
        )
        assertEquals(Color.MAGENTA, first.getPixel(first.width / 2, first.height / 2))
        assertEquals(signed to "Bearer private-bearer-token", requests.take())
        image.set(png(Color.CYAN))
        val next = checkNotNull(stack.resolve(base + signed, fresh = true, maxDimensionPx = 512))
        assertEquals(Color.CYAN, next.getPixel(next.width / 2, next.height / 2))
        assertEquals(signed to "Bearer private-bearer-token", requests.take())
        checkNotNull(
          stack.resolve(
            "http://127.0.0.1:${server.localPort}$signed",
            fresh = true,
            maxDimensionPx = 512,
          )
        )
        assertEquals(
          "External hosts must not receive the HA bearer",
          signed to null,
          requests.take(),
        )
        val log =
          androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand("logcat -d -s HaImageStack")
        val messages =
          android.os.ParcelFileDescriptor.AutoCloseInputStream(log).bufferedReader().use {
            it.readText()
          }
        assertFalse(messages.contains("private-picture-token"))
        assertFalse(messages.contains("private-bearer-token"))
      } finally {
        stack.imageLoader.shutdown()
        server.close()
        serving.join(5000)
        cache.deleteRecursively()
      }
    }
}
