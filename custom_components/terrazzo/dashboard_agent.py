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
import asyncio
import copy
import logging

import voluptuous as vol
from homeassistant.auth.permissions.const import POLICY_READ
from homeassistant.components import conversation, websocket_api
from homeassistant.config_entries import ConfigEntryState
from homeassistant.core import Context, callback
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers import entity_registry as er
from homeassistant.helpers import intent
from homeassistant.helpers import issue_registry as ir
from homeassistant.helpers.dispatcher import async_dispatcher_connect

from . import agent_memory, agent_turn, assist_chat, edit_sessions, edits, llm_api, llm_tools
from .const import CONF_AGENT, DOMAIN

_LOGGER = logging.getLogger(__name__)


def agent_id(hass, setting_up=None):
    """The chosen agent's id while the entry is loaded and the agent exists, else None. [setting_up]
    is an entry in `async_setup_entry`, read before Home Assistant marks it loaded."""
    for entry in hass.config_entries.async_entries(DOMAIN):
        if entry.state is ConfigEntryState.LOADED or entry is setting_up:
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


def assist_state(hass, chosen):
    """Whether [chosen] has Assist's tools on too, so the dashboard chat can switch devices: True,
    False, or None when Terrazzo can't tell."""
    found = _agent_entry(hass, chosen)
    if found is None:
        return None
    _, options, definite = found
    apis = options.get("llm_hass_api")
    if apis is None:
        return False if definite else None
    on = assist_chat.apis_on(apis)
    return None if on is None else on["assist"]


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
    answer = {
        "enabled": True,
        "agent_id": found.id,
        "name": _agent_name(hass, found.id),
        "setup": None,
        "setup_path": None,
        # The dashboard chat (`terrazzo/assist`) is there whenever an agent is; whether it can also
        # switch devices (Assist's tools on the agent) is told to everyone: it changes what the
        # chat offers, not what they may do.
        "chat": True,
        "assist": assist_state(hass, found.id),
    }
    if admin and tools_state(hass, found.id) is False:
        entry, _, _ = _agent_entry(hass, found.id)
        answer.update(setup="tools", setup_path="/config/integrations/integration/" + entry.domain)
    return answer


@callback
def async_check(hass, setting_up=None):
    """Raises or clears the setup repairs for how things stand now. Run when the entry loads (with
    [setting_up], the entry not yet marked loaded), when a client asks for the agent, and before each
    turn, so turning the tools on clears the repair the next time anything looks."""
    chosen = agent_id(hass, setting_up)
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

    def prepare(self, tool, arguments):
        """[arguments] as the tool runs them in this turn (`llm_tools.for_turn`)."""
        return llm_tools.for_turn(tool, arguments, self.session_id)

    def _status(self, status):
        try:
            edits.agent_seen(self.hass, self._user, self.session_id, status)
        except edit_sessions.SessionError:
            pass

    def calling(self, tool, arguments=None):
        if tool in agent_turn.STATUS:
            self._status(agent_turn.STATUS[tool])
        entity_id = (arguments or {}).get("entity_id")
        self.progress(tool=tool, status=agent_turn.STATUS.get(tool), entity_id=entity_id)

    def progress(self, **change):
        """The running turn's `active_turn` (edit_sessions.turn_progress), as the app overlays it."""
        store = edits.edits_of(self.hass)
        try:
            store.changed(store.sessions.turn_progress(self.session_id, edits._now(), **change))
        except edit_sessions.SessionError:
            pass

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


