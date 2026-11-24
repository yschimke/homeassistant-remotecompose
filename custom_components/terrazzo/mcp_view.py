"""Terrazzo's MCP server: this Home Assistant only, for the one user the owner picked.

Off until **Configure → MCP server user** names a user. From then on `/api/terrazzo/mcp` accepts
Home Assistant access tokens (a long-lived token, or one from HA's own OAuth sign-in) of that user
and of nobody else, and acts with that user's permissions on this instance. There is no address
to point it at another instance, no credential of its own and nothing stored: it is pinned to the
Home Assistant serving it, and removing the user from the option switches it off at once.
"""
import json
import time
from pathlib import Path

from aiohttp import ClientError, ClientTimeout, web
from homeassistant.auth.permissions.const import POLICY_CONTROL, POLICY_READ
from homeassistant.components.http import HomeAssistantView
from homeassistant.components.http.ban import process_wrong_login
from homeassistant.config_entries import ConfigEntryState
from homeassistant.core import Context
from homeassistant.exceptions import HomeAssistantError, Unauthorized
from homeassistant.helpers import device_registry as dr
from homeassistant.helpers import entity_registry as er
from homeassistant.helpers.aiohttp_client import async_get_clientsession

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


def _started(session):
    """A session just opened, with the dashboard it starts from: an agent drafts from this without
    calling get_dashboard or get_edit_session first."""
    result = edits.edit_sessions.summary(session)
    result["dashboard"] = session["baseline"]
    result["next"] = (
        "Draft from dashboard (its current config) and submit_draft the whole new config with "
        "this session_id; no need to read it again."
    )
    return result


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

    def device_info(self, entity_id):
        """The registry facts design matching reads (`DeviceInfo`), or None for an unregistered entity."""
        entry = er.async_get(self._hass).async_get(entity_id)
        if entry is None:
            return None
        device = dr.async_get(self._hass).async_get(entry.device_id) if entry.device_id else None
        return {
            "integration": entry.platform,
            "manufacturer": device.manufacturer if device else None,
            "model": device.model if device else None,
            "translationKey": entry.translation_key,
        }

    async def design_index(self):
        """The card design library's suggestion index, cached a few minutes; None when unreachable.
        The request carries nothing about this home: the matching happens here."""
        cached = self._hass.data.setdefault(DOMAIN, {}).get(_DESIGN_INDEX)
        now = time.monotonic()
        if cached and now - cached[0] < DESIGN_INDEX_FOR:
            return cached[1]
        try:
            async with async_get_clientsession(self._hass).get(
                DESIGN_INDEX_URL, timeout=ClientTimeout(total=10)
            ) as response:
                response.raise_for_status()
                index = await response.json(content_type=None)
        except (ClientError, TimeoutError, ValueError):
            return cached[1] if cached else None
        self._hass.data[DOMAIN][_DESIGN_INDEX] = (now, index)
        return index

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


    def edit_sessions(self):
        try:
            return edits.open_sessions_for_agent(self._hass, self._user)
        except edits.SessionError as failure:
            raise mcp.ToolError(str(failure)) from failure

    async def start_edit_session(self, url_path, request):
        try:
            session = await edits.async_start(self._hass, self._user, url_path, request, answered=True)
        except edits.SessionError as failure:
            raise mcp.ToolError(str(failure)) from failure
        return _started(session)

    async def start_new_dashboard(self, title, request):
        try:
            session = await edits.async_start(
                self._hass, self._user, None, request, {"kind": edits.edit_sessions.NEW_DASHBOARD, "title": title},
                answered=True,
            )
        except edits.SessionError as failure:
            raise mcp.ToolError(str(failure)) from failure
        return _started(session)

    async def edit_session(self, session_id):
        try:
            edits.agent_seen(self._hass, self._user, session_id, "Reading the request")
            return await edits.async_session_for_agent(self._hass, self._user, session_id)
        except edits.SessionError as failure:
            raise mcp.ToolError(str(failure)) from failure

    async def submit_draft(self, session_id, config, summary):
        try:
            draft = edits.submit(self._hass, self._user, session_id, config, summary)
            edits.agent_seen(self._hass, self._user, session_id, "Draft submitted")
            await edits.async_notice_draft(self._hass, session_id, draft)
        except edits.SessionError as failure:
            raise mcp.ToolError(str(failure)) from failure
        return {
            "draft_id": draft["id"],
            "status": "submitted",
            "next": "The person sees this draft now, drawn with their own cards. Call "
            "await_feedback to hear what they think.",
        }

    def draft_render(self, session_id):
        try:
            return edits.draft_render(self._hass, self._user, session_id)
        except edits.SessionError as failure:
            raise mcp.ToolError(str(failure)) from failure

    async def await_request(self, since):
        try:
            return await edits.async_await_request(self._hass, self._user, since)
        except edits.SessionError as failure:
            raise mcp.ToolError(str(failure)) from failure

    async def await_feedback(self, session_id, since):
        try:
            return await edits.async_await_feedback(self._hass, self._user, session_id, since)
        except edits.SessionError as failure:
            raise mcp.ToolError(str(failure)) from failure

    def send_message(self, session_id, text):
        try:
            edits.agent_message(self._hass, self._user, session_id, text)
        except edits.SessionError as failure:
            raise mcp.ToolError(str(failure)) from failure
        return {"status": "sent"}

    def set_status(self, session_id, status):
        try:
            edits.agent_seen(self._hass, self._user, session_id, status)
        except edits.SessionError as failure:
            raise mcp.ToolError(str(failure)) from failure
        return {"status": "shown"}


# The public card design library's suggestion index (what remote-compose-card.js reads), and
# how long a fetched copy is used.
DESIGN_INDEX_URL = "https://ha.coo.ee/designs/v1/suggestions.json"
DESIGN_INDEX_FOR = 600
_DESIGN_INDEX = "design_index"


def _entity(state):
    return {
        "entity_id": state.entity_id,
        "state": state.state,
        "attributes": dict(state.attributes),
        "last_changed": state.last_changed.isoformat(),
    }
