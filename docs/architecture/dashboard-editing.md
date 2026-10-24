# Dashboard editing across mobile, web and agents

One model for changing a Home Assistant dashboard from the phone, from the web panel, or by an AI
agent. Home Assistant stays the source of truth and every write goes through it with the
permissions of the person who made it.

## Principles

- **The phone does light edits only.** Reordering, resizing, swapping a card's template and
  deleting work on the live dashboard with nothing installed. Anything more is the panel's job,
  and the phone says so rather than squeezing a full editor onto a small screen.
- **Anything further needs the Terrazzo integration** (`custom_components/terrazzo`, installed
  from HACS). It hosts the web panel and the MCP endpoint, and holds agent edit sessions.
- **No new credentials.** Each caller signs in the way it already does (below); nobody hands a
  general-purpose token to an agent.
- **An agent never saves.** It submits drafts; a person previews them with real cards and applies
  them with their own session.

## Who signs in how

Home Assistant access tokens are not scoped: any token, long-lived or not, carries its user's full
rights. An administrator's long-lived token given to an agent could change automations, users and
integrations, not just dashboards. So the plan gives no caller a token it doesn't already hold:

| Caller | Signs in with | Can save dashboards |
| --- | --- | --- |
| Phone app | Its own HA OAuth refresh token (`TokenVault`) | If the user is an admin and write mode is on |
| Web panel (`/terrazzo`) | The signed-in user's HA frontend session ([HOME_ASSISTANT.md](../HOME_ASSISTANT.md#authorization-and-lifecycle)) | If the user is an admin |
| Agent | The integration's MCP endpoint, as its pinned user ([assistant-access.md](assistant-access.md)): HA's own OAuth for connector apps, a long-lived token of that user for command-line agents | Never: drafts only |

Home Assistant refuses `lovelace/config/save` and `lovelace/dashboards/create` from
non-administrators, so a dedicated non-admin MCP user can read dashboards and submit drafts but
cannot write one. The person reviewing applies it as themselves. The hosted studio at `ha.coo.ee`
signs in to HA separately; it stays the demo and fixture editor, and the panel inside Home
Assistant is the editor in practice.

## What each surface does

| | Without the integration | With the integration |
| --- | --- | --- |
| Phone | Light edits on the live dashboard; **Open in Home Assistant** for HA's own editor; an **Install with HACS** card | Light edits; **Ask an agent** (start an edit session, share its prompt); preview, apply or discard agent drafts |
| Web panel | — | Full editor: add cards, sections, views and dashboards, card settings; start sessions; preview drafts side by side |
| Agent | — (no supported path) | MCP: read dashboards and entities, read the card guide, submit drafts to an edit session |

## Edit sessions

An edit session is the handoff between a person and an agent, held by the integration in Home
Assistant's storage:

1. **Start.** "Ask an agent" in the phone or panel asks the integration for a session: the
   dashboard, the request, the config it starts from, who started it, and an expiry. The prompt
   the person copies or shares holds only the MCP endpoint, the session id and the request: no
   token, no dashboard config, no entity list. The agent reads those over its own authenticated
   MCP connection. A connected agent can also start a session itself.
2. **Draft.** The agent reads the session and the dashboard, uses the card guide (the types harc
   draws, HA's section-grid sizes, the published catalog's preview images), and submits a draft
   with a summary. The integration checks that the draft is a dashboard whose entities exist.
3. **Preview.** The phone and the panel watch the session. A draft appears drawn with the user's
   real cards and live states — the same renderer as the dashboard — so the user sees exactly
   what applying it would show. "Request changes" stores a note the agent reads on its next call
   (MCP here is request/response: the agent is not pushed to).
4. **Apply.** An administrator applies the draft from the phone or panel with their own session.
   The integration saves it inside Home Assistant only if the dashboard still holds the config the
   session started from, which it can check and write without another client's save in between —
   unlike a client, which can only read and then write. One undo is kept. YAML dashboards stay
   draft-only.

## Phases

1. **Phone, light editing** — done.
   - The editor (`DashboardEditorScreen`) selects a card to move it (within or between
     sections), resize it on HA's section grid (toolbar or corner drag), swap its template, or
     delete it; sections move up and down. Save is conditional; one undo.
   - **Template** swaps a card showing one entity to another type harc draws for that entity's
     domain (`DashboardDraft.templateChoices` / `withType`), keeping its entity, name, icon, size,
     visibility and actions and dropping keys specific to the old type.
   - Removed from the phone: adding cards, sections and views, card settings and JSON, New
     dashboard, and the copy-paste agent flows (**Edit with an agent**, **Populate with an
     agent**).
   - The editor ends with a card pointing to Home Assistant's own editor and, when
     `get_config`'s `components` lacks `terrazzo`, to the integration's HACS page.
2. **Integration, edit sessions.** Session storage; WebSocket commands to create, watch, apply and
   undo (apply admin-only, conditional and in-process); MCP tools to read a session, read the card
   guide and submit a draft. Tests: `home-assistant/test/mcp_test.py` with the fake home, and the
   installed-integration test against a real Home Assistant, including the admin refusal for a
   non-admin save.
3. **Panel editor and draft preview.** The editor screens move to the shared UI module (`app-ui`)
   so the panel, desktop and phone share one editor and one draft preview; the phone build shows
   only the light edits. `DashboardDraft` (`ha-client`) already holds the add / remove / view
   operations the panel needs.
4. **Agent guidance.** `skills/ha-dashboard/SKILL.md` and `DashboardAgentPrompt` are rewritten
   around MCP edit sessions; the long-lived-token WebSocket path is dropped.

Rendered previews of a draft *for the agent* wait for a server-side renderer (the add-on server's
`.rc` endpoint is a placeholder): until then the agent gets the published catalog images, and the
person sees the real render while reviewing.
