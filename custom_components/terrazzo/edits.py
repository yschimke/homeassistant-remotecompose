"""Edit sessions in Home Assistant: where they are kept, the dashboards they change, and the
WebSocket commands the phone app and the panel use (docs/architecture/dashboard-editing.md).

Every command runs as the signed-in user of the connection it arrives on: anyone who can see a
dashboard can start a session on it, read it and leave notes; only an administrator can apply or
undo a draft, which is Home Assistant's own rule for saving a dashboard. The MCP server reaches
the same sessions as its pinned user (`mcp_view.py`).
"""
import copy
import time

import voluptuous as vol
from homeassistant.auth.permissions.const import POLICY_READ
from homeassistant.components import websocket_api
from homeassistant.core import callback
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.dispatcher import async_dispatcher_connect, async_dispatcher_send
from homeassistant.helpers.storage import Store

from . import edit_sessions
from .const import DOMAIN, MCP_URL
from .edit_sessions import Conflict, SessionError

STORAGE_KEY = f"{DOMAIN}.edit_sessions"
STORAGE_VERSION = 1
SIGNAL_SESSION = f"{DOMAIN}_edit_session"


class Edits:
    """This home's edit sessions, saved to `.storage/terrazzo.edit_sessions` as they change."""

    def __init__(self, hass, store, sessions):
        self.hass = hass
        self._store = store
        self.sessions = sessions

    @classmethod
    async def async_load(cls, hass):
        store = Store(hass, STORAGE_VERSION, STORAGE_KEY)
        data = await store.async_load() or {}
        return cls(hass, store, edit_sessions.EditSessions(data.get("sessions")))

    @callback
    def changed(self, session):
        self._store.async_delay_save(lambda: {"sessions": self.sessions.to_data()}, 1)
        async_dispatcher_send(self.hass, SIGNAL_SESSION, session["id"])


def edits_of(hass):
    edits = hass.data.get(DOMAIN, {}).get("edits")
    if edits is None:
        raise SessionError("Terrazzo isn't set up")
    return edits


def visible_dashboards(hass, user):
    """Lovelace's dashboards by url_path (None for Overview) that [user] may see. Lovelace's data
    shape differs across Home Assistant releases."""
    data = hass.data.get("lovelace")
    dashboards = getattr(data, "dashboards", None)
    if dashboards is None and isinstance(data, dict):
        dashboards = data.get("dashboards")
    visible = {}
    for url_path, dashboard in (dashboards or {}).items():
        config = getattr(dashboard, "config", None) or {}
        if isinstance(config, dict) and config.get("require_admin") and not user.is_admin:
            continue
        visible[None if url_path in (None, "lovelace") else url_path] = (dashboard, config)
    return visible


def dashboard_for(hass, user, url_path):
    found = visible_dashboards(hass, user).get(url_path or None)
    if found is None:
        raise SessionError(f"No dashboard {url_path or 'Overview'}")
    return found[0]


async def current_config(dashboard, url_path):
    try:
        return await dashboard.async_load(False)
    except HomeAssistantError as failure:
        # ConfigNotFound: Home Assistant generates this dashboard; it has no saved cards.
        raise SessionError(
            f"Dashboard {url_path or 'Overview'} has no saved configuration to edit"
        ) from failure


def mcp_url(hass):
    """The MCP endpoint as an agent outside the home should reach it, when HA knows its URL."""
    try:
        from homeassistant.helpers.network import NoURLAvailableError, get_url

        try:
            return get_url(hass, prefer_external=True) + MCP_URL
        except NoURLAvailableError:
            return MCP_URL
    except ImportError:
        return MCP_URL


def _now():
    return time.time()


def _visible_session(hass, user, session_id):
    """A session, if [user] may see the dashboard it edits."""
    edits = edits_of(hass)
    session = edits.sessions.get(session_id, _now())
    dashboard_for(hass, user, session["url_path"])
    return edits, session


async def async_start(hass, user, url_path, request):
    edits = edits_of(hass)
    baseline = await current_config(dashboard_for(hass, user, url_path), url_path)
    session = edits.sessions.create(url_path or None, request, baseline, user.id, _now())
    edits.changed(session)
    return session


