// Reference capture: given a running HA + long-lived token, walk each
// view of the reference dashboard and crop each card's bounding box
// into references/<card-type>/<name>_{light,dark}.png.
//
// HA's Lovelace lives several shadow roots deep, so we use a manual
// shadow-piercing traversal rather than plain CSS selectors. Dark
// mode is set via `Emulation.setEmulatedMedia` — HA's default theme
// honours `prefers-color-scheme`.
//
// Extend MANIFEST below to add new card references. Each entry:
//   { view, cardIndex, file }
// `file` gets the `_light` / `_dark` theme suffix appended at capture.

import puppeteer from "puppeteer";
import { mkdir, writeFile } from "node:fs/promises";
import { join, dirname } from "node:path";

const BASE = process.env.HA_URL ?? "http://127.0.0.1:8123";
const TOKEN = process.env.HA_TOKEN;
const OUT = process.env.CAPTURE_OUT ?? "/out";

if (!TOKEN) {
  console.error("Missing HA_TOKEN (integration/.env.local).");
  process.exit(2);
}

/** One entry per card crop. */
const MANIFEST = [
  { view: "tile", cardIndex: 0, file: "tile/temperature_sensor" },
  { view: "tile", cardIndex: 1, file: "tile/light_on" },
  { view: "tile", cardIndex: 2, file: "tile/light_off" },
  { view: "tile", cardIndex: 3, file: "tile/lock_locked" },
  { view: "tile", cardIndex: 4, file: "tile/cover" },

  { view: "button", cardIndex: 0, file: "button/light_on" },
  { view: "button", cardIndex: 1, file: "button/light_off" },

  { view: "entity", cardIndex: 0, file: "entity/temperature_sensor" },
  { view: "entity", cardIndex: 1, file: "entity/light_on" },

  { view: "entities", cardIndex: 0, file: "entities/living_room" },

  { view: "glance", cardIndex: 0, file: "glance/overview" },

  { view: "markdown", cardIndex: 0, file: "markdown/notes" },
];

const deepQueryAllFn = `
function deepQueryAll(selector, root = document) {
  const results = [];
  const walk = (node) => {
    if (!node) return;
    if (node.querySelectorAll) {
      for (const el of node.querySelectorAll(selector)) results.push(el);
    }
    if (node.shadowRoot) walk(node.shadowRoot);
    const kids = node.children ?? [];
    for (const k of kids) walk(k);
  };
  walk(root);
  return results;
}
`;

const browser = await puppeteer.launch({
  headless: "new",
  args: ["--no-sandbox", "--disable-setuid-sandbox"],
});

try {
  const page = await browser.newPage();
  await page.setViewport({ width: 800, height: 1600, deviceScaleFactor: 2 });

  // Seed the long-lived token so the frontend skips the login wall.
  await page.goto(BASE, { waitUntil: "domcontentloaded" });
  await page.evaluate((token) => {
    localStorage.setItem(
      "hassTokens",
      JSON.stringify({
        access_token: token,
        token_type: "Bearer",
        expires_in: 60 * 60 * 24 * 365,
        hassUrl: window.location.origin,
        clientId: window.location.origin,
        expires: Date.now() + 1000 * 60 * 60 * 24 * 365,
        refresh_token: "",
      }),
    );
  }, TOKEN);

  let wrote = 0;
  let failed = 0;
  for (const theme of ["light", "dark"]) {
    console.log(`== theme: ${theme} ==`);
    await page.emulateMediaFeatures([
      { name: "prefers-color-scheme", value: theme },
    ]);

    // Cards of a given view get navigated once; we reuse the page.
    const seenViews = new Map(); // view -> card bounding boxes
    for (const entry of MANIFEST) {
      if (!seenViews.has(entry.view)) {
        console.log(`-> navigating to view=${entry.view}`);
        await page.goto(`${BASE}/lovelace/${entry.view}`, {
          waitUntil: "networkidle2",
        });

        try {
          await page.waitForFunction(
            `(() => { ${deepQueryAllFn} return deepQueryAll('hui-card').length > 0; })()`,
            { timeout: 30_000 },
          );
        } catch (e) {
          console.error(`view '${entry.view}' never produced hui-card`);
          seenViews.set(entry.view, []);
          continue;
        }
        await new Promise((r) => setTimeout(r, 1200));
        seenViews.set(entry.view, true);
      }

      const box = await page.evaluate(
        `(() => {
          ${deepQueryAllFn}
          const el = deepQueryAll('hui-card')[${entry.cardIndex}];
          if (!el) return null;
          const r = el.getBoundingClientRect();
          return { x: r.x, y: r.y, width: r.width, height: r.height };
        })()`,
      );

      if (!box || box.width === 0) {
        console.error(
          `no card at index ${entry.cardIndex} in view '${entry.view}'`,
        );
        failed++;
        continue;
      }

      const outPath = join(OUT, `${entry.file}_${theme}.png`);
      await mkdir(dirname(outPath), { recursive: true });
      const buf = await page.screenshot({
        clip: {
          x: Math.max(0, Math.floor(box.x)),
          y: Math.max(0, Math.floor(box.y)),
          width: Math.ceil(box.width),
          height: Math.ceil(box.height),
        },
      });
      await writeFile(outPath, buf);
      console.log(
        `wrote ${outPath} (${Math.ceil(box.width)}x${Math.ceil(box.height)})`,
      );
      wrote++;
    }
  }
  console.log(`\n== summary: wrote=${wrote} failed=${failed} ==`);
} finally {
  await browser.close();
}
