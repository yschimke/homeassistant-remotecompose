#include "screen_jpeg.h"

#include "esp_heap_caps.h"
#include "esp_jpeg_enc.h"
#include "esp_log.h"

namespace tzwall {

static const char *const TAG = "wall_screen";

Jpeg encode_rgb565_jpeg(const uint16_t *fb, int width, int height, int quality, size_t room, size_t margin) {
  Jpeg out;
  if (fb == nullptr || (reinterpret_cast<uintptr_t>(fb) & 15) != 0) {
    ESP_LOGW(TAG, "the frame buffer isn't there, or isn't 16-byte aligned");
    return out;
  }
  // A trial allocation, not a heap walk: walking stalls an RGB panel's scan-out.
  void *trial = heap_caps_malloc(room + margin, MALLOC_CAP_SPIRAM);
  if (trial == nullptr) return out;
  heap_caps_free(trial);
  auto *buf = static_cast<uint8_t *>(heap_caps_malloc(room, MALLOC_CAP_SPIRAM));
  if (buf == nullptr) return out;
  jpeg_enc_config_t config = DEFAULT_JPEG_ENC_CONFIG();
  config.width = width;
  config.height = height;
  config.src_type = JPEG_PIXEL_FORMAT_RGB565_LE;
  config.subsampling = JPEG_SUBSAMPLE_420;
  config.quality = quality;
  jpeg_enc_handle_t encoder = nullptr;
  int size = 0;
  jpeg_error_t err = jpeg_enc_open(&config, &encoder);
  if (err == JPEG_ERR_OK) {
    err = jpeg_enc_process(encoder, reinterpret_cast<const uint8_t *>(fb), width * height * 2, buf, (int) room,
                           &size);
    jpeg_enc_close(encoder);
  }
  if (err != JPEG_ERR_OK || size <= 0) {
    heap_caps_free(buf);
    ESP_LOGW(TAG, "the JPEG encoder failed (%d)", (int) err);
    return out;
  }
  auto *fit = static_cast<uint8_t *>(heap_caps_realloc(buf, size, MALLOC_CAP_SPIRAM));
  out.data = fit != nullptr ? fit : buf;
  out.size = (size_t) size;
  return out;
}

}  // namespace tzwall