class ChatTurn(Turn):
    """One turn of the dashboard chat (`terrazzo/assist`): drafts go to the open dashboard's edit
    session ([session_id], or the one the agent's `submit_draft` opens), and each dashboard tool is
    a step both in the chat's reply and, once there is a session, in that session."""

    def __init__(self, hass, user, url_path, session_id=None):
        super().__init__(hass, user, session_id)
        self.url_path = url_path
        self.steps = []

    def prepare(self, tool, arguments):
        return assist_chat.for_chat(tool, arguments, self.url_path, self.session_id)

    def _status(self, status):
        if self.session_id:
            super()._status(status)

    def progress(self, **change):
        if self.session_id:
            super().progress(**change)

    def called(self, tool, arguments, result):
        opened = assist_chat.drafted_session(tool, result)
        if opened and self.session_id is None:
            self.session_id = opened
        asked, output, ok = agent_turn.describe_step(tool, arguments, result)
        self.steps.append({"tool": tool, "input": asked, "output": output, "ok": ok})
        del self.steps[: -assist_chat.MAX_STEPS]
        if self.session_id:
            super().called(tool, arguments, result)
        elif tool == "submit_draft" and isinstance(result, dict) and "error" not in result:
            self.drafted = True


class DashboardAgent:
    """Runs the agent's turns: at most one at a time per session, and again while the person has
    said something the last turn didn't see."""

    def __init__(self, hass):
        self.hass = hass
        self.running = {}
        # Sessions whose running turn the person cancelled: its task ends without a reply.
        self.cancelled = set()

    def cancel(self, user, session_id):
        """Stops [session_id]'s running turn (`terrazzo/edit/cancel`): the overlay goes at once,
        and the notes it was answering count as answered, so it doesn't start again on them."""
        store = edits.edits_of(self.hass)
        edits._visible_session(self.hass, user, session_id)
        session = store.sessions.cancel_turn(session_id, user.id, edits._now())
        running = self.running.get(session_id)
        if running is not None and not running.done():
            self.cancelled.add(session_id)
            running.cancel()
        store.changed(session)
        return session

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
                about = edit_sessions.note_entities(session)
                store.changed(store.sessions.turn_started(session_id, edits._now(), about))
                try:
                    await self._turn(chosen, user, session)
                finally:
                    self._ended(session_id)
        except asyncio.CancelledError:
            if session_id not in self.cancelled:
                raise  # Home Assistant is stopping, not the person.
        finally:
            self.cancelled.discard(session_id)
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
        retries = iter(agent_turn.RETRY_DELAYS)
        # Carry on the last turn's conversation while Home Assistant keeps it (the provider can
        # reuse its prompt cache); after that, a new one, with the recap as its memory.
        warm = edit_sessions.warm_conversation(session, edits._now())
        house_notes = await agent_memory.async_notes(hass, session.get("url_path"))
        while True:
            token = llm_api.TURN.set(turn)
            try:
                result = await conversation.async_converse(
                    hass,
                    agent_turn.turn_text(session),
                    warm,
                    Context(user_id=user.id),
                    language=hass.config.language,
                    agent_id=chosen,
                    extra_system_prompt=agent_turn.extra_system_prompt(
                        session, hass.config.location_name, warm=warm is not None, house_notes=house_notes
                    ),
                )
            except (HomeAssistantError, ValueError) as failure:
                if await self._retry(turn, chosen, failure, retries):
                    continue
                _LOGGER.warning("Terrazzo's dashboard agent %s couldn't answer: %s", chosen, failure)
                self._failed(session_id, str(failure) or "The agent couldn't answer")
                self._post(user, session_id, f"Sorry, I couldn't answer: {failure}")
                return
            finally:
                llm_api.TURN.reset(token)
            self._remember(session_id, result)
            response = result.response
            speech = ((response.speech or {}).get("plain") or {}).get("speech") or ""
            if response.response_type == intent.IntentResponseType.ERROR and await self._retry(
                turn, chosen, speech, retries
            ):
                continue
            break
        if response.response_type == intent.IntentResponseType.ERROR:
            self._failed(session_id, speech or "The agent couldn't answer")
        self._post(user, session_id, agent_turn.reply_text(speech, turn.drafted))

    async def _retry(self, turn, chosen, failure, retries):
        """Waits out a passing provider failure (503, 429, RESOURCE_EXHAUSTED) and says to run the
        turn again: at most `agent_turn.RETRY_DELAYS` times, and never once it drafted, so a retry
        can't submit a second draft."""
        if turn.drafted or not agent_turn.transient(failure):
            return False
        delay = next(retries, None)
        if delay is None:
            return False
        _LOGGER.info("Terrazzo's dashboard agent %s is busy (%s); trying again in %s s", chosen, failure, delay)
        turn._status("The assistant is busy; trying again")
        turn.progress(phase=edit_sessions.RETRYING, status="Provider busy, retrying")
        await asyncio.sleep(delay)
        return True

    def _remember(self, session_id, result):
        store = edits.edits_of(self.hass)
        try:
            store.sessions.remember_conversation(
                session_id, getattr(result, "conversation_id", None), edits._now()
            )
        except edit_sessions.SessionError:
            pass

    def _ended(self, session_id):
        store = edits.edits_of(self.hass)
        try:
            store.changed(store.sessions.turn_ended(session_id, edits._now()))
        except edit_sessions.SessionError:
            pass

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


