"""Terrazzo's MCP server: this Home Assistant only, for the one user the owner picked.

Off until **Configure → MCP server user** names a user. From then on `/api/terrazzo/mcp` accepts
Home Assistant access tokens (a long-lived token, or one from HA's own OAuth sign-in) of that user
and of nobody else, and acts with that user's permissions on this instance. There is no address
to point it at another instance, no credential of its own and nothing stored: it is pinned to the
Home Assistant serving it, and removing the user from the option switches it off at once.
"""
import json
from pathlib import Path

from aiohttp import web
from homeassistant.auth.permissions.const import POLICY_CONTROL, POLICY_READ
from homeassistant.components.http import HomeAssistantView
from homeassistant.components.http.ban import process_wrong_login
from homeassistant.config_entries import ConfigEntryState
from homeassistant.core import Context
from homeassistant.exceptions import HomeAssistantError, Unauthorized

from . import edits, mcp
from .const import CONF_MCP_USER, DOMAIN, MCP_METADATA_URL, MCP_URL, PANEL_URL_PATH

SERVER_VERSION = json.loads((Path(__file__).parent / "manifest.json").read_text())["version"]


def _pinned_user_id(hass):
    """The user the MCP server serves, or None while it is off (or Terrazzo isn't loaded)."""
    for entry in hass.config_entries.async_entries(DOMAIN):
        if entry.state is ConfigEntryState.LOADED:
            return entry.options.get(CONF_MCP_USER) or None
    return None


def _origin(request):
    return f"{request.url.scheme}://{request.url.host}" + (
        f":{request.url.port}" if request.url.port and not request.url.is_default_port() else ""
    )


def _json(body, status=200, headers=None):
    return web.json_response(body, status=status, headers=headers)


class TerrazzoMcpView(HomeAssistantView):
    """MCP's Streamable HTTP transport, stateless: one JSON-RPC message per POST."""

    url = MCP_URL
    name = "api:terrazzo:mcp"
    # Authenticated here instead of by HA's middleware, so a refusal can carry MCP's
    # `WWW-Authenticate` pointer to the OAuth metadata and only the pinned user gets through.
    requires_auth = False

    def __init__(self, hass):
        self.hass = hass

    async def post(self, request):
        hass = self.hass
        pinned = _pinned_user_id(hass)
        if pinned is None:
            return _json({"error": "Terrazzo's MCP server is off"}, status=404)
        # MCP's DNS-rebinding rule: a browser page may only call from this instance's own origin.
        if (origin := request.headers.get("Origin")) and origin != _origin(request):
            return _json({"error": "Cross-origin MCP requests are refused"}, status=403)
        user = self._user(request)
        if user is None:
            if request.headers.get("Authorization"):
                # A wrong token counts towards HA's own login-attempt ban, as on its other APIs.
                await process_wrong_login(request)
            metadata = f"{_origin(request)}{MCP_METADATA_URL}"
            return _json(
                {"error": "A Home Assistant access token is required"},
                status=401,
                headers={"WWW-Authenticate": f'Bearer resource_metadata="{metadata}"'},
            )
        if user.id != pinned or not user.is_active:
            return _json({"error": "This MCP server is pinned to another Home Assistant user"}, status=403)
        try:
            message = await request.json()
        except ValueError:
            return _json(
                {"jsonrpc": "2.0", "id": None, "error": {"code": mcp.PARSE_ERROR, "message": "Invalid JSON"}},
                status=400,
            )
        if isinstance(message, list):
            return _json(
                {"jsonrpc": "2.0", "id": None,
                 "error": {"code": mcp.INVALID_REQUEST, "message": "Batches aren't supported"}},
                status=400,
            )
        response = await mcp.handle(message, HassHome(hass, user, _origin(request)), SERVER_VERSION)
        if response is None:
            return web.Response(status=202)
        return _json(response)

    async def get(self, request):
        # No server-initiated stream: every answer comes back on its POST.
        return web.Response(status=405, headers={"Allow": "POST"})

    async def delete(self, request):
        return web.Response(status=405, headers={"Allow": "POST"})

    def _user(self, request):
        header = request.headers.get("Authorization", "")
        scheme, _, token = header.partition(" ")
        if scheme.lower() != "bearer" or not token:
            return None
        refresh_token = self.hass.auth.async_validate_access_token(token.strip())
        return refresh_token.user if refresh_token is not None else None


