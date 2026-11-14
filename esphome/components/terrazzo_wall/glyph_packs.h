#pragma once
// Glyph packs: the panel's text faces as glyph coverage, a face at a size each, which the player
// composes runs of text from instead of rasterising them (docs/architecture/glyph-range-fonts.md).
// Packs come built into the firmware (wall-client/tools/glyph_packs.py) and from the design server at run time
// (`GET /designs/v1/fonts/glyphs`), in one wire form (`GlyphPackCodec` in ha-client).
//
// Header only and without ESP-IDF, so the host test and the host renderer read packs too.

#include <cctype>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <algorithm>
#include <atomic>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

namespace tzwall {

// The player's faces, as packs name them. Emoji: colour glyphs for what the others' fonts lack,
// from fetched packs only.
enum : int { kFaceSans = 0, kFaceSansBold = 1, kFaceMono = 2, kFaceEmoji = 3 };

// A colour pack's depth: premultiplied ARGB a pixel.
constexpr int kColorBits = 32;

struct PackGlyph {
  // The ink's top-left from the pen on the baseline (top < 0: above it), in pixels.
  int16_t left = 0, top = 0;
  uint16_t width = 0, height = 0;
  uint8_t bits = 8;   // kColorBits: colour, ARGB big-endian, untinted
  // `height` rows, each padded to a byte, high bits first; where the pack lies (flash or heap).
  const uint8_t *rows = nullptr;
  int16_t advance = 0;   // marginLeft + width + marginRight

  uint8_t coverage(int x, int y) const {
    if (bits == 8) return rows[(size_t) y * width + x];
    const int levels = (1 << bits) - 1, row = (width * bits + 7) / 8, bit = x * bits;
    const int q = (rows[(size_t) y * row + bit / 8] >> (8 - bits - bit % 8)) & levels;
    return (uint8_t) (q * 255 / levels);
  }
};

// A pack's face: which of the player's faces, at how many pixels an em.
struct PackStyle {
  int face = -1;
  int px = 0;
  bool operator<(const PackStyle &o) const { return face != o.face ? face < o.face : px < o.px; }
  bool operator==(const PackStyle &o) const { return face == o.face && px == o.px; }
};

// The face a pack's family and weight are, or -1 for one the player doesn't draw.
inline int face_of(std::string family, int weight, bool italic) {
  for (auto &c : family) c = (char) std::tolower((unsigned char) c);
  if (family.rfind("google:", 0) == 0) family = family.substr(7);
  if (family == "emoji" || family == "noto color emoji") return kFaceEmoji;
  if (italic) return -1;
  if (family == "roboto") return weight >= 600 ? kFaceSansBold : kFaceSans;
  if (family == "roboto mono") return kFaceMono;
  return -1;
}

// What the design server is asked for a face's glyphs by.
inline const char *family_of(int face) {
  return face == kFaceEmoji ? "emoji" : face == kFaceMono ? "google:Roboto Mono" : "roboto";
}
inline int weight_of(int face) { return face == kFaceSansBold ? 700 : 400; }

// The single code point [s] is, or 0 if it is none or more than one.
inline uint32_t single_code_point(const uint8_t *s, size_t n) {
  if (n == 0) return 0;
  uint32_t cp;
  size_t len;
  if (s[0] < 0x80) { cp = s[0]; len = 1; }
  else if ((s[0] & 0xE0) == 0xC0) { cp = s[0] & 0x1F; len = 2; }
  else if ((s[0] & 0xF0) == 0xE0) { cp = s[0] & 0x0F; len = 3; }
  else if ((s[0] & 0xF8) == 0xF0) { cp = s[0] & 0x07; len = 4; }
  else return 0;
  if (len != n) return 0;
  for (size_t i = 1; i < len; i++) {
    if ((s[i] & 0xC0) != 0x80) return 0;
    cp = (cp << 6) | (s[i] & 0x3F);
  }
  return cp;
}

// [data] as a pack: its style and its glyphs by code point, pointing into [data]. False if it
// isn't a whole pack, or is of a face or size the player doesn't draw.
inline bool read_glyph_pack(const uint8_t *data, size_t size, PackStyle &style,
                            std::vector<std::pair<uint32_t, PackGlyph>> &glyphs) {
  size_t at = 0;
  bool ok = true;
  auto need = [&](size_t n) {
    if (!ok || at > size || size - at < n) ok = false;
    return ok;
  };
  auto u8 = [&]() -> uint32_t { return need(1) ? data[at++] : 0; };
  auto u16 = [&]() -> uint32_t { uint32_t hi = u8(); return (hi << 8) | u8(); };
  auto i16 = [&]() -> int { return (int16_t) u16(); };
  auto f32 = [&]() -> float {
    uint32_t b = (u16() << 16);
    b |= u16();
    float f;
    std::memcpy(&f, &b, 4);
    return f;
  };
  if (!need(4) || std::memcmp(data, "RCGP", 4) != 0) return false;
  at = 4;
  if (u8() != 1) return false;
  const int bits = (int) u8();
  if (bits != 8 && bits != 4 && bits != 2 && bits != 1 && bits != kColorBits) return false;
  u16();  // flags: kerning, which the player doesn't use (its advances are its own)
  const size_t familyLength = u16();
  if (!need(familyLength)) return false;
  std::string family((const char *) data + at, familyLength);
  at += familyLength;
  const float sz = f32(), density = f32();
  const int weight = (int) u16();
  const bool italic = u8() != 0;
  i16();  // line height
  const int baseline = i16();
  const size_t count = u16();
  if (!ok) return false;
  const float px = sz * density;
  style.face = face_of(family, weight, italic);
  style.px = (int) std::lround(px);
  if (style.face < 0 || style.px <= 0 || std::fabs(px - (float) style.px) > 0.01f) return false;
  glyphs.clear();
  glyphs.reserve(count);
  for (size_t i = 0; i < count; i++) {
    const size_t charsLength = u8();
    if (!need(charsLength)) return false;
    const uint32_t cp = single_code_point(data + at, charsLength);
    at += charsLength;
    PackGlyph g;
    g.left = (int16_t) i16();
    g.top = (int16_t) (i16() - baseline);
    const int right = i16();
    i16();  // bottom margin
    g.width = (uint16_t) u16();
    g.height = (uint16_t) u16();
    g.bits = (uint8_t) bits;
    g.advance = (int16_t) (g.left + g.width + right);
    const size_t bytes = (size_t) ((g.width * bits + 7) / 8) * g.height;
    if (!need(bytes)) return false;
    g.rows = data + at;
    at += bytes;
    if (cp != 0) glyphs.emplace_back(cp, g);  // a ligature's glyph: not one the player asks for
  }
  return ok;  // the kerning table, if any, follows; it isn't read
}

// Every pack the panel has, looked up by the player as it draws (on its own core) and added to
// by the fetch (on the other): a lock around both. Packs are never dropped, so a glyph's rows
// stay where they are once found. Sorted vectors, not maps: a vector is one allocation, which
// the panel puts in PSRAM, where a map's small nodes would fill its internal RAM.
class GlyphPacks {
 public:
  struct Want {
    PackStyle style;
    std::vector<uint32_t> code_points;
  };

