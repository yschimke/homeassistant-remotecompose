# Embedding a card in any web page

> Status: **first slice**. `<ha-card>` plays a card design template against the viewer's own Home
> Assistant, signed in with HA's OAuth, and runs taps as service calls. Kiosk embed tokens, the
> app's "Copy embed code" and cards whose templates need converter-side bindings are follow-ups
> (see [Follow-ups](#follow-ups)).

## Goal

Put one Home Assistant card on an arbitrary page, such as a blog, a wiki, a status page or a
hand-written kiosk, with the same look the app gives it, live, and with working taps:

```html
<script type="module" src="https://ha.coo.ee/embed/v1/ha-card.js"></script>
<ha-card hass="https://homeassistant.example:8123"
         design="61dbfb57a9a13167fb8cd3e2f60027c8884b73ac01dce38a49ec39046525ade4"
         card='{"type":"tile","entity":"light.kitchen"}'></ha-card>
```

- The **look** is a card design template: a values-free RemoteCompose document from the design
  library ([card-design-server.md](card-design-server.md)), addressed by its sha256.
- The **values** come from the viewer's own Home Assistant. The page's server and the design
  server never see a state, a token or a dashboard.
- The **taps** run the card's own actions through the viewer's own connection.

Non-goals: an iframe of an HA dashboard, server-side rendering with values (that is the add-on's
`/v1/cards/{id}.rc`), and any long-lived Home Assistant token in a page.

## Home Assistant precedent

- HA has no official single-card embed. You can iframe a dashboard, but that needs
  `http: use_x_frame_options: false` and a full login inside the frame, and it shows a whole
  dashboard rather than one card.
- HA's auth already treats **any website as an OAuth client**. The `client_id` is the site's URL,
  and the `redirect_uri` must be on the same host (IndieAuth style), so no client registration is
  needed. `/auth/token` allows cross-origin requests, and the WebSocket API does not check the
  origin. `home-assistant-js-websocket`'s `getAuth({ hassUrl })` and `createConnection` are built
  on this, and HA's own Cast receiver (`cast.home-assistant.io`) is a third-party-origin client
  that works this way.

`<ha-card>` follows the Cast model. It is a small client of the documented WebSocket API, and the
viewer signs in to their own instance.

## How it works

```
page ── <script type=module src=…/embed/v1/ha-card.js>
         │  loads …/embed/v1/rc-player.js          (design server: the TS RemoteCompose player)
         │  fetches …/designs/v1/blobs/<sha>.rc    (design server: verified against the sha256)
         │
         ├─ OAuth: <hass>/auth/authorize → back to this page → POST <hass>/auth/token
         ├─ wss://<hass>/api/websocket: auth → subscribe_entities(entity_ids of the card)
         │     each event → slot bindings → player named overrides → repaint
         └─ tap → HostNamedAction "ha" → card-local action → call_service
```

The design server serves the embed at `/embed/v1/ha-card.js` (the `embed/ha-card.js` source, with
no build step), the player at `/embed/v1/rc-player.js` (from
`ee.schimke.composeai:remote-compose-player-js-dist`, unzipped by
`:design-server:embedResources`) and a demo at `/embed/v1/demo.html`. The scripts, the manifest
and the blobs send `Access-Control-Allow-Origin: *`, because a page on any origin imports and
fetches them. None of them holds user data.

### Attributes

| Attribute | Meaning |
| --- | --- |
| `hass` | The viewer's Home Assistant base URL. Only its origin is used. |
| `card` | The Lovelace card config as JSON: the same object a dashboard holds. |
| `design` | The template's sha256 in the library. The bytes are checked against it where `crypto.subtle` exists, which is any secure context. |
| `design-key` | Instead of `design`, a `DesignKey` as JSON, looked up in the library's manifest. A pinned sha256 is preferred because it can't change under the page. |
| _(neither)_ | With no `design` and no `design-key`, the card asks the library's server for one (`POST /api/cards/resolve`, [card-design-server.md](card-design-server.md)): the closest library design for its type, slots, `card-style` and mode. It is asked once per card, style and mode, as a CORS simple request. Only the design is taken: the answer's values are the library example's, not this card's. |
| `card-style` | With neither of the above, the card style to draw in: an `HaUberTheme` id such as `lcd` or `braun`. The default is `classic`. |
| `library` | The design library base. The default is `/designs/v1/` on the host serving the script. |
| `theme` | `light`, `dark` or `auto`. Templates are baked per theme, so this only steers the player. |
| `height` | A fixed CSS height. By default the canvas fits the laid-out card. |
| `read-only` | Taps run no service calls. Events still fire. |

Events, which bubble and are composed: `ha-card-action` fires before a tap runs and is
cancelable, with the resolved action as its detail. `ha-card-more-info` and `ha-card-navigate`
carry the actions a page has to handle itself. `data-status` reads `loading`, `signed-out`,
`connecting`, `live`, `reconnecting` or `error`. `setStates({...})` plays the card from given
states without any Home Assistant. The demo uses it.

### Choosing the template

Choosing a template for a card, meaning its `DesignKey` and shape, runs the Android converters
(`CardConverter.designProperties`), so a browser can't do it. The snippet therefore carries the
chosen design's sha256 next to the card config. The app knows both for every card it shows, so a
"Copy embed code" action there can write the snippet (a follow-up). Until then, the template
browser on the design server and the demo page list designs with an example card for each.

## Bindings

The page pushes the **slot bindings** a template reads, which is everything
`CardDesignSlots.slotBindings` derives from a snapshot:

- `card.title`;
- for each slot `<domain>.s<N>`: `.state` (formatted, as `formatState`), `.name`, `.is_on`
  (`HaLiveBindings.isOn`, pushed as an int 0/1 like the app does), `.is_available`
  (`HaLiveBindings.isAvailable`, likewise 0/1; false for a missing entity), `.state_int`, `.status`
  and `.numeric_state`.

Names go to the player in the `USER:` domain, as the AndroidX player's `setUserLocal*` writes them.

A template that carries a **binding recipe** (`meta.binding_recipe`, see
[card-design-server.md](card-design-server.md#binding-recipes)) also gets the card-level bindings
the recipe computes from the card config and states (a gauge's `card.min`, `card.range`, …), pushed
on top of the slot bindings.

`.state` reads the way Home Assistant's frontend shows it (`HaStateFormat.kt`): binary-sensor words
by device class (`Open`, `Detected`), `Unknown` apart from `Unavailable`, the entity's
`display_precision` / `suggested_display_precision` else a device-class default, currency
(`£0.25/kWh`), durations (`1 h 05 min`), compass bearings (`NE 50°`), count units (`1 star`), `—`
for a redacted value, and free text untouched. Two differences from the app, both by design:

- The embed doesn't read the entity registry, so a registry-only display precision (`dp`, which the
  app gets from `config/entity_registry/list_for_display`) isn't applied; the attributes are.
- A timestamp (`device_class: timestamp`, `event` / `button` / `input_button` / `scene`) reads as
  a relative time from the viewer's clock (`3 h ago`), a date (local time) a week or more away. The
  element re-derives its bindings every 20 s while a card shows one, like the app's dashboard tick.

`embed/ha-card.js` is a JavaScript **port** of those functions, not ha-model's `wasmJs` build. The
port is a few hundred lines with no download cost, while the wasm build is a megabyte-scale module plus
loader for a few string functions. The port is pinned to Kotlin by one golden file,
`embed/test/bindings-golden.json`:

- `EmbedBindingsGoldenTest` (design-server, JVM) checks that every case's `expected` is exactly
  what `CardDesignSlots.slotBindings` returns, plus `CardBindingRecipe.evaluate` for a case with a
  `recipe`. `-Pembed.golden.update=true` rewrites them.
- `embed/test/bindings.test.mjs` checks that the port returns the same values.

The cases cover HALF_UP rounding and its carries, exponents, `12.xx` placeholders, Kotlin's
`toFloatOrNull` grammar (`" 7 "` and `"5f"` are numbers, `1e400` is not), every modelled domain's
`is_on`, every alarm `state_int`, containers, picture elements, repeated entities and missing
entities.

**Cards this slice keeps live:** those whose templates read only slot bindings. That covers tile,
entity, button, glance, entities and heading, and stacks or grids of them. Templates that also
read converter-computed card bindings (`CardConverter.designBindings`) need Android and are not
reproduced here. Examples are a gauge's range and severity, a clock's date, markdown blocks, list
rows, sparklines and dial fractions. Such a card plays, but those values keep the template's
placeholders. See [Follow-ups](#follow-ups).

## Taps

Every template action is an `HaAction` serialised as a `HostNamedAction` named `ha` (opcode 210)
with the JSON as its string value. The TS player already delivers it to
`CoreDocument.addActionCallback`. A tap in the templates becomes a real service call because of
that path.

A template is remote content, so, as in `CardTemplatePlayback.resolveTap`, what it encodes is
never run as is. It only picks which of the card's own actions runs:

1. A tap that names slot N (`light.s0`) of this card runs that slot's own `tap_action` from the
   card config (the row's, the child card's, or the card's).
2. With none configured, it runs HA's default for the slot's real entity
   (`defaultTapActionFor`: a toggle for toggleable domains, more-info otherwise). The template's
   action kind, service and service data are never run, so a crafted template can't turn a tap
   into `alarm_disarm` or pass its own parameters.
3. A tap that names no slot, or a slot this card doesn't have, runs the card's own `tap_action`
   or its first entity's default. A URL or navigation path in a template is never followed. Only
   the card config can supply one.

Toggle becomes `homeassistant.toggle`, the same call the app makes, and a call-service becomes
`call_service`. More-info and navigate become DOM events, because a page has no HA dialog or
router. A URL opens only for `http(s)` from the card config. Alarm keypad actions need the app's
`AlarmKeypadCoordinator`, so they fall back to the domain default in this slice.

## Connection modes

### (a) Signed-in viewer: HA OAuth (the default, implemented)

- The client id is the page's origin (`https://page.example/`). The redirect URI is the page
  itself with `?ha_card_auth=1`, which HA accepts because it is on the client id's host. `state`
  carries the HA URL, so a page with cards for two instances finishes the right sign-in.
- The card never redirects on its own. A signed-out card shows a "Connect to Home Assistant"
  button, so a page with many cards doesn't bounce the viewer around.
- The code exchange, the refresh and the stored tokens (`localStorage`,
  `ha-card-auth:<hass origin>`) follow what HA's frontend and the Cast receiver do. A refresh
  token stored on a third-party origin is the price of this model. It is the viewer's own, for
  their own instance, and they can revoke it under their HA profile's refresh tokens like any
  other.
- One WebSocket per HA per page is shared by every card on the page. It reconnects with backoff
  and resubscribes. Each card subscribes with `subscribe_entities` for its own entities only, so
  HA filters server-side and sends compressed diffs.
- Requirements: the viewer's browser must reach their HA, and an `https` page needs an `https` HA
  because of mixed content. What a viewer can do is what their HA user can do. The card only ever
  issues its own resolved actions.

### (b) Kiosk and public pages: add-on embed tokens (design only)

A page that shows a home to people with no HA account, such as a wall tablet in kiosk mode, a
family status page or a public "is the garage open" badge, must not hold a long-lived HA access
token. The token would grant the whole API to anyone who views the source. Instead:

- The add-on (`addon-server/`) issues **embed tokens**: opaque and revocable, and scoped to a list
  of entity ids, to read-only or to an explicit allow-list of services per entity, and optionally
  to an origin and an expiry. They are created and revoked in the add-on's ingress UI, which only
  HA admins reach.
- The page connects to the add-on rather than to HA: `<ha-card addon="https://…" token="…">`. The
  add-on checks the token on every subscribe and every call, holds the only HA credential (the
  Supervisor token), and pushes **slot** bindings derived with the same `slotBindings`, so the
  page needs no port at all.
- A leaked embed token exposes only its own entities, at most read-only, and is revoked in one
  place. An HA token never reaches a browser.

This needs the add-on gaps below closed first.

## Player gaps

The fixes are written as one ready-to-apply patch against the player's upstream,
[`embed/upstream/remote-compose-player.patch`](../../embed/upstream/remote-compose-player.patch).
It touches `players/typescript/` in
[yschimke/remotecompose-experiments](https://github.com/yschimke/remotecompose-experiments), and
from there [camaelon/remotecompose-experiments](https://github.com/camaelon/remotecompose-experiments),
where changes are filed:

```sh
git apply /path/to/homeassistant-remotecompose/embed/upstream/remote-compose-player.patch
cd players/typescript && npx esbuild src/host-action.test.ts --bundle --platform=node \
  --format=cjs --outfile=build-test/host-action.cjs && node build-test/host-action.cjs
```

1. **Named string overrides don't draw.** This is the gap that matters for embedding.
   `WebRemoteContext.setNamedStringOverride` updates the state but not the paint context's text
   cache, which `CoreText` measures and draws from. Every pass the document's own `TextData`
   reloads the authored text into that cache. A pushed `light.s0.state` therefore never shows:
   ints and floats work, strings don't. The patch writes the override into the cache before
   notifying listeners and keeps an overridden id's text when `TextData` re-applies.
   `ha-card.js` works around it today: `patchStringOverrides`, marked **temporary**, does the same
   from outside on the context instance. It turns itself off when the player has the fix (it
   checks for `textOverrides`).
2. **`HostActionMetadataOperation` (opcode 216) is a stub.** It reads `actionId` and
   `metadataId` and drops both, so the tap does nothing. The Java reference
   (`androidx.compose.remote.core…HostActionMetadataOperation`, remote-core alpha21) is an
   `ActionOperation`. `apply` does nothing, and `runAction` resolves the metadata **at tap time**
   (`context.getText(metadataId)`, `""` if missing) before calling
   `context.runAction(actionId, metadata)`, the same id channel as opcode 209
   (`CoreDocument.addIdActionListener`). Resolving at tap time matters because the metadata is a
   dynamic string, the action JSON plus the entity's live state and the document time
   (`WidgetActionRegistry.metadata`). The patch makes it a real operation in `ActionOperations.ts`
   with that behaviour, since TS click modifiers run their actions through `apply`.
   **The embed doesn't need this today.** Only launcher-widget captures
   (`ProvideWidgetActionRegistry`) emit 216. All 2,320 templates in the library are mobile-app
   designs, and every one of their 3,420 actions is opcode 210 (counted by parsing the library
   with the TS player). A page could not work around 216 anyway: the stub registers no click
   target. It matters once widget-surface designs are embedded.
3. **Named and URL bitmaps.** The TS player decodes only inline bitmaps. It has no host bitmap
   loader for URL-encoded images, and no named bitmap override. A picture-entity template's
   `rc-slot://<slot>/entity_picture`, which the app resolves through
   `CardDesignSlots.resolvePictureUrl`, can't be drawn. The embed needs a
   `setBitmapLoader(url → Promise<ImageBitmap>)` hook, or `setNamedBitmapOverride`, upstream. The
   HA side is simple: resolve the slot picture against the card's own entity and fetch it with the
   viewer's token.
4. Minor: a text change relayouts through the layout animation, so a fresh value settles over
   about 300ms. `RcdPlayer` has no "values changed" API, so the embed calls
   `CoreDocument.invalidateMeasure()` and `repaint()`, and it reads the laid-out height from
   `mRootLayoutComponent` to size the canvas. Both are internals and want a small public API.

## Add-on gaps

The add-on's `/v1/stream` is close to mode (b), but:

- it pushes entity-keyed bindings (`<entity_id>.state`) and the **raw** state, while templates
  read **slot** bindings with the **formatted** state, so a template fed from it shows `21.5`
  where it baked `21.5 °C`;
- it has no client authentication of its own, which is fine behind ingress and not fine on a
  public page;
- `call_service` passes any service through, where an embed token needs an allow-list;
- the card `.rc` endpoint (`/v1/cards/{id}.rc`) still answers 501.

## Security and privacy

- Templates are untrusted: they pick among the card's own actions and never name a URL, a
  service on another entity or a different HA. A template's image URLs aren't fetched at all
  today.
- `design=<sha256>` pins the exact bytes. The design server can't swap a page's template, and the
  page doesn't trust the library's manifest.
- The design server learns only that a page loaded the scripts and a blob, never anything about
  the viewer's HA.
- The embed runs in the host page's JavaScript context, as any widget script does. A page that
  includes it trusts the design server's script, the same way it trusts any CDN script.

## Testing

- `embed/test/bindings.test.mjs` checks the port against the Kotlin golden.
  `EmbedBindingsGoldenTest` checks the golden against ha-model.
- `embed/test/actions.test.mjs` covers tap resolution (slot rebinding, configured actions, the
  cases a template must not reach) and HA's compressed entity events.
- `embed/test/smoke.test.mjs` runs headless Chromium with the real player and a real template.
  It covers sign-in through the OAuth callback (authorize parameters, token exchange, a cleaned
  URL) and a stubbed HA WebSocket (`subscribe_entities`, the drawn text). A click on the canvas
  must arrive as `call_service homeassistant.toggle`, and the pushed change must be redrawn.
- `DesignServerTest.servesTheEmbedToAnyOrigin` checks the routes and the CORS headers.

Run `node --test embed/test/*.test.mjs` after `./gradlew :design-server:embedResources`. The smoke test
skips itself when Playwright isn't installed.

## Follow-ups

1. **"Copy embed code" in the app**: for a card on a dashboard, write the snippet with its config
   and the sha256 of the design it plays. The app already resolves both
   (`LocalCardDesigns`, `CardDesignTemplates`).
2. **Card-level bindings in the snippet**: the converter-computed `designBindings` that depend
   only on the config (a gauge's range and severity, area names) can be computed by the app when
   it copies the snippet, and carried as a `bindings` attribute. Values that depend on live data
   (history, forecasts, markdown templates) need a host that can compute them: the add-on.
3. **Add-on embed tokens** (mode (b)): scoped tokens, slot bindings over `/v1/stream`, a service
   allow-list, and origin checks.
4. **Upstream the player patch** and drop `patchStringOverrides` once a released
   `remote-compose-player-js-dist` has it. The embed checks for the fix at runtime, so the order
   doesn't matter.
5. Bitmaps (player gap 3), alarm keypads, `hold_action` and `double_tap_action`, and a
   more-info dialog the page can opt into.
