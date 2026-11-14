#pragma once
// WallClient's Transport over ESP-IDF's esp_http_client: one handle a request, so tasks may call
// at once. A body grows only into memory that's there: on a panel whose PSRAM runs out before
// its heap says so, a failed allocation is a failed request, not a write through null.

#include <cstdint>
#include <string>

#include "wall_client.h"

namespace tzwall {

class IdfTransport : public Transport {
 public:
  // [base]: Home Assistant (or a design server), without a trailing path. [token]: the bearer
  // sent with each request; empty sends none.
  IdfTransport(std::string base, std::string token);

  Response get(const std::string &path, int timeout_ms, const std::string &if_none_match) override;
  Response post_json(const std::string &path, const std::string &json, int timeout_ms) override;

  // The most of a body taken (a picture, a glyph pack): a longer one fails the request.
  size_t max_body = SIZE_MAX;

 private:
  Response request_(int method, const std::string &path, const std::string *body, int timeout_ms,
                    const std::string &if_none_match);
  std::string base_, auth_;
};

// [s] made able to hold [bytes] without failing: false (and [s] as it was) when no free block
// could take it, with [margin] left for what the player draws meanwhile. Asked by a trial
// allocation, not heap_caps_get_largest_free_block: that walks the whole heap with interrupts
// off (milliseconds on a panel with 8 MB of PSRAM), and an RGB panel's scan-out slips.
bool reserve_room(std::string &s, size_t bytes, size_t margin = 48 * 1024);

}  // namespace tzwall
