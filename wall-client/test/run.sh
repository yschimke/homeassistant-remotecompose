#!/usr/bin/env bash
# Builds and runs the wall client's host test. Needs a C++17 compiler and curl (for the
# ArduinoJson single header ESPHome's `json` component pins, fetched once into build/).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
src="$here/../src"
out="$here/build"
version=7.4.3
mkdir -p "$out/include"
if [[ ! -f "$out/include/ArduinoJson.h" ]]; then
  curl -fsSL -o "$out/include/ArduinoJson.h" \
    "https://github.com/bblanchon/ArduinoJson/releases/download/v$version/ArduinoJson-v$version.h"
fi
"${CXX:-c++}" -std=c++17 -Wall -Wextra -Werror -fsanitize=address,undefined -g \
  -I"$out/include" -I"$src" "$src/wall_client.cpp" "$here/wall_client_test.cpp" -o "$out/wall_client_test"
"$out/wall_client_test"
"${CXX:-c++}" -std=c++17 -Wall -Wextra -Werror -fsanitize=address,undefined -g \
  -I"$src" "$here/glyph_packs_test.cpp" -o "$out/glyph_packs_test"
# roboto-15.rcgp: Roboto Regular at 15 px, drawn by glyph_packs.py.
"$out/glyph_packs_test" "$here/roboto-15.rcgp"