  // Adds [data]'s glyphs (a glyph already held stays). [data] must outlive this.
  bool add(const uint8_t *data, size_t size) {
    PackStyle style;
    std::vector<std::pair<uint32_t, PackGlyph>> glyphs;
    if (!read_glyph_pack(data, size, style, glyphs)) return false;
    std::lock_guard<std::mutex> lock(this->mutex_);
    auto s = std::lower_bound(this->styles_.begin(), this->styles_.end(), style,
                              [](const Style &a, const PackStyle &b) { return a.style < b; });
    if (s == this->styles_.end() || !(s->style == style)) s = this->styles_.insert(s, Style{style, {}});
    auto &held = s->glyphs;
    for (auto &g : glyphs) {
      auto at = std::lower_bound(held.begin(), held.end(), g.first, by_code_point);
      if (at == held.end() || at->first != g.first) held.insert(at, g);
      remove_key(this->wanted_, key(style, g.first));
    }
    this->generation_++;
    return true;
  }

  // Moves whenever a pack is added: text measured or drawn before then may be again.
  uint32_t generation() const { return this->generation_.load(); }

  bool find(int face, int px, uint32_t cp, PackGlyph &out) const {
    std::lock_guard<std::mutex> lock(this->mutex_);
    const PackStyle style{face, px};
    auto s = std::lower_bound(this->styles_.begin(), this->styles_.end(), style,
                              [](const Style &a, const PackStyle &b) { return a.style < b; });
    if (s == this->styles_.end() || !(s->style == style)) return false;
    auto g = std::lower_bound(s->glyphs.begin(), s->glyphs.end(), cp, by_code_point);
    if (g == s->glyphs.end() || g->first != cp) return false;
    out = g->second;
    return true;
  }

  // A glyph a run needed that no pack has: asked for once (see take_wants).
  void missing(int face, int px, uint32_t cp) {
    std::lock_guard<std::mutex> lock(this->mutex_);
    if (this->asked_.size() >= kMaxAsked) return;
    const uint64_t k = key({face, px}, cp);
    if (insert_key(this->asked_, k)) insert_key(this->wanted_, k);
  }