class TerrazzoMcpMetadataView(HomeAssistantView):
    """OAuth protected-resource metadata (RFC 9728): Home Assistant itself issues the tokens."""

    url = MCP_METADATA_URL
    name = "api:terrazzo:mcp:metadata"
    requires_auth = False

    def __init__(self, hass):
        self.hass = hass

    async def get(self, request):
        if _pinned_user_id(self.hass) is None:
            return _json({"error": "Terrazzo's MCP server is off"}, status=404)
        base = _origin(request)
        return _json(
            {
                "resource": f"{base}{MCP_URL}",
                "authorization_servers": [base],
                "bearer_methods_supported": ["header"],
                "resource_name": f"Terrazzo for {self.hass.config.location_name}",
            }
        )


class HassHome:
    """What the MCP tools may see and do: this instance, through one user's permissions."""

    def __init__(self, hass, user, base_url):
        self._hass = hass
        self._user = user
        self._base_url = base_url
        self.name = hass.config.location_name

    def _can(self, entity_id, policy):
        return self._user.permissions.check_entity(entity_id, policy)

    def states(self):
        return [
            _entity(state)
            for state in self._hass.states.async_all()
            if self._can(state.entity_id, POLICY_READ)
        ]

    def state(self, entity_id):
        state = self._hass.states.get(entity_id)
        if state is None or not self._can(entity_id, POLICY_READ):
            return None
        return _entity(state)

    async def switch(self, service, entity_id):
        if not self._can(entity_id, POLICY_CONTROL):
            raise mcp.Unauthorized(f"Your Home Assistant user may not control {entity_id}")
        try:
            # `homeassistant.*` dispatches to the entity's own domain, scenes and scripts included.
            await self._hass.services.async_call(
                "homeassistant",
                service,
                {"entity_id": entity_id},
                blocking=True,
                context=Context(user_id=self._user.id),
            )
        except Unauthorized as failure:
            raise mcp.Unauthorized(f"Your Home Assistant user may not control {entity_id}") from failure
        except HomeAssistantError as failure:
            raise mcp.ToolError(f"Home Assistant refused {service} for {entity_id}: {failure}") from failure

    def _dashboards(self):
        return edits.visible_dashboards(self._hass, self._user)

    def dashboards(self):
        listed = []
        for url_path, (_, config) in self._dashboards().items():
            title = config.get("title") if isinstance(config, dict) else None
            listed.append(
                {
                    "url_path": url_path,
                    "title": title or ("Overview" if url_path is None else url_path),
                    "terrazzo_url": f"{self._base_url}/{PANEL_URL_PATH}?dashboard={url_path or ''}",
                }
            )
        return sorted(listed, key=lambda item: (item["url_path"] is not None, item["title"].casefold()))

    async def dashboard(self, url_path):
        found = self._dashboards().get(url_path)
        if found is None:
            raise mcp.ToolError(f"No dashboard {url_path or 'Overview'}; use list_dashboards")
        try:
            return await found[0].async_load(False)
        except HomeAssistantError as failure:
            # ConfigNotFound: Home Assistant generates this dashboard; it has no saved cards.
            raise mcp.ToolError(
                f"Dashboard {url_path or 'Overview'} has no saved configuration: {failure}"
            ) from failure


    async def start_edit_session(self, url_path, request):
        try:
            session = await edits.async_start(self._hass, self._user, url_path, request)
        except edits.SessionError as failure:
            raise mcp.ToolError(str(failure)) from failure
        return edits.edit_sessions.summary(session)

    async def edit_session(self, session_id):
        try:
            return await edits.async_session_for_agent(self._hass, self._user, session_id)
        except edits.SessionError as failure:
            raise mcp.ToolError(str(failure)) from failure

    async def submit_draft(self, session_id, config, summary):
        try:
            draft = edits.submit(self._hass, self._user, session_id, config, summary)
        except edits.SessionError as failure:
            raise mcp.ToolError(str(failure)) from failure
        return {
            "draft_id": draft["id"],
            "status": "submitted",
            "next": "A person previews this draft and applies it. Call get_edit_session later to "
            "read their notes if they ask for changes.",
        }


def _entity(state):
    return {
        "entity_id": state.entity_id,
        "state": state.state,
        "attributes": dict(state.attributes),
        "last_changed": state.last_changed.isoformat(),
    }
