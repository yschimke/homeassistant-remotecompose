#include "idf_transport.h"

#include <strings.h>

#include "esp_heap_caps.h"
#include "esp_http_client.h"
#include "esp_log.h"
#include "sdkconfig.h"
#ifdef CONFIG_MBEDTLS_CERTIFICATE_BUNDLE
#include "esp_crt_bundle.h"
#endif

namespace tzwall {

static const char *const TAG = "wall_http";

IdfTransport::IdfTransport(std::string base, std::string token)
    : base_(std::move(base)), auth_(token.empty() ? "" : "Bearer " + token) {
  while (!base_.empty() && base_.back() == '/') base_.pop_back();
}

Response IdfTransport::get(const std::string &path, int timeout_ms, const std::string &inm) {
  return request_(HTTP_METHOD_GET, path, nullptr, timeout_ms, inm);
}

Response IdfTransport::post_json(const std::string &path, const std::string &json, int timeout_ms) {
  return request_(HTTP_METHOD_POST, path, &json, timeout_ms, "");
}

static esp_err_t on_event(esp_http_client_event_t *e) {
  if (e->event_id == HTTP_EVENT_ON_HEADER && strcasecmp(e->header_key, "ETag") == 0)
    static_cast<std::string *>(e->user_data)->assign(e->header_value);
  return ESP_OK;
}

bool reserve_room(std::string &s, size_t bytes, size_t margin) {
  if (bytes <= s.capacity()) return true;
  if (heap_caps_get_free_size(MALLOC_CAP_DEFAULT) < bytes + margin) return false;
  void *trial = heap_caps_malloc(bytes + 64, MALLOC_CAP_DEFAULT);
  if (trial == nullptr) return false;
  heap_caps_free(trial);
  s.reserve(bytes);
  return s.capacity() >= bytes;
}

Response IdfTransport::request_(int method, const std::string &path, const std::string *body, int timeout_ms,
                                const std::string &inm) {
  Response r;
  const std::string url = base_ + path;
  esp_http_client_config_t cfg = {};
  cfg.url = url.c_str();
  cfg.method = (esp_http_client_method_t) method;
  cfg.timeout_ms = timeout_ms;
  cfg.event_handler = on_event;
  cfg.user_data = &r.etag;
  cfg.buffer_size = 2048;
#ifdef CONFIG_MBEDTLS_CERTIFICATE_BUNDLE
  if (url.compare(0, 8, "https://") == 0) cfg.crt_bundle_attach = esp_crt_bundle_attach;
#endif
  esp_http_client_handle_t c = esp_http_client_init(&cfg);
  if (c == nullptr) return r;
  if (!auth_.empty()) esp_http_client_set_header(c, "Authorization", auth_.c_str());
  if (!inm.empty()) esp_http_client_set_header(c, "If-None-Match", inm.c_str());
  if (body != nullptr) esp_http_client_set_header(c, "Content-Type", "application/json");
  const int length = body != nullptr ? (int) body->size() : 0;
  if (esp_http_client_open(c, length) == ESP_OK &&
      (length == 0 || esp_http_client_write(c, body->data(), length) == length) &&
      esp_http_client_fetch_headers(c) >= 0) {
    r.status = esp_http_client_get_status_code(c);
    const int64_t content = esp_http_client_get_content_length(c);
    if (content > 0 && !reserve_room(r.body, (size_t) content)) {
      ESP_LOGW(TAG, "%s: no room for %lld bytes", path.c_str(), (long long) content);
      r.status = 0;
    } else {
      char buf[1024];
      int n;
      while ((n = esp_http_client_read(c, buf, sizeof buf)) > 0 && r.body.size() < max_body) {
        if (r.body.size() + n > r.body.capacity() && !reserve_room(r.body, (r.body.size() + n) * 2)) {
          ESP_LOGW(TAG, "%s: no room past %u bytes", path.c_str(), (unsigned) r.body.size());
          r.status = 0;
          r.body.clear();
          break;
        }
        r.body.append(buf, n);
      }
    }
  }
  esp_http_client_cleanup(c);
  return r;
}

}  // namespace tzwall
