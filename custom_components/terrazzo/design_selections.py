"""Authenticated commands for the signed-in user's card design selections."""
import voluptuous as vol
from homeassistant.components import websocket_api
from homeassistant.core import callback
from homeassistant.helpers.storage import Store

from .card_selections import CardSelections
from .const import DOMAIN


class DesignSelections:
    def __init__(self, store, choices):
        self.store = store
        self.choices = choices

    @classmethod
    async def async_load(cls, hass):
        store = Store(hass, 1, f"{DOMAIN}.design_selections")
        return cls(store, CardSelections(await store.async_load()))

    @callback
    def changed(self):
        self.store.async_delay_save(self.choices.to_data, 1)


@websocket_api.websocket_command({vol.Required("type"): "terrazzo/design_selections/get"})
@callback
def ws_get(hass, connection, msg):
    selections = hass.data[DOMAIN]["design_selections"].choices
    connection.send_result(msg["id"], {"cards": selections.for_user(connection.user.id)})


@websocket_api.websocket_command({
    vol.Required("type"): "terrazzo/design_selections/set",
    vol.Required("card"): str,
    vol.Required("design_id"): vol.Any(None, str),
})
@callback
def ws_set(hass, connection, msg):
    selections = hass.data[DOMAIN]["design_selections"]
    try:
        selections.choices.set(connection.user.id, msg["card"], msg["design_id"])
    except ValueError as failure:
        connection.send_error(msg["id"], "invalid_selection", str(failure))
        return
    selections.changed()
    connection.send_result(msg["id"], {"cards": selections.choices.for_user(connection.user.id)})


@callback
def async_register(hass):
    websocket_api.async_register_command(hass, ws_get)
    websocket_api.async_register_command(hass, ws_set)
