// Waveshare ESP32-S3-Touch-LCD-7: display (7" 800x480 RGB565 parallel panel, esp_lcd RGB
// driver, two frame buffers in PSRAM), touch (GT911 on I2C), backlight and resets (CH422G
// I/O expander on I2C).
//
// Pins and timings are ESPHome's mipi_rgb model ESP32-S3-TOUCH-LCD-7-800X480 (ESPHome
// 2026.10, components/mipi_rgb/models/waveshare.py); the CH422G registers are ESPHome's
// ch422g component's; the I2C bus (SDA 8, SCL 9), touch interrupt (GPIO4) and the GT911
// address-select reset are Waveshare's ESP-IDF examples for this board.
//
// The panel's GPIOs include GPIO0 (BOOT), so there are no buttons; and no IMU, RTC, PMU or
// buzzer driver here.
#include "board/board.h"

#include <algorithm>
#include <cstdio>
#include <cstring>

#include "driver/gpio.h"
#include "driver/i2c_master.h"
#include "esp_attr.h"
#include "esp_heap_caps.h"
#include "esp_lcd_panel_ops.h"
#include "esp_lcd_panel_rgb.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "mbedtls/base64.h"

static const char* TAG = "lcd7";

namespace board {

// ── Pins ──
static constexpr gpio_num_t kSda = GPIO_NUM_8, kScl = GPIO_NUM_9, kTouchInt = GPIO_NUM_4;
static constexpr int kDe = 5, kHsync = 46, kVsync = 3, kPclk = 7;
// D0..D15 as RGB565 has them, low bits first: blue 0-4, green 0-5, red 0-4.
static constexpr int kData[16] = {14, 38, 18, 17, 10,        // B
                                  39, 0, 45, 48, 47, 21,     // G
                                  1, 2, 42, 41, 40};         // R
static constexpr uint32_t kPclkHz = 16 * 1000 * 1000;

// ── CH422G: each register is its own I2C address. 0x24 system (bit 0: IO0-7 are outputs),
// 0x38 the outputs IO0-7. IO1 touch reset, IO2 backlight, IO3 panel reset, IO4 SD card CS,
// IO6 the panel's enable on the 7" (ESPHome's model turns IO2 and IO6 on). ──
static constexpr uint8_t kChSystem = 0x24, kChOut = 0x38;
static constexpr uint8_t kIoTouchRst = 1 << 1, kIoBacklight = 1 << 2, kIoLcdRst = 1 << 3,
                         kIoSdCs = 1 << 4, kIoLcdEnable = 1 << 6;
static constexpr uint8_t kGt911 = 0x5D, kGt911Alt = 0x14;

static i2c_master_bus_handle_t sBus;
static i2c_master_dev_handle_t sChSystem, sChOut, sTouch;
static uint8_t sOut = kIoSdCs;
static esp_lcd_panel_handle_t sPanel;
static uint16_t* sFb[2];
static int sBack = 0;             // the frame buffer not on screen
static SemaphoreHandle_t sVsync;
static const uint32_t* sLast;     // last presented frame, for dumpFrame
static int sLastW, sLastH;

// ── I2C ──

static i2c_master_dev_handle_t i2cDevice(uint8_t addr) {
    if (!sBus) {
        i2c_master_bus_config_t cfg = {};
        cfg.i2c_port = I2C_NUM_0;
        cfg.sda_io_num = kSda;
        cfg.scl_io_num = kScl;
        cfg.clk_source = I2C_CLK_SRC_DEFAULT;
        cfg.glitch_ignore_cnt = 7;
        cfg.flags.enable_internal_pullup = true;
        if (i2c_new_master_bus(&cfg, &sBus) != ESP_OK) return nullptr;
    }
    if (i2c_master_probe(sBus, addr, 50) != ESP_OK) return nullptr;
    i2c_device_config_t dev = {};
    dev.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    dev.device_address = addr;
    dev.scl_speed_hz = 400000;
    i2c_master_dev_handle_t h = nullptr;
    i2c_master_bus_add_device(sBus, &dev, &h);
    return h;
}

static bool chWrite(i2c_master_dev_handle_t reg, uint8_t value) {
    return reg && i2c_master_transmit(reg, &value, 1, 50) == ESP_OK;
}

static void expander(uint8_t set, uint8_t clear) {
    sOut = (uint8_t) ((sOut | set) & ~clear);
    chWrite(sChOut, sOut);
}

// GT911 registers are 16-bit, big-endian.
static bool gtRead(uint16_t reg, uint8_t* out, size_t n) {
    const uint8_t r[2] = {(uint8_t) (reg >> 8), (uint8_t) reg};
    return sTouch && i2c_master_transmit_receive(sTouch, r, 2, out, n, 50) == ESP_OK;
}

static bool gtWrite(uint16_t reg, uint8_t value) {
    const uint8_t b[3] = {(uint8_t) (reg >> 8), (uint8_t) reg, value};
    return sTouch && i2c_master_transmit(sTouch, b, 3, 50) == ESP_OK;
}

// The expander first: it holds the panel and the touch controller in reset. The GT911 takes
// its address from INT while it leaves reset: low gives 0x5D.
static void resetPeripherals() {
    sChSystem = i2cDevice(kChSystem);
    sChOut = i2cDevice(kChOut);
    if (!sChSystem || !sChOut) {
        ESP_LOGE(TAG, "CH422G not found on I2C (SDA 8, SCL 9): no backlight, no touch");
        return;
    }
    chWrite(sChSystem, 0x01);   // IO0-7 push-pull outputs
    expander(kIoLcdEnable | kIoSdCs, kIoTouchRst | kIoLcdRst | kIoBacklight);

    gpio_config_t out = {};
    out.pin_bit_mask = 1ULL << kTouchInt;
    out.mode = GPIO_MODE_OUTPUT;
    gpio_config(&out);
    gpio_set_level(kTouchInt, 0);
    vTaskDelay(pdMS_TO_TICKS(20));
    expander(kIoTouchRst | kIoLcdRst, 0);
    vTaskDelay(pdMS_TO_TICKS(100));
    gpio_set_direction(kTouchInt, GPIO_MODE_INPUT);
    vTaskDelay(pdMS_TO_TICKS(100));

    sTouch = i2cDevice(kGt911);
    if (!sTouch) sTouch = i2cDevice(kGt911Alt);
    uint8_t id[4] = {};
    if (gtRead(0x8140, id, sizeof id))
        ESP_LOGI(TAG, "touch GT%.4s", (const char*) id);
    else
        ESP_LOGW(TAG, "no GT911 touch controller");
}

// ── Panel ──

static bool IRAM_ATTR onVsync(esp_lcd_panel_handle_t, const esp_lcd_rgb_panel_event_data_t*, void*) {
    BaseType_t woken = pdFALSE;
    xSemaphoreGiveFromISR(sVsync, &woken);
    return woken == pdTRUE;
}

// The backlight is on/off only (CH422G IO2): no PWM on this board.
void setBrightness(int percent) {
    if (percent > 0) expander(kIoBacklight, 0);
    else expander(0, kIoBacklight);
}

bool displayInit() {
    resetPeripherals();

    esp_lcd_rgb_panel_config_t cfg = {};
    cfg.clk_src = LCD_CLK_SRC_DEFAULT;
    cfg.timings.pclk_hz = kPclkHz;
    cfg.timings.h_res = kWidth;
    cfg.timings.v_res = kHeight;
    cfg.timings.hsync_pulse_width = 4;
    cfg.timings.hsync_back_porch = 8;
    cfg.timings.hsync_front_porch = 8;
    cfg.timings.vsync_pulse_width = 4;
    cfg.timings.vsync_back_porch = 16;
    cfg.timings.vsync_front_porch = 16;
    cfg.timings.flags.pclk_active_neg = true;
    cfg.data_width = 16;
    cfg.bits_per_pixel = 16;
    cfg.num_fbs = 2;
    // Bounce buffers in internal RAM keep the scan-out steady while the CPU works PSRAM hard.
    cfg.bounce_buffer_size_px = kWidth * 10;
    cfg.hsync_gpio_num = kHsync;
    cfg.vsync_gpio_num = kVsync;
    cfg.de_gpio_num = kDe;
    cfg.pclk_gpio_num = kPclk;
    cfg.disp_gpio_num = GPIO_NUM_NC;
    for (int i = 0; i < 16; i++) cfg.data_gpio_nums[i] = kData[i];
    cfg.flags.fb_in_psram = true;
    if (esp_lcd_new_rgb_panel(&cfg, &sPanel) != ESP_OK) {
        ESP_LOGE(TAG, "RGB panel: esp_lcd_new_rgb_panel failed");
        return false;
    }
    sVsync = xSemaphoreCreateBinary();
    esp_lcd_rgb_panel_event_callbacks_t cbs = {};
    cbs.on_vsync = onVsync;
    esp_lcd_rgb_panel_register_event_callbacks(sPanel, &cbs, nullptr);
    esp_lcd_panel_reset(sPanel);
    esp_lcd_panel_init(sPanel);
    void *a = nullptr, *b = nullptr;
    esp_lcd_rgb_panel_get_frame_buffer(sPanel, 2, &a, &b);
    sFb[0] = (uint16_t*) a;
    sFb[1] = (uint16_t*) b;
    if (!sFb[0] || !sFb[1]) return false;
    memset(sFb[0], 0, (size_t) kWidth * kHeight * 2);
    memset(sFb[1], 0, (size_t) kWidth * kHeight * 2);
    sBack = 1;   // the driver scans out buffer 0 first
    setBrightness(100);
    ESP_LOGI(TAG, "panel up: %dx%d RGB565 at %u MHz", kWidth, kHeight, (unsigned) (kPclkHz / 1000000));
    return true;
}

// Premultiplied, opaque ARGB8888 -> RGB565 (the RGB bus takes it in CPU byte order).
static inline uint16_t to565(uint32_t p) {
    return (uint16_t) (((p >> 8) & 0xF800) | ((p >> 5) & 0x07E0) | ((p >> 3) & 0x001F));
}

// What each frame buffer is missing: the rectangles that changed since it was last filled
// (`full` once there are too many to list).
struct Backlog {
    Rect r[8];
    int n = 0;
    bool full = true;
    void add(const Rect& a) {
        if (full) return;
        for (int i = 0; i < n; i++) {   // inside one already
            if (a.x0 >= r[i].x0 && a.y0 >= r[i].y0 && a.x1 <= r[i].x1 && a.y1 <= r[i].y1) return;
        }
        if (n == 8) { full = true; return; }
        r[n++] = a;
    }
};
static Backlog sBacklog[2];

// Fill the back buffer, hand it to the driver (it switches at the next vsync without a copy:
// the bitmap is one of its own frame buffers), and wait for that vsync so the old front buffer
// is free to draw into next. Only what the back buffer is missing is converted.
static void sendFrame(const uint32_t* argb, int srcW, int srcH) {
    const int k = std::max(1, kWidth / std::max(1, srcW));   // integer upscale factor
    uint16_t* dst = sFb[sBack];
    Backlog& todo = sBacklog[sBack];
    if (todo.full || k != 1) {
        for (int y = 0; y < kHeight; y++) {
            const uint32_t* src = argb + (size_t) std::min(srcH - 1, y / k) * srcW;
            uint16_t* d = dst + (size_t) y * kWidth;
            if (k == 1) {
                const int w = std::min(srcW, kWidth);
                for (int x = 0; x < w; x++) d[x] = to565(src[x]);
            } else {
                for (int x = 0; x < kWidth; x++) d[x] = to565(src[std::min(srcW - 1, x / k)]);
            }
        }
    } else {
        for (int i = 0; i < todo.n; i++) {
            const Rect& r = todo.r[i];
            const int x0 = std::max(0, r.x0), x1 = std::min({r.x1, srcW, kWidth});
            const int y0 = std::max(0, r.y0), y1 = std::min({r.y1, srcH, kHeight});
            for (int y = y0; y < y1; y++) {
                const uint32_t* src = argb + (size_t) y * srcW;
                uint16_t* d = dst + (size_t) y * kWidth;
                for (int x = x0; x < x1; x++) d[x] = to565(src[x]);
            }
        }
    }
    todo = Backlog{};
    todo.full = false;
    xSemaphoreTake(sVsync, 0);   // forget a stale one
    esp_lcd_panel_draw_bitmap(sPanel, 0, 0, kWidth, kHeight, dst);
    xSemaphoreTake(sVsync, pdMS_TO_TICKS(100));
    sBack ^= 1;
}

// A sender task on core 0 converts and sends frame N while the player (core 1) renders N+1.
static TaskHandle_t sSender;
static SemaphoreHandle_t sJob, sIdle;
static const uint32_t* sJobArgb;
static int sJobW, sJobH;

static void senderTask(void*) {
    while (true) {
        xSemaphoreTake(sJob, portMAX_DELAY);
        sendFrame(sJobArgb, sJobW, sJobH);
        xSemaphoreGive(sIdle);
    }
}

void presentWait() {
    if (!sIdle) return;
    xSemaphoreTake(sIdle, portMAX_DELAY);
    xSemaphoreGive(sIdle);
}

static void startSender() {
    if (sSender) return;
    sJob = xSemaphoreCreateBinary();
    sIdle = xSemaphoreCreateBinary();
    xSemaphoreGive(sIdle);
    xTaskCreatePinnedToCore(senderTask, "lcd_send", 3072, nullptr, 6, &sSender, 0);
}

static void submit(const uint32_t* argb, int srcW, int srcH) {
    sJobArgb = argb;
    sJobW = srcW;
    sJobH = srcH;
    sLast = argb;
    sLastW = srcW;
    sLastH = srcH;
    xSemaphoreGive(sJob);
}

void presentAsync(const uint32_t* argb, int srcW, int srcH) {
    if (!sPanel) return;
    startSender();
    xSemaphoreTake(sIdle, portMAX_DELAY);
    sBacklog[0].full = sBacklog[1].full = true;   // anything may have changed
    submit(argb, srcW, srcH);
}

void presentAsyncDamage(const uint32_t* argb, int srcW, int srcH, const Rect* rects, int n) {
    if (!sPanel) return;
    startSender();
    xSemaphoreTake(sIdle, portMAX_DELAY);   // the sender is idle: the backlogs are ours
    for (int b = 0; b < 2; b++)
        for (int i = 0; i < n; i++) sBacklog[b].add(rects[i]);
    submit(argb, srcW, srcH);
}

int64_t present(const uint32_t* argb, int srcW, int srcH) {
    const int64_t t0 = esp_timer_get_time();
    presentAsync(argb, srcW, srcH);
    presentWait();
    return esp_timer_get_time() - t0;
}

const uint16_t* frameBuffer() { return sFb[0] ? sFb[sBack ^ 1] : nullptr; }

void dumpFrame(int step) {
    presentWait();
    const uint16_t* fb = frameBuffer();
    if (!fb || step < 1) { printf("SNAP BEGIN 0 0\nSNAP END\n"); return; }
    const int ow = kWidth / step, oh = kHeight / step;
    static uint16_t row[kWidth];
    static unsigned char b64[kWidth * 3];
    printf("\nSNAP BEGIN %d %d\n", ow, oh);
    for (int y = 0; y < oh; y++) {
        for (int x = 0; x < ow; x++) row[x] = fb[(size_t) y * step * kWidth + x * step];
        size_t olen = 0;
        mbedtls_base64_encode(b64, sizeof(b64), &olen, (const unsigned char*) row, ow * 2);
        b64[olen] = 0;
        printf("%s\n", b64);
        if ((y & 15) == 15) fflush(stdout);
    }
    printf("SNAP END\n");
    fflush(stdout);
}

// ── Touch: GT911. 0x814E: bit 7 data ready, bits 0-3 the touch count; the first point at
// 0x8150 (x low, x high, y low, y high). Clear 0x814E after each read. ──

// The GT911 reports at its scan rate, not every time it's asked: between reports the last
// one holds. A report with no points is the lift; a held finger with no report for 300 ms
// (a missed lift) is let go.
Touch readTouch() {
    static Touch last;
    static int64_t lastAt = 0;
    const int64_t now = esp_timer_get_time();
    uint8_t status = 0;
    if (!gtRead(0x814E, &status, 1) || !(status & 0x80)) {
        if (last.down && now - lastAt > 300000) last.down = false;
        return last;
    }
    uint8_t p[4] = {};
    const bool down = (status & 0x0F) > 0 && gtRead(0x8150, p, sizeof p);
    gtWrite(0x814E, 0);
    lastAt = now;
    last.down = false;
    if (!down) return last;
    const int x = p[0] | p[1] << 8, y = p[2] | p[3] << 8;
    if (x >= kWidth || y >= kHeight) return last;
    last.down = true;
    last.x = x;
    last.y = y;
    return last;
}

void consoleInit() {}   // ESPHome's logger owns the console
void radioPower() {}
int buttonPressed() { return 0; }   // BOOT (GPIO0) is a panel data line here

Motion readMotion() { return {}; }
bool rtcRead(int64_t*) { return false; }
bool rtcWrite(int64_t) { return false; }
bool beep(int, int) { return false; }
bool displayRefresh() { return false; }
const char* wakeReason() { return "power_on"; }
bool sleepFor(int, Sleep) { return false; }
void powerInit() {}
Power readPower() { return {}; }

bool console(const std::string& line) {
    if (line == "i2c") {
        printf("i2c (SDA 8, SCL 9):");
        for (int a = 0x08; a < 0x78; a++)
            if (sBus && i2c_master_probe(sBus, a, 20) == ESP_OK) printf(" 0x%02X", a);
        printf("\n");
        return true;
    }
    return false;
}

}  // namespace board
