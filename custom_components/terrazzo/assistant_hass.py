"""The built-in dashboard agent in Home Assistant: when it runs, as whom, and how it reaches its
model (assistant.py has the turn itself).

It runs while the integration's options name a provider and hold an API key. Every edit session
then gets an answer from it: a new session a greeting, each note the person leaves one turn. A
turn acts as the person who started the session, with their permissions only, through the same
home the MCP server uses (`mcp_view.HassHome`). The key stays in Home Assistant: clients learn only
whether the agent is on and which model it uses (`terrazzo/agent/info`).
"""
import copy
import logging

import voluptuous as vol
from aiohttp import ClientError, ClientTimeout
from homeassistant.auth.permissions.const import POLICY_READ
from homeassistant.components import websocket_api
from homeassistant.config_entries import ConfigEntryState
from homeassistant.core import callback
from homeassistant.helpers import area_registry as ar
from homeassistant.helpers import device_registry as dr
from homeassistant.helpers import entity_registry as er
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.dispatcher import async_dispatcher_connect

from . import assistant, edit_sessions, edits
from .const import (
    CONF_AGENT_API_KEY,
    CONF_AGENT_BASE_URL,
    CONF_AGENT_MODEL,
    CONF_AGENT_PROVIDER,
    DOMAIN,
)
from .mcp_view import HassHome

_LOGGER = logging.getLogger(__name__)

MODEL_TIMEOUT = ClientTimeout(total=120)
# What home_overview lists: enough to plan a dashboard, bounded for a large home.
OVERVIEW_PER_AREA = 60
OVERVIEW_UNASSIGNED = 80


def agent_config(hass):
    """The built-in agent's settings from the loaded entry; disabled when there is none."""
    for entry in hass.config_entries.async_entries(DOMAIN):
        if entry.state is ConfigEntryState.LOADED:
            options = entry.options
            return assistant.AgentConfig(
                options.get(CONF_AGENT_PROVIDER),
                options.get(CONF_AGENT_API_KEY),
                options.get(CONF_AGENT_MODEL),
                options.get(CONF_AGENT_BASE_URL),
            )
    return assistant.AgentConfig(None, None)


def agent_enabled(hass):
    return agent_config(hass).enabled


class AssistantHome(HassHome):
    """The MCP server's home for one person, plus what only the built-in agent asks for."""

    def overview(self):
        """This home's areas with their entities, and entity counts per domain: what the person
        could put on a dashboard."""
        hass = self._hass
        areas = ar.async_get(hass)
        devices = dr.async_get(hass)
        entities = er.async_get(hass)
        by_area, unassigned, domains = {}, [], {}
        for state in hass.states.async_all():
            entity_id = state.entity_id
            if not self._can(entity_id, POLICY_READ):
                continue
            domain = entity_id.split(".", 1)[0]
            domains[domain] = domains.get(domain, 0) + 1
            entry = entities.async_get(entity_id)
            if entry is not None and (entry.hidden_by or entry.entity_category):
                continue  # Diagnostic and hidden entities rarely belong on a dashboard.
            area_id = entry.area_id if entry is not None else None
            if area_id is None and entry is not None and entry.device_id:
                device = devices.async_get(entry.device_id)
                area_id = device.area_id if device is not None else None
            name = state.attributes.get("friendly_name") or entity_id
            row = f"{entity_id} ({name}): {state.state}"
            if area_id is None:
                unassigned.append(row)
            else:
                by_area.setdefault(area_id, []).append(row)
        listed = []
        for area_id, rows in by_area.items():
            area = areas.async_get_area(area_id)
            listed.append(
                {
                    "area": area.name if area is not None else area_id,
                    "entities": sorted(rows)[:OVERVIEW_PER_AREA],
                    "more": max(0, len(rows) - OVERVIEW_PER_AREA),
                }
            )
        return {
            "areas": sorted(listed, key=lambda a: a["area"].casefold()),
            "not_in_an_area": sorted(unassigned)[:OVERVIEW_UNASSIGNED],
            "not_in_an_area_more": max(0, len(unassigned) - OVERVIEW_UNASSIGNED),
            "entities_per_domain": dict(sorted(domains.items())),
        }

    async def current_config(self, session):
        """The dashboard as the agent should edit it: its newest draft, else as it is now."""
        if session["drafts"]:
            return session["drafts"][-1]["config"]
        try:
            return await edits.async_session_config(self._hass, self._user, session)
        except edits.SessionError:
            return session["baseline"]


