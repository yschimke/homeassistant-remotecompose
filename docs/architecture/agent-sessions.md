# Live agent sessions

How a person and an AI agent edit a card or a dashboard together: the agent joins with a short
code, the person sees who is connected and what it is doing, each draft appears by itself drawn
with their own cards and live states, and they talk until the person accepts or discards. It works
the same in the Android app, the desktop app and the browser (the Wasm app and its Wasmo install),
whatever sits in front of Home Assistant.

## Why a relay

| Constraint | Consequence |
| --- | --- |
| A cloud agent usually can't reach Home Assistant: local-only installs, reverse proxies, Nabu Casa, ingress | Nothing may depend on an inbound connection to HA |
| HA tokens are not scoped | No agent ever gets one; it submits drafts, a person applies them |
| The phone is often in the background, and a browser tab can reload | The person's side must pick a session back up |
| Proxies cut long requests at about 60–100 s and buffer server-sent events | Long-polls are held for at most 25 s; no SSE, no WebSockets to the relay |
| MCP is request/response | An agent waits for the person with a long-polling tool (`await_feedback`) |

So the design server (`ha.coo.ee`) is a rendezvous point: the agent and the person's app both
connect *out* to it. Only the person's app talks to Home Assistant, over the connection it
already has.

```
Agent (Claude, Codex, …) ──MCP──▶ design server relay ◀──HTTPS long-poll── person's app ──WS──▶ Home Assistant
                                  drafts, code, presence,                  (Android, desktop,      checks and
                                  conversation, decision                    Wasm, Wasmo)            applies drafts
```

## The flow

1. **Start.** In Card Studio (**With a cloud agent**) or **Ask an agent → Use a cloud agent** on a
   dashboard, the person writes a request. The app sends the card's preview contract or the
   dashboard's configuration to the design server and gets back an owner capability and a join
   code such as `K7QM-4MD2`.
2. **Connect.** The person gives the agent the code. **Copy invite for the agent** copies one line
   with the code and the MCP address; nothing else needs pasting. An agent that already has the
   Terrazzo MCP server configured needs only the code. The other direction works too: an agent
   calls `request_access`, shows the person a code, and the person enters it under **Have a code
   from your agent?**.
3. **Presence.** The panel shows "Claude · Drafting a compact layout" from the agent's tool calls
   and `set_status`, "was here 3 min ago" when it's quiet, or "Waiting for an agent to join".
4. **Drafts arrive.** The app long-polls the relay, so a submitted draft shows up within moments,
   with no **Check for draft**. A dashboard draft is relayed into Home Assistant's edit session
   (`terrazzo/edit/submit`) as the signed-in person and drawn with their real cards; a card draft
   is loaded with its images, checked against the card's contract, and is ready to review in UI
   Builder.
5. **Talk.** The person types in the panel. On a dashboard draft each card has a **Comment**
   button that pins the next message to that card (its title or entity, and where it sits). The
   agent, waiting in `await_feedback`, gets the message at once, and can `send_message` back.
6. **Decide.** **Apply** (dashboard) or **Publish privately and use it** (card) does what it did
   before and also tells the agent `accepted`. **Discard** or **Stop and revoke access** ends the
   session for both sides.

## Lifetimes and storage

- A join code is single use and lasts 15 minutes; **New code** replaces it. An agent's
  `request_access` code lasts 10 minutes.
- A session ends an hour after its last activity (either side), never more than a day after it
  started, on revocation, or when the design server restarts (sessions are in memory).
- The owner capability is kept in the browser's `localStorage` (memory on Android and desktop,
  whose process outlives the screen), so a reload resumes the session. It is never written to
  Wasmo's synced settings: a capability is a secret for this device.
- Wrong codes share one budget: after 30 failures in a minute every redemption waits, which keeps
  eight-character codes (31 symbols, no `0/O/1/I/L`) unguessable in practice.

## Interfaces

**Agent (MCP)** — `POST /api/agent/mcp`, stateless JSON-RPC, no header needed; every tool after
joining takes the `session` argument (a `Bearer` header works too). Browser origins are refused.

| Tool | Does |
| --- | --- |
| `join_session(code, agentName?)` | Redeem the person's code; returns `session` |
| `request_access(agentName?)`, `poll_access(requestToken)` | Ask first; the person enters the code; `poll_access` waits up to 25 s |
| `get_session(session)` | Request, card document or dashboard, `revision`, conversation, `cursor` |
| `set_status(session, status)` | What the person sees the agent doing |
| `send_message(session, text)` | A message in the person's panel |
| `submit_draft(session, revision, document \| dashboard, summary?)` | Validated by the per-kind gate (card contract, dashboard shape) |
| `put_card_asset(session, key, imageBase64)` | Card images, as before |
| `await_feedback(session, since)` | Waits up to 25 s for comments or a decision after `since` |

`POST /api/agent/join {code}` trades a code for the session for agents without MCP; they then use
the existing per-kind JSON routes (`/api/studio/agent-session`, `/api/studio/dashboard-session`).
The per-kind MCP endpoints (`/api/studio/agent-mcp`, `/api/studio/dashboard-mcp`) keep working
with a pasted capability, and also count as presence.

**Person (HTTPS, CORS `*`, owner capability as bearer)**

| Route | Does |
| --- | --- |
| `GET /api/agent/session?since=<version>` | State now, or held until it changes past `version` (25 s at most) |
| `POST /api/agent/session/join-code` | A fresh code |
| `POST /api/agent/claim {code}` | Let in the agent that asked first |
| `POST /api/agent/session/comment {text, target?}` | A message to the agent; on a dashboard also a session note |
| `POST /api/agent/session/decision {decision}` | `accepted` or `rejected` |
| `DELETE /api/agent/session` | Revoke |

Session creation is unchanged (`POST /api/studio/agent-sessions`, `POST
/api/studio/dashboard-sessions`); a server with the relay adds `joinCode` to the answer, and a
client that sees no code falls back to the pasted-prompt flow.

| Piece | Where |
| --- | --- |
| Relay: codes, presence, conversation, long-polls, sliding expiry | `design-server/.../AgentRelay.kt` |
| MCP and HTTP routes | `AgentRelayMcp.kt`, `AgentRelayRoutes.kt` |
| Drafts and their validation (unchanged) | `AgentDesignSessions.kt`, `DashboardAgentSessions.kt` |
| Client | `ha-client/.../agent/AgentRelayClient.kt`, `CloudDashboardAgentEdits` |
| Panel (both screens, every host) | `app-ui/.../shared/AgentLivePanel.kt`; `CardStudioScreen`, `AgentEditScreen` |
| Tests | `AgentRelayTest`, `DashboardRelayTest` (code → join → draft → HA → comment → accept), `AgentLivePanelTest` |

## Not yet

- **Agents signed in to Home Assistant directly** (the integration's own MCP server) still use
  notes and `get_edit_session`: an `await_feedback` tool there would give them the same loop.
- **The integration as the owner.** Holding the relay link in the always-on integration would let
  a session survive the phone sleeping, with an FCM wake through `push-relay` when a draft arrives.
- **Renders for the agent.** The app could upload the draft as it draws it, so the agent sees
  exactly what the person sees rather than catalog images.
- **One session for a card and its dashboard.** Card and dashboard sessions share the relay and
  the MCP tools but are still started separately, and accepted separately.
- **Durable sessions.** They are in memory: a design server restart ends them, and the app says so.
