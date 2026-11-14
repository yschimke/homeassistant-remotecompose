# The Waveshare ESP32-S3-Touch-LCD-7's `board` component: the shared board interface
# (board/board.h) over esp_lcd's RGB panel driver, the GT911 touch controller and the CH422G
# I/O expander on I2C.
idf_component_register(SRCS src/board.cpp
                       INCLUDE_DIRS include
                       PRIV_REQUIRES esp_lcd esp_driver_i2c esp_driver_gpio esp_timer mbedtls)
