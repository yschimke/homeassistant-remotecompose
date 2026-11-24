"""The household's standing notes for the dashboard agent ("house memory"): one for the whole home
and one per dashboard, saved to `.storage/terrazzo.agent_memory` and given to every turn
(`agent_turn.extra_system_prompt`). People write them, not the agent: what they say once (the pack
variant they like, that helper dashboards need explicit bindings and actions, how wide cards
should be) holds for every later edit session, without the agent keeping anything itself.

`terrazzo/agent/memory` reads them (anyone signed in: the agent's own instructions are no secret
from the person it works for); `terrazzo/agent/memory/set` changes one (administrators, who also
choose the agent).
"""
import asyncio

import voluptuous as vol
from homeassistant.components import websocket_api
from homeassistant.core import callback
from homeassistant.helpers.storage import Store

from . import agent_turn
from .const import DOMAIN

STORAGE_KEY = f"{DOMAIN}.agent_memory"
STORAGE_VERSION = 1
_KEY = "agent_memory"


class AgentMemory:
    def __init__(self, store, data):
        self._store = store
        self.data = data

    def set(self, url_path, text, home=False):
        self.data = agent_turn.with_note(self.data, url_path, text, home)
        self._store.async_delay_save(lambda: self.data, 1)
        return self.data


async def async_memory(hass):
    """This home's notes, loaded once: callers that arrive while the first load runs wait for it
    and get the same instance, so no note saved meanwhile is lost to a second one."""
    state = hass.data.setdefault(DOMAIN, {})
    loading = state.get(_KEY)
    if loading is None:
        loading = state[_KEY] = hass.async_create_task(_async_load(hass), "terrazzo_agent_memory_load")
    try:
        return await asyncio.shield(loading)
    except Exception:
        if state.get(_KEY) is loading:
            del state[_KEY]  # The next call tries the load again.
        raise


async def _async_load(hass):
    store = Store(hass, STORAGE_VERSION, STORAGE_KEY)
    return AgentMemory(store, await store.async_load() or {"home": "", "dashboards": {}})


async def async_notes(hass, url_path):
    return agent_turn.notes_for((await async_memory(hass)).data, url_path)


@websocket_api.websocket_command({vol.Required("type"): "terrazzo/agent/memory"})
@websocket_api.async_response
async def ws_memory(hass, connection, msg):
    connection.send_result(msg["id"], (await async_memory(hass)).data)


@websocket_api.require_admin
@websocket_api.websocket_command(
    {
        vol.Required("type"): "terrazzo/agent/memory/set",
        vol.Optional("home", default=False): bool,
        vol.Optional("url_path"): vol.Any(None, str),
        vol.Required("text"): vol.Any(None, str),
    }
)
@websocket_api.async_response
async def ws_memory_set(hass, connection, msg):
    memory = await async_memory(hass)
    try:
        data = memory.set(msg.get("url_path"), msg["text"], msg["home"])
    except ValueError as failure:  # agent_turn.with_note: not text, or too many dashboards.
        connection.send_error(msg["id"], "invalid_format", str(failure))
        return
    connection.send_result(msg["id"], data)


@callback
def async_register(hass):
    websocket_api.async_register_command(hass, ws_memory)
    websocket_api.async_register_command(hass, ws_memory_set)
