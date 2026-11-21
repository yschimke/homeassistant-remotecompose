# Waveshare ESP32-S3-Touch-LCD-7

A `board` for kobento-mcu's player, kept with the ESPHome component (`kobento: board:
waveshare_s3_lcd7`) until it moves into kobento-mcu's `esp32/boards/`.

- ESP32-S3R8: 8 MB octal PSRAM, 8 MB flash, USB Serial/JTAG.
- 7" 800x480 RGB565 parallel panel at 16 MHz, two frame buffers in PSRAM, flipped on vsync.
- GT911 touch and a CH422G I/O expander on I2C (SDA 8, SCL 9): the expander holds the
  backlight (on/off only), the panel and touch resets.
- No buttons (GPIO0 is a panel data line), no IMU, RTC or PMU.

Pins and timings are ESPHome's `mipi_rgb` model `ESP32-S3-TOUCH-LCD-7-800X480`. Not yet run
on hardware.
