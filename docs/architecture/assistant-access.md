# Assistant access: AppFunctions and MCP

Two opt-in ways for an AI agent to read and switch a user's Home Assistant through Terrazzo. Both
are off by default, both act only as one already-signed-in user on one instance, and both offer
the same small set of tools with the same rule: locks and alarm panels can be read but never
switched.

| | Android AppFunctions | MCP server |
| --- | --- | --- |
| Lives in | `app/.../assistant/` | `custom_components/terrazzo/mcp.py`, `mcp_view.py` |
| Caller | An on-device agent holding `EXECUTE_APP_FUNCTIONS` | Any MCP client: Claude, ChatGPT, Gemini custom apps, local agents |
| Instance | The one the phone app is signed in to | The Home Assistant serving the integration |
| Identity | The app's own login (refresh token in `TokenVault`) | The one HA user chosen under **Configure → MCP server user** |
| Switch | Settings → Assistants (Android 16+) | Configure → MCP server user (`Off` by default) |

## AppFunctions (phone)

`TerrazzoAppFunctions` is an `@AppFunctionServiceEntryPoint`; the AppFunctions KSP compiler
generates `TerrazzoAppFunctionService` (declared in the manifest) and the
`terrazzo_app_functions.xml` asset from the functions' KDoc. Functions: `findEntities`,
`getEntityState`, `turnOn`, `turnOff`, `toggle`.

- Every function is declared `isEnabled = false`. `AssistantAccess` enables or disables them all
  together through `AppFunctionManager.setAppFunctionEnabled`; the platform keeps that state.
- Calls go through the same path as the launcher widgets ([`launcher-widget-actions.md`](launcher-widget-actions.md)):
  the app's live socket when it is connected, otherwise a short-lived session on a token minted
  from the stored refresh token (`WidgetActionRunner.accessToken`). Signed out means
  `AppFunctionPermissionRequiredException`.
- In demo mode the functions read the demo house and their writes do nothing, so a tester can try
  an agent without a Home Assistant.

**Who can call it today.** Only callers with `EXECUTE_APP_FUNCTIONS`, which Android grants to a
small set of system agents. Google describes the Gemini integration as a private preview with
trusted testers (as of 2026), so an ordinary Play install is not yet reachable by Gemini; the
Claude, ChatGPT and other agent apps cannot call AppFunctions at all. For development:

```sh
adb shell cmd app_function list-app-functions   # Terrazzo's five functions, with enabled state
```

and the AppFunctions Testing Agent in [android/appfunctions](https://github.com/android/appfunctions)
(`agent/`, started with its privileged launch script) executes them on an emulator. The
AppFunctions library is a beta (`androidx.appfunctions` 1.0.0-beta01) and its API is still
moving. Wear OS is not wired: nothing confirms an on-watch agent can reach a watch-side service yet.

## MCP server (Home Assistant integration)

User-facing setup is in [`docs/HOME_ASSISTANT.md`](../HOME_ASSISTANT.md#mcp-server-for-ai-assistants).

- `mcp.py` holds the protocol (stateless Streamable HTTP: one JSON-RPC message per POST, JSON
  responses, no sessions or server-sent events) and the tools, with no Home Assistant imports, so
  `home-assistant/test/mcp_test.py` drives it with a fake home.
- `mcp_view.py` authenticates every request itself rather than through HA's middleware, so it can
  answer MCP's `401` + `WWW-Authenticate: Bearer resource_metadata=…` challenge and accept only
  the pinned user's tokens (`hass.auth.async_validate_access_token`). Wrong tokens go to HA's
  `process_wrong_login`. Cross-origin browser requests are refused (MCP's DNS-rebinding rule).
- Reads filter by the user's `POLICY_READ`; switches check `POLICY_CONTROL` and run
  `homeassistant.turn_on` / `turn_off` / `toggle` with `Context(user_id=…)`, so HA's own entity
  permissions apply. Dashboards marked `require_admin` are hidden from non-administrators.
- The views are registered once (HA can't unregister views) and check the loaded entry's options
  on every request: with no user chosen, or the entry unloaded, they answer `404`.
- The MCP user is set in the options flow only. A `terrazzo:` YAML block, re-imported on every
  start, keeps whatever user the entry already has.
- OAuth discovery hands clients to Home Assistant itself, which publishes RFC 8414 metadata with
  PKCE and client ID metadata documents. The installed test uses login-flow and long-lived
  tokens; a third-party client's OAuth flow is not exercised in CI.

`home-assistant/test/installed-mcp.test.mjs` runs after the panel test in the
**Home Assistant plugin** workflow, against the packaged integration in a real HA container: off
until configured, `401` with the metadata pointer, initialize / tools / switching acting on real
entities, a long-lived token of the pinned user accepted, another user refused, re-pinning, and
`Off` switching it off again.
