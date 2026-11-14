#pragma once
// Pictures for the card on screen (a camera, a picture-entity), kept as JPEG and decoded into the
// frame where they're drawn.
//
// A design names a picture by its slot (`rc-slot://camera.s0/entity_picture`); the long poll
// says where the card on screen has it (WallClient::picture_url). The player asks for one as it
// paints, at the size it shows it (version()): answered with what's held, while a task fetches it
// from Home Assistant scaled and cropped to that size, then asks the player to draw again. Only
// the JPEG is kept: each frame that draws over a picture's box decodes it there (draw()), 16
// rows at a time, straight into the frame. A picture on screen is fetched again each minute (an
// unchanged one answers 304), and at once after a tap on its card (refresh()). Only the screen
// showing keeps its pictures. The document never changes for a picture: only its slot is in it.

#include <atomic>
#include <cstdint>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <string>

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "idf_transport.h"
#include "wall_client.h"

namespace tzwall {

class PictureCache {
 public:
  struct Hooks {
    // The card id on screen now: only its pictures are fetched.
    std::function<std::string()> current_card;
    // Draw the screen again: a picture arrived.
    std::function<void()> redraw;
    // Whether the screen is lit: a dark one fetches nothing.
    std::function<bool()> screen_on = [] { return true; };
  };

  PictureCache(std::string url, std::string token, WallClient &client, Hooks hooks);

  // Starts the fetch task (FreeRTOS, on [core]).
  void start(int core = 0, int priority = 1);

  // The version of [slot_url]'s picture at [w] x [h] pixels: 0 while there's none to draw (it's
  // being fetched), else a number that changes when the picture does. Call as the player paints.
  uint32_t version(const std::string &slot_url, int w, int h);

  // [slot_url]'s picture into [box] (x0, y0, x1, y1) of an ARGB8888 frame of [stride] pixels a
  // row, only where it's [within]. A JPEG up to 7 pixels bigger than the box (fetched in whole
  // blocks of 8) is cropped to it at its centre; one of another size (the box moved) is scaled.
  void draw(const std::string &slot_url, const int *box, const int *within, uint32_t *pixels, int stride);

  // A tap on [card]: its pictures fetched now, past both caches.
  void refresh(const std::string &card);

  // Pictures drawn since the last call: how many, and the microseconds they took.
  void take_draw_stats(int &draws, int64_t &us);

 private:
  struct Entry {
    std::string card, slot, url, etag;   // url: the picture_url it was fetched from
    int w = 0, h = 0;                    // the size it's drawn at
    std::shared_ptr<const std::string> jpeg;
    uint32_t version = 0;
    int64_t fetched_us = 0, fresh_us = 0, failed_us = 0, seen_us = 0, sized_us = 0;
    bool fresh = false;
  };

  bool key_of_(const std::string &url, std::string &key) const;
  bool fetch_one_();
  void wake_();
  static void task_(void *self);

  IdfTransport http_;
  WallClient &client_;
  Hooks hooks_;
  std::mutex lock_;
  std::map<std::string, Entry> entries_;
  TaskHandle_t handle_ = nullptr;
  std::atomic<bool> settling_{false};
  std::atomic<int> draws_{0};
  std::atomic<int64_t> draw_us_{0};
};

}  // namespace tzwall
