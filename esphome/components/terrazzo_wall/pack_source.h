#pragma once
// Glyph packs as Kobento's glyph source (kobentovg::ThorvgPaintContext::GlyphSource): this
// player's adapter to glyph_packs.h, a template so this header needn't include the player's.

#include "glyph_packs.h"

namespace tzwall {

template <class PaintContext> class PackSource : public PaintContext::GlyphSource {
 public:
  explicit PackSource(GlyphPacks &packs) : packs_(packs) {}
  bool glyph(typename PaintContext::Face face, int px, uint32_t cp,
             typename PaintContext::SourceGlyph &out) override {
    PackGlyph g;
    if (!this->packs_.find((int) face, px, cp, g)) return false;
    out.left = g.left;
    out.top = g.top;
    out.width = g.width;
    out.height = g.height;
    out.bits = g.bits;
    out.rows = g.rows;
    out.advance = g.advance;
    return true;
  }
  uint32_t generation() const override { return this->packs_.generation(); }
  void missing(typename PaintContext::Face face, int px, uint32_t cp) override {
    this->packs_.missing((int) face, px, cp);
  }

 private:
  GlyphPacks &packs_;
};

}  // namespace tzwall