  // [want] again, on the next take_wants: its fetch didn't get an answer.
  void retry(const Want &want) {
    std::lock_guard<std::mutex> lock(this->mutex_);
    for (uint32_t cp : want.code_points) insert_key(this->wanted_, key(want.style, cp));
  }

  // The glyphs reported missing since the last call, by style.
  std::vector<Want> take_wants() {
    std::lock_guard<std::mutex> lock(this->mutex_);
    std::vector<Want> wants;
    for (uint64_t k : this->wanted_) {
      const PackStyle style{(int) (k >> 40), (int) ((k >> 24) & 0xFFFF)};
      if (wants.empty() || !(wants.back().style == style)) wants.push_back({style, {}});
      wants.back().code_points.push_back((uint32_t) (k & 0xFFFFFF));
    }
    this->wanted_.clear();
    return wants;
  }

  size_t glyph_count() const {
    std::lock_guard<std::mutex> lock(this->mutex_);
    size_t n = 0;
    for (auto &s : this->styles_) n += s.glyphs.size();
    return n;
  }

  size_t style_count() const {
    std::lock_guard<std::mutex> lock(this->mutex_);
    return this->styles_.size();
  }

 private:
  struct Style {
    PackStyle style;
    std::vector<std::pair<uint32_t, PackGlyph>> glyphs;  // by code point
  };
  static bool by_code_point(const std::pair<uint32_t, PackGlyph> &g, uint32_t cp) { return g.first < cp; }
  // A style and code point as one sortable key: face, size, code point (21 bits).
  static uint64_t key(const PackStyle &style, uint32_t cp) {
    return ((uint64_t) (uint32_t) style.face << 40) | ((uint64_t) (style.px & 0xFFFF) << 24) | (cp & 0xFFFFFF);
  }
  static bool insert_key(std::vector<uint64_t> &keys, uint64_t k) {
    auto at = std::lower_bound(keys.begin(), keys.end(), k);
    if (at != keys.end() && *at == k) return false;
    keys.insert(at, k);
    return true;
  }
  static void remove_key(std::vector<uint64_t> &keys, uint64_t k) {
    auto at = std::lower_bound(keys.begin(), keys.end(), k);
    if (at != keys.end() && *at == k) keys.erase(at);
  }

  static constexpr size_t kMaxAsked = 1024;
  mutable std::mutex mutex_;
  std::vector<Style> styles_;
  std::vector<uint64_t> asked_, wanted_;
  std::atomic<uint32_t> generation_{0};
};


// [cps] as UTF-8, percent-encoded for a query.
inline std::string query_chars(const std::vector<uint32_t> &cps) {
  std::string utf8;
  for (uint32_t cp : cps) {
    if (cp < 0x80) utf8 += (char) cp;
    else if (cp < 0x800) { utf8 += (char) (0xC0 | (cp >> 6)); utf8 += (char) (0x80 | (cp & 0x3F)); }
    else if (cp < 0x10000) {
      utf8 += (char) (0xE0 | (cp >> 12));
      utf8 += (char) (0x80 | ((cp >> 6) & 0x3F));
      utf8 += (char) (0x80 | (cp & 0x3F));
    } else {
      utf8 += (char) (0xF0 | (cp >> 18));
      utf8 += (char) (0x80 | ((cp >> 12) & 0x3F));
      utf8 += (char) (0x80 | ((cp >> 6) & 0x3F));
      utf8 += (char) (0x80 | (cp & 0x3F));
    }
  }
  static const char *hex = "0123456789ABCDEF";
  std::string out;
  for (unsigned char c : utf8) {
    if (std::isalnum(c) || c == '-' || c == '_' || c == '.' || c == '~') out += (char) c;
    else { out += '%'; out += hex[c >> 4]; out += hex[c & 15]; }
  }
  return out;
}

// The query (after `?`) the design server's glyph route takes for [want]'s glyphs, at [bits] a
// pixel (colour for the emoji face, whatever [bits] says).
inline std::string glyph_query(const GlyphPacks::Want &want, int bits) {
  if (want.style.face == kFaceEmoji) bits = kColorBits;
  std::string family = family_of(want.style.face), f;
  for (char c : family) f += c == ' ' ? std::string("%20") : std::string(1, c);
  return "family=" + f + "&size=" + std::to_string(want.style.px) +
         "&density=1&weight=" + std::to_string(weight_of(want.style.face)) + "&bits=" +
         std::to_string(bits) + "&chars=" + query_chars(want.code_points);
}

}  // namespace tzwall
