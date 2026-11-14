# Wall panel roadmap

A guide to building a Home Assistant wall panel that plays RemoteCompose (RC) card designs on an
ESP32-S3 with a 7" RGB display, from nothing. It is written as a roadmap: what to build, in what
order, what each step should measure, and which tempting approaches to skip because they don't pay
on this hardware.

| Doc | Read it for |
|---|---|
| [hardware.md](hardware.md) | The Waveshare ESP32-S3-Touch-LCD-7: where its time and memory go, the board settings that matter, the dead ends, and the numbers a finished panel reaches. |
| [rendering-design.md](rendering-design.md) | How to play RC documents of Home Assistant cards fast on a microcontroller: layout, paint, damage, text, icons, images, transitions and live data. |
| [protocol-and-server.md](protocol-and-server.md) | What the panel asks Home Assistant and the design server for, and what the server must do so the panel doesn't have to. |
| [testing-and-verification.md](testing-and-verification.md) | Proving each step on a desktop first, then on the device, without fooling yourself with screenshots. |

The short version:

1. The panel is memory-bound before it is CPU-bound. Every design choice has a PSRAM budget.
2. Run the same renderer on a desktop host and make it fast and correct there first; the device
   costs minutes per try and crashes are expensive to read.
3. Never re-encode a document for a value change. Values flow into named slots in a document the
   panel already holds.
4. Draw only what changed, and cache what is expensive to draw (text) as images, bounded.
5. Let the server do the work the panel can't afford: per-display designs, simplified layouts and
   pre-rasterised glyphs.