async def async_session_for_agent(hass, user, session_id):
    """What an agent reads: the session without its stored configs, the dashboard as it is now,
    and the newest draft."""
    _, session = _visible_session(hass, user, session_id)
    dashboard = dashboard_for(hass, user, session["url_path"])
    result = edit_sessions.summary(session)
    result["dashboard"] = await current_config(dashboard, session["url_path"])
    result["latest_draft"] = session["drafts"][-1]["config"] if session["drafts"] else None
    return result


def submit(hass, user, session_id, config, summary):
    edits, _ = _visible_session(hass, user, session_id)
    draft = edits.sessions.submit(
        session_id,
        config,
        summary,
        # Only entities this user may read count as existing: a draft can't probe for others.
        lambda entity_id: hass.states.get(entity_id) is not None
        and user.permissions.check_entity(entity_id, POLICY_READ),
        user.id,
        _now(),
    )
    session = edits.sessions.get(session_id, _now())
    edits.changed(session)
    return draft


async def async_apply(hass, user, session_id, draft_id):
    """Save [draft_id] as the dashboard, only if it still holds the session's starting config.

    The check and the save have no await between them once the dashboard is loaded (Lovelace's
    storage writes its in-memory config before it awaits the file), so no other save can land in
    between: unlike a client, which can only read and then write.
    """
    if not user.is_admin:
        raise SessionError("Only an administrator can apply a dashboard draft")
    edits, session = _visible_session(hass, user, session_id)
    dashboard = dashboard_for(hass, user, session["url_path"])
    if getattr(dashboard, "mode", None) != "storage":
        raise SessionError("This dashboard is a YAML file; copy the draft into the file instead")
    current = await current_config(dashboard, session["url_path"])
    config = edits.sessions.check_apply(session_id, draft_id, current, _now())
    await dashboard.async_save(copy.deepcopy(config))
    edits.changed(edits.sessions.applied(session_id, draft_id, user.id, _now()))
    return edits.sessions.get(session_id, _now())


async def async_undo(hass, user, session_id):
    """Restore the dashboard the session started from, if it still holds the applied draft."""
    if not user.is_admin:
        raise SessionError("Only an administrator can undo an applied draft")
    edits, session = _visible_session(hass, user, session_id)
    dashboard = dashboard_for(hass, user, session["url_path"])
    current = await current_config(dashboard, session["url_path"])
    baseline = edits.sessions.check_undo(session_id, current, _now())
    await dashboard.async_save(copy.deepcopy(baseline))
    edits.changed(edits.sessions.undone(session_id, _now()))
    return edits.sessions.get(session_id, _now())


# --- WebSocket commands ---------------------------------------------------------------------


def _send_failure(connection, msg, failure):
    code = "conflict" if isinstance(failure, Conflict) else "invalid"
    connection.send_error(msg["id"], code, str(failure))


def _session_reply(session, hass=None):
    """The session with its configs; with [hass], also the prompt to hand an agent again."""
    reply = {"session": edit_sessions.summary(session, with_configs=True)}
    if hass is not None:
        reply["prompt"] = edit_sessions.agent_prompt(
            session, mcp_url(hass), hass.config.location_name
        )
    return reply


@websocket_api.websocket_command(
    {
        vol.Required("type"): "terrazzo/edit/start",
        vol.Optional("url_path"): vol.Any(None, str),
        vol.Optional("request"): str,
    }
)
@websocket_api.async_response
async def ws_start(hass, connection, msg):
    try:
        session = await async_start(hass, connection.user, msg.get("url_path"), msg.get("request"))
    except SessionError as failure:
        _send_failure(connection, msg, failure)
        return
    connection.send_result(msg["id"], _session_reply(session, hass))


