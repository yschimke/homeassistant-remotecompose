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

## From a harc prompt

harc's **Edit with an agent** screen copies a prompt holding these rules, the request, the
dashboard's current config and a table of the home's entities (`entity_id | name | state | unit |
device_class`). Answer with the JSON block. The user pastes the answer back into harc, which checks
that it is an object with a `views` list, asks before replacing the dashboard, and offers one undo.

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
