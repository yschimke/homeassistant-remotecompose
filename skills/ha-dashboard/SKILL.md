---
name: ha-dashboard
description: Author or edit a Home Assistant Lovelace dashboard for harc, the dashboard viewer for phones, tablets, desktops, launcher widgets and watches. Use when asked to set up, reorganise or extend a dashboard (for example "a garden dashboard with soil moisture charts"), whether working from a harc or Terrazzo edit-session prompt or asked directly by the person.
---

# Home Assistant dashboards for harc

harc shows the dashboards that already live in Home Assistant, drawn in the user's chosen card
style. It never edits automations, devices or helpers. A dashboard edit is a new Lovelace config
for one dashboard; Home Assistant stays the source of truth, and **you never save it yourself**:
you submit drafts to an edit session, and the person previews each one with their real cards and
applies it as themselves ([`docs/architecture/dashboard-editing.md`](../../docs/architecture/dashboard-editing.md)).

## Connecting

Edit sessions are served by the Terrazzo integration (`custom_components/terrazzo`, installed from
HACS) through its MCP server at `https://<home>/api/terrazzo/mcp`. Add it as a remote MCP server;
connector apps sign in with Home Assistant's own OAuth, command-line agents send a long-lived token
of the user the integration's options name as the MCP user. That user can read dashboards and
submit drafts, and — when it isn't an administrator, as it should be — cannot save a dashboard.

Never ask the person for a token to paste into a prompt, and never fall back to Home Assistant's
WebSocket `lovelace/config/save`: a token carries its user's full rights, and a save skips the
person's review.

### With a Terrazzo code (no Home Assistant sign-in)

When the person gives you a short code such as `K7QM-4MD2` (from Settings → **Connect an
agent**, or from one card or dashboard), use the design server's agent relay instead: the MCP
server at `https://ha.coo.ee/api/agent/mcp` (or the design server the invite names). It needs no
header or sign-in. The person watches each session live in the app, sees each draft drawn with
their own cards and states, and talks to you there.

1. `connect` with the code (and your name as `agentName`). A setup code returns a `link`: keep it
   (it lasts 30 days of use) and call `await_request` with it whenever you're free; each request
   arrives with its own `session`. A code from one card or dashboard returns that `session`
   directly. If the person has no code, `request_access`, tell them the code it returns to enter
   under **Have a code from your agent?** (in Settings for a lasting link), and `poll_access`
   until it returns a link or a session. Pass the `session` to every tool below.
2. `get_session`: the request, the dashboard (or card document), its `revision` and the
   conversation so far.
3. `set_status` with a few words while you work; `send_message` to ask a question.
4. `submit_draft` with the `revision` you read, the **complete** `dashboard` (or `document` for a
   card) and a one-line `summary`. A stale revision is refused: read again. `get_render` then
   shows a dashboard draft exactly as the person's app draws it.
5. `await_feedback` with `since` = the last `cursor` (0 at first). It waits up to 25 s; call it
   again until it returns feedback. Comments may name a card in `target`. Act on them, submit a new
   draft, and keep listening until `decision` is `accepted` (you're done) or `rejected`. Then go
   back to `await_request` on your link for the next request.

The person applies the dashboard or publishes the card design themselves; you never can.

## An edit session

Listen first: `await_request` (with `since` = the last `cursor`, 0 at first) waits up to 25 s
and returns the sessions the person starts in the app. While you listen, they start a request
with nothing to copy, and the app tells them you're picking it up. Otherwise a harc or Terrazzo
prompt names the MCP endpoint, a `session_id` and the request. Without one,
call `list_edit_sessions` to find an existing open request and its notes on a visible dashboard. If
there is no relevant request, call `start_edit_session` with the dashboard's `url_path` (from
`list_dashboards`; the default dashboard has none) and the request. The person then sees the
session in the phone app or the panel.

1. `get_edit_session` with the `session_id`: the request, the dashboard as it is now, the latest
   draft and the person's notes.
2. `get_card_guide` for the card types harc draws and how to size them; `find_entities` for the
   home's entities (search by name, area words or domain).
3. `submit_draft` with the **complete** new dashboard config and a one-line `summary`. The
   integration refuses a config without a `views` list or one naming entities the home doesn't
   have, and says which.
4. The person watches the session in harc or the Terrazzo panel and sees each draft at once.
   Use `set_status` while you work and `send_message` to ask them something;
   `get_draft_render` shows the draft exactly as their app draws it. Then call
   `await_feedback` with `since` = the last `cursor` (0 at first): it waits up to 25 s and
   returns their notes, which may name a card in `target`. Act on them, submit again, and keep
   listening until `status` is `applied` or `discarded`. A session expires a day after its last
   change; an applied or discarded one takes no more drafts.

## Rules

- Submit the complete dashboard config. Keep every view, section and card the person didn't ask
  to change.
- Use only entity ids that exist in the home. Never invent entities.
- Prefer card types harc draws natively: tile, entities, glance, gauge, sensor, thermostat,
  humidifier, light, button, entity, history-graph, statistics-graph, statistic,
  weather-forecast, media-control, alarm-panel, area, picture-entity, markdown, heading,
  todo-list, calendar, logbook, map, clock, vertical-stack, horizontal-stack, grid, conditional,
  entity-filter. Other cards (custom/HACS) show as a plain list of their entities.
- Lay views out with `"type": "sections"` and group related cards into sections that start with a
  heading card. harc shows sections in one column on a phone and side by side on wider screens.
- Give every card a short `name` or `title` when the entity's own name is unclear.
- Do not add automations, scripts, scenes or helpers.

These rules match `CARD_GUIDE` in `custom_components/terrazzo/mcp.py`, which `get_card_guide`
returns; change both together.

## Card sizes

In a sections view each section is a 12-column grid. Size a card with
`"grid_options": {"columns": N, "rows": R}`: columns 3, 6, 9 or `"full"`, rows a number of 56 dp
rows or `"auto"`. On a phone a section is about 400 dp wide, so two tiles share a row at 6 columns
each; gauges, graphs, forecasts and lists read best full width. Cards fill a section's rows in
order. harc's phone editor writes sizes the same way (`DashboardDraft` in `ha-client`).

## Looking at harc's card designs

harc's card designs are published as previews in the hosted Compose Preview catalog: the
`compose-preview-catalog` MCP server in this repository's `.mcp.json`
(`https://preview.coo.ee/mcp`, catalog `homeassistant-remotecompose`). Reading it needs a
short-lived grant: `request_access`, show the person the `approveUrl` and `userCode`, then
`poll_access` until approved.

- `catalog_list_previews` with `query: "card-<type>"` (`card-gauge`, `card-tile`,
  `card-thermostat`, `card-history`, …) lists a card type's designs, light and dark.
- `catalog_render_preview` with `observe: "png"` shows one; the `device` override (ids from
  `catalog_list_devices`, such as `id:small_phone` or `id:pixel_fold`) renders it at another width,
  and `catalog_render_matrix` with `axes: {"device": [...]}` compares several widths in one call.

Use them to choose each card's type and size. The person sees your draft drawn with their own
cards and live states before applying it; you see the catalog's images, not their home.
