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
from homeassistant.helpers import entity_registry as er
from homeassistant.helpers import intent
from homeassistant.helpers import issue_registry as ir
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


# Repairs that walk an administrator through setting the agent up.
ISSUE_MOVED = "agent_moved"  # The built-in agent was on before 0.1.101 and no agent is chosen yet.
ISSUE_NO_TOOLS = "agent_no_tools"  # The chosen agent doesn't have the dashboard tools turned on.
TERRAZZO_URL = "/config/integrations/integration/" + DOMAIN
MY_URL = "https://my.home-assistant.io/redirect/integration/?domain="


def _agent_entry(hass, chosen):
    """The chosen agent's (config entry, its options or its subentry's data, whether that is an AI
    integration's conversation agent), or None for an agent without a config entry."""
    entity = er.async_get(hass).async_get(chosen)
    if entity is None or not entity.config_entry_id:
        return None
    entry = hass.config_entries.async_get_entry(entity.config_entry_id)
    if entry is None:
        return None
    subentry = (getattr(entry, "subentries", None) or {}).get(getattr(entity, "config_subentry_id", None))
    if subentry is not None:
        return entry, subentry.data, subentry.subentry_type == "conversation"
    return entry, entry.options, False


def tools_state(hass, chosen):
    """Whether [chosen] can reach the dashboard tools: True, False, or None when Terrazzo can't tell
    (an agent that keeps its APIs elsewhere, or none at all), which it then leaves to the agent."""
    found = _agent_entry(hass, chosen)
    if found is None:
        return None
    _, options, definite = found
    return agent_turn.tools_on(options.get("llm_hass_api"), definite)


def _agent_name(hass, chosen):
    found = conversation.async_get_agent_info(hass, chosen)
    state = hass.states.get(chosen)
    friendly = state.attributes.get("friendly_name") if state is not None else None
    return friendly or (found.name if found is not None else None)


def info(hass, user=None):
    """What a client may know: whether an agent answers edit sessions, which one, its name, and, for
    an administrator, what setting it up still needs (`setup`: "agent" while none is chosen, "tools"
    while the chosen one can't reach the dashboard tools, else None) and where they do that
    (`setup_path`, a path on this Home Assistant). Anyone else gets None for both: they can't change
    it, and the chat itself tells them when the tools are off."""
    admin = user is not None and user.is_admin
    chosen = agent_id(hass)
    found = conversation.async_get_agent_info(hass, chosen) if chosen else None
    if found is None:
        setup = ("agent", TERRAZZO_URL) if admin else (None, None)
        return {"enabled": False, "agent_id": None, "name": None, "setup": setup[0], "setup_path": setup[1]}
    # An agent entity named after its device (most AI integrations) reports its entity id as its
    # name; its state's friendly name is what Home Assistant shows.
    answer = {"enabled": True, "agent_id": found.id, "name": _agent_name(hass, found.id), "setup": None, "setup_path": None}
    if admin and tools_state(hass, found.id) is False:
        entry, _, _ = _agent_entry(hass, found.id)
        answer.update(setup="tools", setup_path="/config/integrations/integration/" + entry.domain)
    return answer


@callback
def async_check(hass):
    """Raises or clears the setup repairs for how things stand now. Run when the entry loads, when a
    client asks for the agent, and before each turn, so turning the tools on clears the repair the
    next time anything looks."""
    chosen = agent_id(hass)
    if chosen is not None:
        ir.async_delete_issue(hass, DOMAIN, ISSUE_MOVED)
    found = _agent_entry(hass, chosen) if chosen is not None else None
    if found is None or tools_state(hass, chosen) is not False:
        ir.async_delete_issue(hass, DOMAIN, ISSUE_NO_TOOLS)
        return
    entry = found[0]
    ir.async_create_issue(
        hass,
        DOMAIN,
        ISSUE_NO_TOOLS,
        is_fixable=False,
        is_persistent=False,
        severity=ir.IssueSeverity.WARNING,
        translation_key=ISSUE_NO_TOOLS,
        translation_placeholders={"agent": _agent_name(hass, chosen) or chosen, "integration": entry.title},
        learn_more_url=MY_URL + entry.domain,
    )


@callback
def async_moved(hass, provider):
    """From the 1.2 migration: the built-in agent that was on is gone, so a repair says what replaces
    it. It stays until an agent is chosen (`async_check`)."""
    placeholders = agent_turn.moved_placeholders(provider)
    if placeholders is None:
        return
    ir.async_create_issue(
        hass,
        DOMAIN,
        ISSUE_MOVED,
        is_fixable=False,
        is_persistent=True,
        severity=ir.IssueSeverity.WARNING,
        translation_key=ISSUE_MOVED,
        translation_placeholders=placeholders,
        learn_more_url=MY_URL + DOMAIN,
    )


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
        async_check(hass)
        if tools_state(hass, chosen) is False:
            # Without the tools a turn could only fail: say how to turn them on instead.
            reply = agent_turn.no_tools_reply(_agent_name(hass, chosen))
            self._failed(session_id, reply)
            self._post(user, session_id, reply)
            return
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
    with it as the way to edit a dashboard, or what setting one up still needs."""
    async_check(hass)
    connection.send_result(msg["id"], info(hass, connection.user))


@callback
def async_register(hass):
    """Answer edit sessions as they change; registered once, like the edit session commands."""
    runner = DashboardAgent(hass)
    websocket_api.async_register_command(hass, ws_info)
    async_dispatcher_connect(hass, edits.SIGNAL_SESSION, runner.changed)
    return runner

