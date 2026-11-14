#pragma once
// A frame buffer as a JPEG: for a screenshot of the panel (an ESPHome camera, a debug route).

#include <cstddef>
#include <cstdint>

namespace tzwall {

struct Jpeg {
  uint8_t *data = nullptr;   // in PSRAM; free with heap_caps_free
  size_t size = 0;
};

// [fb]: [width] x [height] RGB565 (little-endian), 16-byte aligned, read as it is (a swap
// mid-encode can tear the picture). Returns no data when there's no room for it: a capture never
// takes the last [margin] bytes of PSRAM from the player. A UI frame is ~20-30 KB at quality 70.
Jpeg encode_rgb565_jpeg(const uint16_t *fb, int width, int height, int quality = 70,
                        size_t room = 64 * 1024, size_t margin = 48 * 1024);

}  // namespace tzwall
