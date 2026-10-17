#!/usr/bin/env python3
"""Add a /demo/ entry at a Pages project path without duplicating the WASM bundle."""
import sys
from pathlib import Path

site = Path(sys.argv[1])
index = (site / 'index.html').read_text()
(site / 'demo').mkdir(exist_ok=True)
(site / 'demo/index.html').write_text(index.replace('<head>', '<head>\n  <base href="../">', 1))