@websocket_api.websocket_command(
    {vol.Required("type"): "terrazzo/edit/cancel", vol.Required("session_id"): str}
)
@callback
def ws_cancel(hass, connection, msg):
    """Stops the agent's running turn on a session: the person's Cancel on the overlay."""
    runner = hass.data.get(DOMAIN, {}).get("dashboard_agent")
    try:
        if runner is None:
            raise edit_sessions.SessionError("No agent answers edit sessions here")
        session = runner.cancel(connection.user, msg["session_id"])
    except edit_sessions.SessionError as failure:
        edits._send_failure(connection, msg, failure)
        return
    connection.send_result(msg["id"], edits._session_reply(hass, session, user=connection.user))


def _on_screen(hass, user, entity_ids):
    """The entities the app says its cards show, as `(entity_id, name, state)` the prompt names:
    only ones [user] may read and Home Assistant has."""
    def can_read(entity_id):
        return user.permissions.check_entity(entity_id, POLICY_READ) and hass.states.get(entity_id) is not None

    rows = []
    for entity_id in assist_chat.clean_entities(entity_ids, can_read):
        state = hass.states.get(entity_id)
        unit = state.attributes.get("unit_of_measurement")
        rows.append(
            (entity_id, state.attributes.get("friendly_name"), f"{state.state} {unit}" if unit else state.state)
        )
    return rows


def _dashboard_title(hass, user, url_path):
    """The open dashboard's title as the person sees it; raises when they can't see it."""
    found = edits.visible_dashboards(hass, user).get(url_path or None)
    if found is None:
        raise edit_sessions.SessionError(f"No dashboard {url_path or 'Overview'}")
    _, config = found
    title = config.get("title") if isinstance(config, dict) else None
    return title or ("Overview" if not url_path else url_path)


async def async_chat(hass, user, text, url_path=None, view=None, entity_ids=(), conversation_id=None, session_id=None):
    """One turn of the dashboard chat, as [user], from the dashboard [url_path] they have open:
    `assist_chat.reply`. The chosen agent answers with its own tools (Assist's, when it has them on)
    and Terrazzo's dashboard tools; a draft lands in [session_id], or in the session its
    `submit_draft` opens on the open dashboard. When there is a session, the exchange is kept on it
    too, so its panel and a later note carry on the same conversation."""
    chosen = agent_id(hass)
    if chosen is None:
        raise edit_sessions.SessionError("No chat agent is set up for Terrazzo")
    text = assist_chat.clean_text(text)
    title = _dashboard_title(hass, user, url_path)
    store = edits.edits_of(hass)
    if session_id:
        _, session = edits._visible_session(hass, user, session_id)
        if session["status"] != edit_sessions.OPEN:
            session_id = None
        elif conversation_id is None:
            conversation_id = edit_sessions.warm_conversation(session, edits._now())
    turn = ChatTurn(hass, user, url_path or None, session_id or None)
    house_notes = await agent_memory.async_notes(hass, url_path or None)
    prompt = assist_chat.screen_prompt(
        hass.config.location_name,
        title,
        url_path or None,
        (view or "").strip()[:80] or None,
        _on_screen(hass, user, entity_ids),
        turn.session_id,
    )
    if house_notes:
        prompt += "\n\nThe household's standing notes for dashboard edits (their preferences):\n" + house_notes
    retries = iter(agent_turn.RETRY_DELAYS)
    while True:
        token = llm_api.TURN.set(turn)
        try:
            result = await conversation.async_converse(
                hass,
                text,
                conversation_id,
                Context(user_id=user.id),
                language=hass.config.language,
                agent_id=chosen,
                extra_system_prompt=prompt,
            )
        except (HomeAssistantError, ValueError) as failure:
            if not turn.drafted and agent_turn.transient(failure) and (delay := next(retries, None)) is not None:
                await asyncio.sleep(delay)
                continue
            _LOGGER.warning("Terrazzo's dashboard chat with %s couldn't answer: %s", chosen, failure)
            return assist_chat.reply(
                f"Sorry, I couldn't answer: {failure}", "error", conversation_id, turn.session_id, turn.drafted, turn.steps
            )
        finally:
            llm_api.TURN.reset(token)
        response = result.response
        speech = ((response.speech or {}).get("plain") or {}).get("speech") or ""
        failed = response.response_type == intent.IntentResponseType.ERROR
        if failed and not turn.drafted and agent_turn.transient(speech) and (delay := next(retries, None)) is not None:
            await asyncio.sleep(delay)
            continue
        break
    conversation_id = getattr(result, "conversation_id", None) or conversation_id
    if turn.session_id:
        _keep_on_session(hass, user, store, turn.session_id, text, speech, turn.drafted, conversation_id)
    return assist_chat.reply(
        speech, "error" if failed else "answer", conversation_id, turn.session_id, turn.drafted, turn.steps
    )


