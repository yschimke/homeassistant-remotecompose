"""The conversation agent that answers edit sessions: one of Home Assistant's own, chosen in the
integration's options (`agent`), with the "Terrazzo dashboards" LLM API on in its own options.

Every edit session then gets an answer from it: a new session a first turn, each note the person
leaves another. A turn is one `conversation.async_converse` call as the person who started the
session (their context, so the dashboard tools run with their permissions only), told which session
it answers and the exchange so far (`agent_turn.py`). What it says is posted as the agent's message,
and each dashboard tool it calls is a step in the chat (`llm_api.TURN`). Like any agent it only
drafts: a person applies. Clients learn whether an agent answers, and its name, from
`terrazzo/agent/info`; the provider and its key stay in that agent's own integration.
"""
import copy
import logging

import voluptuous as vol
from homeassistant.components import conversation, websocket_api
from homeassistant.config_entries import ConfigEntryState
from homeassistant.core import Context, callback
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers import intent
from homeassistant.helpers.dispatcher import async_dispatcher_connect

from . import agent_turn, edit_sessions, edits, llm_api
from .const import CONF_AGENT, DOMAIN

_LOGGER = logging.getLogger(__name__)


def agent_id(hass):
    """The chosen agent's id while the entry is loaded and the agent exists, else None."""
    for entry in hass.config_entries.async_entries(DOMAIN):
        if entry.state is ConfigEntryState.LOADED:
            chosen = entry.options.get(CONF_AGENT) or None
            if chosen and conversation.async_get_agent_info(hass, chosen) is not None:
                return chosen
    return None


def info(hass):
    """What a client may know: whether an agent answers edit sessions, which one, its name."""
    chosen = agent_id(hass)
    found = conversation.async_get_agent_info(hass, chosen) if chosen else None
    if found is None:
        return {"enabled": False, "agent_id": None, "name": None}
    # An agent entity named after its device (most AI integrations) reports its entity id as its
    # name; its state's friendly name is what Home Assistant shows.
    state = hass.states.get(found.id)
    friendly = state.attributes.get("friendly_name") if state is not None else None
    return {"enabled": True, "agent_id": found.id, "name": friendly or found.name}


class Turn:
    """One turn's link from the dashboard tools to its session (`llm_api.TURN`): the chat's status
    line before each call, and a step after it."""

    def __init__(self, hass, user, session_id):
        self.hass = hass
        self.user_id = user.id
        self._user = user
        self.session_id = session_id
        self.drafted = False

    def _status(self, status):
        try:
            edits.agent_seen(self.hass, self._user, self.session_id, status)
        except edit_sessions.SessionError:
            pass

    def calling(self, tool):
        if tool in agent_turn.STATUS:
            self._status(agent_turn.STATUS[tool])

    def called(self, tool, arguments, result):
        if tool == "submit_draft" and isinstance(result, dict) and "error" not in result:
            self.drafted = True
        store = edits.edits_of(self.hass)
        try:
            store.sessions.agent_step(
                self.session_id, tool, *agent_turn.describe_step(tool, arguments, result), edits._now()
            )
            store.changed(store.sessions.get(self.session_id, edits._now()))
        except edit_sessions.SessionError:
            pass


class DashboardAgent:
    """Runs the agent's turns: at most one at a time per session, and again while the person has
    said something the last turn didn't see."""

    def __init__(self, hass):
        self.hass = hass
        self.running = {}

    @callback
    def changed(self, session_id):
        if agent_id(self.hass) is None:
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
            self._run(session_id), f"terrazzo_dashboard_agent_{session_id}"
        )

    async def _run(self, session_id):
        hass = self.hass
        store = edits.edits_of(hass)
        try:
            while True:
                chosen = agent_id(hass)
                if chosen is None:
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
                # The turn's text and recap are read before the notes are marked taken.
                session = copy.deepcopy(session)
                store.changed(store.sessions.assistant_started(session_id, edits._now()))
                await self._turn(chosen, user, session)
        finally:
            # No await between the last check above and this: a note can't slip in unanswered.
            self.running.pop(session_id, None)

    async def _turn(self, chosen, user, session):
        hass = self.hass
        session_id = session["id"]
        turn = Turn(hass, user, session_id)
        turn._status("Thinking")
        token = llm_api.TURN.set(turn)
        try:
            result = await conversation.async_converse(
                hass,
                agent_turn.turn_text(session),
                None,  # A new conversation each turn: the recap is its memory (agent_turn.py).
                Context(user_id=user.id),
                language=hass.config.language,
                agent_id=chosen,
                extra_system_prompt=agent_turn.extra_system_prompt(session, hass.config.location_name),
            )
        except (HomeAssistantError, ValueError) as failure:
            _LOGGER.warning("Terrazzo's dashboard agent %s couldn't answer: %s", chosen, failure)
            self._failed(session_id, str(failure) or "The agent couldn't answer")
            self._post(user, session_id, f"Sorry, I couldn't answer: {failure}")
            return
        finally:
            llm_api.TURN.reset(token)
        response = result.response
        speech = ((response.speech or {}).get("plain") or {}).get("speech") or ""
        if response.response_type == intent.IntentResponseType.ERROR:
            self._failed(session_id, speech or "The agent couldn't answer")
        self._post(user, session_id, agent_turn.reply_text(speech, turn.drafted))

    def _post(self, user, session_id, reply):
        try:
            if reply:
                edits.agent_message(self.hass, user, session_id, reply)
            edits.agent_seen(self.hass, user, session_id, "Waiting for you")
        except edit_sessions.SessionError:
            pass

    def _failed(self, session_id, error):
        store = edits.edits_of(self.hass)
        try:
            store.changed(store.sessions.assistant_failed(session_id, error, edits._now()))
        except edit_sessions.SessionError:
            pass


@websocket_api.websocket_command({vol.Required("type"): "terrazzo/agent/info"})
@callback
def ws_info(hass, connection, msg):
    """Whether a conversation agent answers edit sessions, and its name: clients then open a chat
    with it as the way to edit a dashboard."""
    connection.send_result(msg["id"], info(hass))


@callback
def async_register(hass):
    """Answer edit sessions as they change; registered once, like the edit session commands."""
    runner = DashboardAgent(hass)
    websocket_api.async_register_command(hass, ws_info)
    async_dispatcher_connect(hass, edits.SIGNAL_SESSION, runner.changed)
    return runner

