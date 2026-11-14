// The board interface (kobento-mcu's board/board.h, the same declarations as every board
// under kobento-mcu/esp32/boards/), for the Waveshare ESP32-S3-Touch-LCD-7: ESP32-S3R8 (8 MB
// octal PSRAM), a 7" 800x480 RGB565 parallel panel, GT911 touch and a CH422G I/O expander
// (backlight, panel and touch resets) on I2C. Kept with the ESPHome component until it moves
// into kobento-mcu.
#pragma once
#include <cstdint>
#include <string>

namespace board {

constexpr const char* kName = "waveshare_s3_lcd7";
constexpr const char* kDisplayName = "Waveshare 7\"  ·  ESP32-S3";   // system.board, for documents
constexpr int kWidth = 800;      // native landscape panel
constexpr int kHeight = 480;
// ~133 dpi, Android's mdpi: documents lay out in 800 x 480 dp.
constexpr float kDensity = 1.0f;
constexpr bool kRound = false;  // system.screen_round: a round panel shows only the inscribed circle
constexpr bool kEink = false;   // system.screen_eink
constexpr int kGrays = 0;   // the panel's grey levels; 0 = full colour
constexpr const char* kSound = "";   // what beep() plays on: "speaker", "buzzer", "" for none

bool displayInit();
void setBrightness(int percent);

// Push a frame of opaque premultiplied ARGB8888 to the panel. The source may be smaller
// than the panel by an integer factor; it is scaled up while it is converted to RGB565 in
// strips in internal RAM. Returns microseconds spent.
int64_t present(const uint32_t* argb, int srcW, int srcH);

// Start the same on a sender task (core 0) and return, so the player renders the next frame
// meanwhile; call presentWait() before reusing `argb`. presentAsync() waits for the previous
// frame first.
void presentAsync(const uint32_t* argb, int srcW, int srcH);
void presentWait();

// The same, when only `rects` (source pixels, end-exclusive) changed since the previous
// frame: only they are converted into the panel's back buffer, plus whatever that buffer
// missed while the other one was on screen. A player that tracks damage uses it.
#define BOARD_PRESENT_DAMAGE 1
// One ARGB8888 render buffer, not two: the second (1.5 MB) left the panel 110-400 KB of PSRAM
// for documents, text and the rasteriser, and a heavy screen ran it out. The player waits for
// the previous frame's conversion to RGB565 before drawing the next (a damage-limited frame
// converts only its rectangles, so the wait is short).
#define BOARD_SINGLE_RENDER_BUFFER 1
// The player's stack, in PSRAM (sdkconfig: CONFIG_SPIRAM_ALLOW_STACK_EXTERNAL_MEMORY): the
// default 64 KB in internal RAM overflowed on a deep screen (the host needs 32-48 KB for it on
// x86; Xtensa's register windows take more) and the overflow corrupted the heap below it.
#define BOARD_RENDER_STACK_KB 192
struct Rect {
    int x0, y0, x1, y1;
};
void presentAsyncDamage(const uint32_t* argb, int srcW, int srcH, const Rect* rects, int n);

// No scan-out buffer to read back: the panel's memory is behind SPI.
const uint16_t* frameBuffer();
// Print the last presented frame to stdout for tools/snap.py: "SNAP BEGIN w h", one base64
// RGB565 row per line, "SNAP END". Downsampled by `step`.
void dumpFrame(int step);

struct Touch {
    bool down = false;
    int x = 0, y = 0;    // panel pixels
};
Touch readTouch();

// Make stdin/stdout a blocking, driver-backed console on the chip's USB Serial/JTAG, for a
// console reader that uses
// fgetc(stdin). Apps whose framework owns the console already (the Muse SDK) don't call it.
void consoleInit();

// Power a radio co-processor before networking starts. This radio is on-chip.
void radioPower();

// A short press, once per press: +1 for the PWR key (forward; latched by the AXP2101), -1 for
// BOOT (back; GPIO0), 0 for none. Apps use them to step through documents. Poll it often
// (each frame): it rate-limits its own reads.
int buttonPressed();

// ── Motion ──
// The accelerometer (m/s², gravity included) and gyroscope (rad/s) in the screen's axes, as
// Android's sensors report them: x to the right, y up the screen, z out of it (lying flat,
// face up: az = +9.81; held upright: ay = +9.81). valid = false on boards without a sensor
// (or where it isn't read yet).
struct Motion {
    bool valid = false;
    float ax = 0, ay = 0, az = 0;
    float gx = 0, gy = 0, gz = 0;
};
Motion readMotion();

// ── Clock ──
// The battery-backed real-time clock, in UTC seconds since 1970. rtcRead is false on boards
// without one, and when it lost power (its time is not to be trusted).
bool rtcRead(int64_t* epoch);
bool rtcWrite(int64_t epoch);

// ── Sound ──
// A tone of `hz` for `ms` milliseconds, played without waiting (a newer one replaces it).
// False on boards with nothing to play it on (kSound "").
bool beep(int hz, int ms);

// ── Display ──
// E-ink: redraw every pixel of the picture on the panel, which clears ghosting after many
// partial updates. False on other panels (nothing to do).
bool displayRefresh();

// ── Sleep ──
// Why the board is running: "timer" (woke from sleepFor's timer), "rtc" (switched on by the
// RTC after sleepFor's power-off), "power_on", "restart", "reset". Read it early: on the
// PaperS3 it also clears the RTC's timer flag.
const char* wakeReason();
// Light: the CPU pauses for `seconds` with everything kept (RAM, the document), and returns
// true; Wi-Fi drops and reconnects. Deep: everything off but the timer, then a full boot.
// Off: the board switches itself off and the RTC switches it on (least drain; on battery
// only: on USB it can't switch off and falls back to Deep). Deep and Off don't return.
// False if the board can't sleep that way.
enum class Sleep { Light, Deep, Off };
bool sleepFor(int seconds, Sleep how);

// ── Power ──
// Called once from the device-state sampler's task, then once a second.
void powerInit();
struct Power {
    bool valid = false;          // the board can measure its supply
    float batteryVolts = 0;
    float batteryMilliamps = 0;  // + into the battery
    int batteryLevel = -1;       // 0..100 %, -1 = no battery
    int powerState = -1;         // 0 battery, 1 charging, 2 external power, -1 unknown
    bool charging = false;
    bool headphones = false;
};
Power readPower();

// Board diagnostics on the serial console ("touch", "i2c", "power"). Returns false for a line it
// doesn't know.
bool console(const std::string& line);

}  // namespace board
