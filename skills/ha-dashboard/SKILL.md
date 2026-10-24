---
name: ha-dashboard
description: Author or edit a Home Assistant Lovelace dashboard for harc, the dashboard viewer for phones, tablets, desktops, launcher widgets and watches. Use when asked to set up, reorganise or extend a dashboard (for example "a garden dashboard with soil moisture charts"), whether working from a harc "Edit with an agent" prompt or directly against a Home Assistant instance.
---

# Home Assistant dashboards for harc

harc shows the dashboards that already live in Home Assistant, drawn in the user's chosen card
style. It never edits automations, devices or helpers. A dashboard edit is a new Lovelace config
for one dashboard; Home Assistant stays the source of truth.

## Rules

- Answer with the complete new dashboard config as one JSON object in a ```json fenced block, and
  nothing else in that block. Keep every view, section and card the user didn't ask to change.
- Use only entity ids that exist in the home. Never invent entities.
- Prefer card types harc draws natively: tile, entities, glance, gauge, sensor, thermostat,
  humidifier, light, button, entity, history-graph, statistics-graph, statistic,
  weather-forecast, media-control, alarm-panel, area, picture-entity, markdown, heading,
  todo-list, calendar, logbook, map, clock, vertical-stack, horizontal-stack, grid, conditional,
  entity-filter. Other cards (custom/HACS) show as a plain list of their entities.
- Lay views out with `"type": "sections"` and group related cards into sections with a heading.
  harc shows sections in one column on a phone and side by side on wider screens.
- Give every card a short `name` or `title` when the entity's own name is unclear.
- Do not add automations, scripts, scenes or helpers.

These rules match `DashboardAgentPrompt.GUIDE` in `ha-client`, the prompt harc builds; change both
together.

## Card sizes

In a sections view each section is a 12-column grid. Size a card with
`"grid_options": {"columns": N, "rows": R}`: columns 3, 6, 9 or `"full"`, rows a number of 56 dp
rows or `"auto"`. On a phone a section is about 400 dp wide, so two tiles share a row at 6 columns
each; gauges, graphs, forecasts and lists read best full width. Cards fill a section's rows in
order. harc's dashboard editor writes sizes the same way (`DashboardDraft` in `ha-client`).

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

Use them to choose each card's type and size. These match `DashboardAgentPrompt.SIZING` and
`DashboardAgentPrompt.DESIGN_TOOLS`, which the editor's **Populate with an agent** prompt carries.

## From a harc prompt

harc's **Edit with an agent** screen, and the dashboard editor's **Populate with an agent**, copy a
prompt holding these rules, the request, the
dashboard's current config and a table of the home's entities (`entity_id | name | state | unit |
device_class`). Answer with the JSON block. The user pastes the answer back into harc, which checks that it is an
object with a `views` list. **Edit with an agent** asks before replacing the dashboard and offers one
undo; the editor loads the answer as an unsaved draft the user arranges before saving.

## Directly against Home Assistant

With a long-lived access token, use Home Assistant's WebSocket API (`/api/websocket`):

1. `{"type": "lovelace/dashboards/list"}` to find the dashboard's `url_path` (the default dashboard
   has none).
2. `{"type": "lovelace/config", "url_path": "<path>"}` to read its current config, and
   `{"type": "get_states"}` for the entities.
3. Keep the config you read: it is the undo.
4. `{"type": "lovelace/config/save", "url_path": "<path>", "config": {...}}` to save the new one.
   Home Assistant refuses YAML-mode dashboards and users without admin rights; report that rather
   than working around it.
