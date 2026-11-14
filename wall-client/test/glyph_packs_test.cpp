// Host test of the glyph pack reader and store (glyph_packs.h), on a pack glyph_packs.py drew.
#include "glyph_packs.h"

#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <iterator>

static int failures = 0;
#define CHECK(c)                                                  \
  do {                                                            \
    if (!(c)) {                                                   \
      std::fprintf(stderr, "%s:%d: %s\n", __FILE__, __LINE__, #c); \
      failures++;                                                 \
    }                                                             \
  } while (0)

int main(int argc, char **argv) {
  if (argc < 2) return 2;
  std::ifstream in(argv[1], std::ios::binary);
  std::vector<uint8_t> pack((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
  CHECK(pack.size() > 100);

  tzwall::PackStyle style;
  std::vector<std::pair<uint32_t, tzwall::PackGlyph>> glyphs;
  CHECK(tzwall::read_glyph_pack(pack.data(), pack.size(), style, glyphs));
  CHECK(style.face == tzwall::kFaceSans && style.px == 15);
  CHECK(glyphs.size() > 95);

  // Every truncation is refused, never read past.
  for (size_t n = 0; n < pack.size(); n += 7) {
    std::vector<uint8_t> cut(pack.begin(), pack.begin() + n);
    CHECK(!tzwall::read_glyph_pack(cut.data(), cut.size(), style, glyphs));
  }

  tzwall::GlyphPacks packs;
  CHECK(packs.add(pack.data(), pack.size()));
  tzwall::PackGlyph h, space, degree;
  CHECK(packs.find(tzwall::kFaceSans, 15, 'H', h));
  // An H at 15 px: some 8 by 11 px of ink, standing on the baseline, a stem fully covered.
  CHECK(h.width >= 7 && h.width <= 10 && h.height == 11 && h.top == -11);
  CHECK(h.coverage(h.width / 2, 0) == 0 && h.coverage(1, h.height / 2) > 200);
  CHECK(packs.find(tzwall::kFaceSans, 15, ' ', space) && space.width == 0);
  CHECK(packs.find(tzwall::kFaceSans, 15, 0xB0, degree) && degree.top < h.top + 4);
  CHECK(!packs.find(tzwall::kFaceSansBold, 15, 'H', h));
  CHECK(!packs.find(tzwall::kFaceSans, 15, 0x4E2D, h));

  // A missing glyph is asked for once, and again only when its fetch is retried.
  packs.missing(tzwall::kFaceSansBold, 16, 'A');
  packs.missing(tzwall::kFaceSansBold, 16, 'A');
  packs.missing(tzwall::kFaceSansBold, 16, 0xE9);
  auto wants = packs.take_wants();
  CHECK(wants.size() == 1 && wants[0].code_points.size() == 2);
  CHECK(packs.take_wants().empty());
  CHECK(tzwall::glyph_query(wants[0], 4) ==
        "family=roboto&size=16&density=1&weight=700&bits=4&chars=A%C3%A9");
  packs.retry(wants[0]);
  CHECK(packs.take_wants().size() == 1);
  tzwall::GlyphPacks::Want mono{{tzwall::kFaceMono, 13}, {'0'}};
  CHECK(tzwall::glyph_query(mono, 4) ==
        "family=google:Roboto%20Mono&size=13&density=1&weight=400&bits=4&chars=0");

  // An emoji: the colour face, asked for at 32 bits whatever the panel's coverage depth.
  tzwall::GlyphPacks::Want rocket{{tzwall::kFaceEmoji, 22}, {0x1F680}};
  CHECK(tzwall::glyph_query(rocket, 4) ==
        "family=emoji&size=22&density=1&weight=400&bits=32&chars=%F0%9F%9A%80");
  CHECK(tzwall::face_of("emoji", 400, false) == tzwall::kFaceEmoji);
  // A colour pack: one 2x1 glyph, premultiplied ARGB, read in place with its advance.
  {
    std::vector<uint8_t> p = {'R', 'C', 'G', 'P', 1, 32, 0, 0, 0, 5, 'e', 'm', 'o', 'j', 'i',
                              0x41, 0xB0, 0, 0, 0x3F, 0x80, 0, 0, 1, 0x90, 0, 0, 25, 0, 20, 0, 1,
                              4, 0xF0, 0x9F, 0x9A, 0x80, 0, 1, 0, 2, 0, 3, 0, 0, 0, 2, 0, 1,
                              0xFF, 0x10, 0x20, 0x30, 0x80, 0x40, 0x00, 0x00};
    const uint32_t before = packs.generation();
    CHECK(packs.add(p.data(), p.size()));
    CHECK(packs.generation() != before);
    tzwall::PackGlyph e;
    CHECK(packs.find(tzwall::kFaceEmoji, 22, 0x1F680, e));
    CHECK(e.bits == 32 && e.width == 2 && e.advance == 1 + 2 + 3 && e.top == 2 - 20);
    CHECK(e.rows[0] == 0xFF && e.rows[4] == 0x80);
  }

  if (failures) return 1;
  std::printf("glyph packs: ok (%zu glyphs)\n", packs.glyph_count());
  return 0;
}
