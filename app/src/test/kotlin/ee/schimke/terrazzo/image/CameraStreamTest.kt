package ee.schimke.terrazzo.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import okio.Buffer
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString

class CameraStreamTest {

  @Test
  fun aCameraPictureStreamsFromTheProxyStream() {
    assertEquals(
      "https://ha.local/api/camera_proxy_stream/camera.yard?token=t",
      cameraStreamUrl("https://ha.local/api/camera_proxy/camera.yard?token=t&terrazzo_frame=d3"),
    )
    assertNull(cameraStreamUrl("https://ha.local/api/image_proxy/image.map?token=t"))
    assertNull(cameraStreamUrl("demo://terrazzo/api/camera_proxy/camera.yard"))
  }

  @Test
  fun framesAreReadByContentLengthOrByTheirEndMarker() {
    val first = "ffd8010203ffd9".decodeHex()
    // A picture whose data holds an end marker: only its Content-Length says where it ends.
    val second = "ffd8ffd90405ffd9".decodeHex()
    val third = "ffd80607ffd9".decodeHex()
    val stream =
      Buffer()
        .writeUtf8("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${first.size}\r\n\r\n")
        .write(first)
        .writeUtf8("\r\n--frame\r\ncontent-length: ${second.size}\r\n\r\n")
        .write(second)
        // A camera's own feed passed through, with no length.
        .writeUtf8("\r\n--myboundary\r\nContent-Type: image/jpeg\r\n\r\n")
        .write(third)
        .writeUtf8("\r\n--myboundary--\r\n")

    val frames = mutableListOf<okio.ByteString>()
    readMjpegFrames(stream) { frames += it.toByteString() }

    assertEquals(listOf(first, second, third), frames)
  }
}
