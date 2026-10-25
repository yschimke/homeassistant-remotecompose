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

## An edit session

A harc or Terrazzo prompt names the MCP endpoint, a `session_id` and the request. Without one,
`start_edit_session` with the dashboard's `url_path` (from `list_dashboards`; the default dashboard
has none) and the request opens a session the person then sees in the phone app or the panel.

1. `get_edit_session` with the `session_id`: the request, the dashboard as it is now, the latest
   draft and the person's notes.
2. `get_card_guide` for the card types harc draws and how to size them; `find_entities` for the
   home's entities (search by name, area words or domain).
3. `submit_draft` with the **complete** new dashboard config and a one-line `summary`. The
   integration refuses a config without a `views` list or one naming entities the home doesn't
   have, and says which.
4. Tell the person the draft is ready to review in harc or the Terrazzo panel. If they ask for
   changes, they leave a note: call `get_edit_session` again, read `notes`, and submit a new
   draft. A session expires a day after its last change; an applied or discarded one takes no
   more drafts.

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
