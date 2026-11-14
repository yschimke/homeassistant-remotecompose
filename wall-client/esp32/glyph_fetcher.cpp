#include "glyph_fetcher.h"

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "nvs.h"

namespace tzwall {

static const char *const TAG = "wall_glyphs";

GlyphFetcher::GlyphFetcher(GlyphPacks &packs, Config config, std::function<void()> arrived)
    : packs_(packs), config_(std::move(config)), arrived_(std::move(arrived)), http_(config_.base, config_.token) {
  http_.max_body = config_.max_bytes;
}

void GlyphFetcher::load_stored() {
  nvs_handle_t h;
  if (nvs_open(config_.nvs_namespace, NVS_READWRITE, &h) != ESP_OK) return;
  uint32_t firmware = 0, count = 0;
  if (nvs_get_u32(h, "config", &firmware) != ESP_OK || firmware != config_.firmware_packs) {
    nvs_erase_all(h);
    nvs_set_u32(h, "config", config_.firmware_packs);
    nvs_commit(h);
    nvs_close(h);
    return;
  }
  nvs_get_u32(h, "count", &count);
  for (uint32_t i = 0; i < count; i++) {
    char key[12];
    snprintf(key, sizeof key, "p%u", (unsigned) i);
    size_t size = 0;
    if (nvs_get_blob(h, key, nullptr, &size) != ESP_OK || size == 0) continue;
    if (bytes_ + size > config_.max_bytes) break;
    auto *data = static_cast<uint8_t *>(malloc(size));   // kept: the packs point into it
    if (data == nullptr) break;
    if (nvs_get_blob(h, key, data, &size) == ESP_OK && packs_.add(data, size)) {
      bytes_ += size;
    } else {
      free(data);
    }
  }
  stored_ = count;
  nvs_close(h);
  ESP_LOGI(TAG, "%u packs fetched before (%u bytes)", (unsigned) count, (unsigned) bytes_.load());
}

// One pack fetched: kept for the player (never freed: glyphs point into it) and in NVS.
bool GlyphFetcher::keep_(const std::string &body) {
  if (bytes_ + body.size() > config_.max_bytes) return false;
  auto *data = static_cast<uint8_t *>(malloc(body.size()));
  if (data == nullptr) return false;
  memcpy(data, body.data(), body.size());
  if (!packs_.add(data, body.size())) {
    free(data);
    return false;
  }
  bytes_ += body.size();
  nvs_handle_t h;
  if (nvs_open(config_.nvs_namespace, NVS_READWRITE, &h) == ESP_OK) {
    char key[12];
    snprintf(key, sizeof key, "p%u", (unsigned) stored_);
    if (nvs_set_blob(h, key, data, body.size()) == ESP_OK && nvs_set_u32(h, "count", stored_ + 1) == ESP_OK &&
        nvs_commit(h) == ESP_OK)
      stored_++;
    nvs_close(h);
  }
  return true;
}

void GlyphFetcher::start(int core, int priority) {
  xTaskCreatePinnedToCore(task_, "wall_glyphs", 6144, this, priority, nullptr, core);
}

void GlyphFetcher::task_(void *self) {
  auto *f = static_cast<GlyphFetcher *>(self);
  while (true) {
    vTaskDelay(pdMS_TO_TICKS(2000));
    for (auto &want : f->packs_.take_wants()) {
      if (f->bytes_ >= f->config_.max_bytes) break;   // full: the player draws the rest itself
      // A request's characters fit a URL: a few dozen at a time.
      for (size_t at = 0; at < want.code_points.size(); at += 48) {
        GlyphPacks::Want part{want.style, {}};
        part.code_points.assign(want.code_points.begin() + at,
                                want.code_points.begin() + std::min(want.code_points.size(), at + 48));
        const Response r = f->http_.get(f->config_.route + glyph_query(part, f->config_.bits), 10000, "");
        if (r.status == 200) {
          if (f->keep_(r.body) && f->arrived_) f->arrived_();
        } else if (r.status == 404) {
          ESP_LOGW(TAG, "the design server has no glyph route");
        } else if (r.status == 0 || r.status == 408 || r.status == 429 || r.status >= 500) {
          // Not answered, timed out or throttled: asked again a while later.
          ESP_LOGW(TAG, "the design server didn't answer (HTTP %d)", r.status);
          f->packs_.retry(part);
          vTaskDelay(pdMS_TO_TICKS(30000));
        }
      }
    }
  }
}

}  // namespace tzwall