def _keep_on_session(hass, user, store, session_id, text, speech, drafted, conversation_id):
    """The chat's exchange on the edit session it drafted into: the person's words as a note the
    agent has already answered (so the session's own agent doesn't answer it again), the reply as
    the agent's message, and the conversation, so a note left on the draft later carries on in it."""
    now = edits._now()
    try:
        store.sessions.note(session_id, text, user.id, now)
        store.sessions.assistant_started(session_id, now)
        store.sessions.remember_conversation(session_id, conversation_id, now)
        # One change for all three: the session's agent sees the note already answered.
        store.changed(store.sessions.get(session_id, now))
        reply = agent_turn.reply_text(speech, drafted)
        if reply:
            edits.agent_message(hass, user, session_id, reply)
        edits.agent_seen(hass, user, session_id, "Waiting for you")
    except edit_sessions.SessionError:
        pass


@websocket_api.websocket_command(
    {
        vol.Required("type"): "terrazzo/assist",
        vol.Required("text"): str,
        vol.Optional("url_path"): vol.Any(None, str),
        # The open view's title, as the person sees it.
        vol.Optional("view"): vol.Any(None, str),
        # The entities the open view's cards show, in screen order.
        vol.Optional("entities"): [str],
        vol.Optional("conversation_id"): vol.Any(None, str),
        # The edit session open on this dashboard, whose draft the app draws in place.
        vol.Optional("session_id"): vol.Any(None, str),
    }
)
@websocket_api.async_response
async def ws_assist(hass, connection, msg):
    """The dashboard chat: Home Assistant's conversation agent, asked from an open dashboard."""
    try:
        answer = await async_chat(
            hass,
            connection.user,
            msg["text"],
            url_path=msg.get("url_path"),
            view=msg.get("view"),
            entity_ids=msg.get("entities") or (),
            conversation_id=msg.get("conversation_id"),
            session_id=msg.get("session_id"),
        )
    except (edit_sessions.SessionError, ValueError) as failure:
        edits._send_failure(connection, msg, failure)
        return
    connection.send_result(msg["id"], answer)


@callback
def async_register(hass):
    """Answer edit sessions as they change; registered once, like the edit session commands."""
    runner = DashboardAgent(hass)
    websocket_api.async_register_command(hass, ws_info)
    websocket_api.async_register_command(hass, ws_cancel)
    websocket_api.async_register_command(hass, ws_assist)
    agent_memory.async_register(hass)
    async_dispatcher_connect(hass, edits.SIGNAL_SESSION, runner.changed)
    return runner