class Assistant:
    """Runs the built-in agent's turns: at most one at a time per session, and again while the
    person has said something the last turn didn't see."""

    def __init__(self, hass):
        self.hass = hass
        self.running = {}

    @callback
    def changed(self, session_id):
        if not agent_enabled(self.hass):
            return
        running = self.running.get(session_id)
        if running is not None and not running.done():
            return  # The running turn checks again when it ends.
        try:
            session = edits.edits_of(self.hass).sessions.get(session_id, edits._now())
        except edit_sessions.SessionError:
            return
        if not edit_sessions.EditSessions.assistant_pending(session):
            return
        self.running[session_id] = self.hass.async_create_background_task(
            self._run(session_id), f"terrazzo_assistant_{session_id}"
        )

    async def _run(self, session_id):
        hass = self.hass
        store = edits.edits_of(hass)
        try:
            while True:
                config = agent_config(hass)
                if not config.enabled:
                    return
                try:
                    session = store.sessions.get(session_id, edits._now())
                except edit_sessions.SessionError:
                    return
                if not edit_sessions.EditSessions.assistant_pending(session):
                    return
                user = await hass.auth.async_get_user(session["created_by"])
                if user is None or not user.is_active:
                    return
                store.changed(store.sessions.assistant_started(session_id, edits._now()))
                await self._turn(config, user, copy.deepcopy(session))
        finally:
            # No await between the last check above and this: a note can't slip in unanswered.
            self.running.pop(session_id, None)

    async def _turn(self, config, user, session):
        hass = self.hass
        session_id = session["id"]
        home = AssistantHome(hass, user, _base_url(hass))

        def set_status(status):
            try:
                edits.agent_seen(hass, user, session_id, status)
            except edit_sessions.SessionError:
                pass

        async def complete(messages, tools):
            url, headers, body = assistant.completion_request(config, messages, tools)
            async with async_get_clientsession(hass).post(
                url, json=body, headers=headers, timeout=MODEL_TIMEOUT, allow_redirects=False
            ) as response:
                try:
                    payload = await response.json(content_type=None)
                except ValueError:
                    payload = {"error": (await response.text())[:300]}
                return assistant.completion_message(response.status, payload)

        set_status("Thinking")
        try:
            reply = await assistant.run_turn(session, home, complete, set_status)
        except assistant.ModelError as failure:
            reply = f"Sorry, I couldn't answer: {failure}"
            self._failed(session_id, str(failure))
        except (ClientError, TimeoutError) as failure:
            _LOGGER.warning("Terrazzo's agent couldn't reach %s: %s", config.label, failure)
            reply = f"Sorry, I couldn't reach {config.label}. Try again in a moment."
            self._failed(session_id, f"Couldn't reach {config.label}")
        except edit_sessions.SessionError:
            return  # The session ended meanwhile.
        try:
            if reply:
                edits.agent_message(hass, user, session_id, reply)
            edits.agent_seen(hass, user, session_id, "Waiting for you")
        except edit_sessions.SessionError:
            pass

    def _failed(self, session_id, error):
        store = edits.edits_of(self.hass)
        try:
            store.changed(store.sessions.assistant_failed(session_id, error, edits._now()))
        except edit_sessions.SessionError:
            pass


def _base_url(hass):
    try:
        from homeassistant.helpers.network import NoURLAvailableError, get_url

        try:
            return get_url(hass, prefer_external=True)
        except NoURLAvailableError:
            return ""
    except ImportError:
        return ""


@websocket_api.websocket_command({vol.Required("type"): "terrazzo/agent/info"})
@callback
def ws_info(hass, connection, msg):
    """Whether the built-in agent answers edit sessions, and with which model: clients then offer
    a chat with it in place of their manual editors. Never the key."""
    connection.send_result(msg["id"], agent_config(hass).info())


@callback
def async_register(hass):
    """Answer edit sessions as they change; registered once, like the edit session commands."""
    runner = Assistant(hass)
    websocket_api.async_register_command(hass, ws_info)
    async_dispatcher_connect(hass, edits.SIGNAL_SESSION, runner.changed)
    return runner
