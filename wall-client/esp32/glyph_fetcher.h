#pragma once
// Glyph packs a player lacks, fetched from the design server and kept in NVS.
//
// The player draws its text from GlyphPacks (glyph_packs.h): packs built into the firmware, and
// these. When it meets a style or character no pack has, GlyphPacks notes it as wanted; this
// task asks for what's wanted (`GET …/glyphs?…`, a few dozen characters a request), adds each
// pack it gets to GlyphPacks and to NVS, and tells the player so it can lay the text out again.
// Packs kept in NVS come back at boot, unless the firmware's own packs changed since: they were
// fetched for what the old ones lacked.

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <string>

#include "glyph_packs.h"
#include "idf_transport.h"

namespace tzwall {

class GlyphFetcher {
 public:
  struct Config {
    // Where packs come from: Home Assistant's wall route (`/api/terrazzo/wall/v1/glyphs?`, with
    // the wall token), or a design server's own (`/designs/v1/fonts/glyphs?`, no token).
    std::string base, token, route = "/api/terrazzo/wall/v1/glyphs?";
    int bits = 4;                       // coverage depth asked for (emoji always come as colour)
    uint32_t firmware_packs = 0;        // a hash of the built-in packs: NVS is cleared when it moves
    size_t max_bytes = 64 * 1024;       // fetched packs kept, at most
    const char *nvs_namespace = "tz_glyphs";
  };

  // [arrived]: called (on the fetch task) after a pack is added, to lay text out again.
  GlyphFetcher(GlyphPacks &packs, Config config, std::function<void()> arrived);

  // Packs fetched on an earlier boot, into GlyphPacks. Before the player draws.
  void load_stored();
  // Starts the fetch task (FreeRTOS, on [core]).
  void start(int core = 0, int priority = 1);

  size_t bytes() const { return bytes_.load(); }

 private:
  bool keep_(const std::string &body);
  static void task_(void *self);

  GlyphPacks &packs_;
  Config config_;
  std::function<void()> arrived_;
  IdfTransport http_;
  std::atomic<size_t> bytes_{0};
  uint32_t stored_ = 0;
};

}  // namespace tzwall