@websocket_api.websocket_command(
    {vol.Required("type"): "terrazzo/edit/list", vol.Optional("url_path"): vol.Any(None, str)}
)
@callback
def ws_list(hass, connection, msg):
    try:
        edits = edits_of(hass)
        dashboard_for(hass, connection.user, msg.get("url_path"))
    except SessionError as failure:
        _send_failure(connection, msg, failure)
        return
    sessions = edits.sessions.for_dashboard(msg.get("url_path") or None, _now())
    connection.send_result(msg["id"], {"sessions": [edit_sessions.summary(s) for s in sessions]})


@websocket_api.websocket_command(
    {vol.Required("type"): "terrazzo/edit/get", vol.Required("session_id"): str}
)
@callback
def ws_get(hass, connection, msg):
    try:
        _, session = _visible_session(hass, connection.user, msg["session_id"])
    except SessionError as failure:
        _send_failure(connection, msg, failure)
        return
    connection.send_result(msg["id"], _session_reply(session, hass))


@websocket_api.websocket_command(
    {vol.Required("type"): "terrazzo/edit/subscribe", vol.Required("session_id"): str}
)
@callback
def ws_subscribe(hass, connection, msg):
    """The session now and after every change, until the subscription ends."""
    session_id = msg["session_id"]
    try:
        _, session = _visible_session(hass, connection.user, session_id)
    except SessionError as failure:
        _send_failure(connection, msg, failure)
        return

    @callback
    def forward(changed_id):
        if changed_id != session_id:
            return
        try:
            _, current = _visible_session(hass, connection.user, session_id)
        except SessionError:
            return
        connection.send_message(websocket_api.event_message(msg["id"], _session_reply(current)))

    connection.subscriptions[msg["id"]] = async_dispatcher_connect(hass, SIGNAL_SESSION, forward)
    connection.send_result(msg["id"])
    connection.send_message(websocket_api.event_message(msg["id"], _session_reply(session)))


@websocket_api.websocket_command(
    {
        vol.Required("type"): "terrazzo/edit/note",
        vol.Required("session_id"): str,
        vol.Required("text"): str,
    }
)
@callback
def ws_note(hass, connection, msg):
    try:
        edits, _ = _visible_session(hass, connection.user, msg["session_id"])
        edits.sessions.note(msg["session_id"], msg["text"], connection.user.id, _now())
        session = edits.sessions.get(msg["session_id"], _now())
    except SessionError as failure:
        _send_failure(connection, msg, failure)
        return
    edits.changed(session)
    connection.send_result(msg["id"], _session_reply(session))


@websocket_api.websocket_command(
    {vol.Required("type"): "terrazzo/edit/discard", vol.Required("session_id"): str}
)
@callback
def ws_discard(hass, connection, msg):
    try:
        edits, _ = _visible_session(hass, connection.user, msg["session_id"])
        session = edits.sessions.discard(msg["session_id"], connection.user.id, _now())
    except SessionError as failure:
        _send_failure(connection, msg, failure)
        return
    edits.changed(session)
    connection.send_result(msg["id"], _session_reply(session))


@websocket_api.require_admin
@websocket_api.websocket_command(
    {
        vol.Required("type"): "terrazzo/edit/apply",
        vol.Required("session_id"): str,
        vol.Required("draft_id"): str,
    }
)
@websocket_api.async_response
async def ws_apply(hass, connection, msg):
    try:
        session = await async_apply(hass, connection.user, msg["session_id"], msg["draft_id"])
    except SessionError as failure:
        _send_failure(connection, msg, failure)
        return
    connection.send_result(msg["id"], _session_reply(session))


@websocket_api.require_admin
@websocket_api.websocket_command(
    {vol.Required("type"): "terrazzo/edit/undo", vol.Required("session_id"): str}
)
@websocket_api.async_response
async def ws_undo(hass, connection, msg):
    try:
        session = await async_undo(hass, connection.user, msg["session_id"])
    except SessionError as failure:
        _send_failure(connection, msg, failure)
        return
    connection.send_result(msg["id"], _session_reply(session))


COMMANDS = (ws_start, ws_list, ws_get, ws_subscribe, ws_note, ws_discard, ws_apply, ws_undo)


def async_register(hass):
    for command in COMMANDS:
        websocket_api.async_register_command(hass, command)
