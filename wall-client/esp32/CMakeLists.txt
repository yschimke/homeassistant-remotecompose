# The wall client and its ESP32 parts as one ESP-IDF component. Needs ArduinoJson 7 (ESPHome's
# `json` component, or the bblanchon/arduinojson component) and espressif/esp_new_jpeg.
idf_component_register(
  SRCS "../src/wall_client.cpp" "idf_transport.cpp" "pictures.cpp" "glyph_fetcher.cpp" "screen_jpeg.cpp"
       "sockets.cpp"
  INCLUDE_DIRS "../src" "."
  REQUIRES esp_http_client esp-tls esp_timer lwip nvs_flash
)
