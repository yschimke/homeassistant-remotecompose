"""`terrazzo/info`: which Terrazzo commands this Home Assistant has, for this user, in one round
trip. Clients ask it first instead of trying each command and reading `unknown_command`, which
can't tell an older integration from a refusal. The answer's rules are in `capabilities.py`.

An integration older than this command answers it `unknown_command`; a client then asks Core's
`manifest/get` for `terrazzo` to tell an older integration from none.
"""
import voluptuous as vol
from homeassistant.components import websocket_api
from homeassistant.loader import async_get_integration

from . import capabilities, design_server
from .const import DOMAIN
from .mcp_view import _pinned_user_id


@websocket_api.websocket_command({vol.Required("type"): "terrazzo/info"})
@websocket_api.async_response
async def ws_info(hass, connection, msg):
    user = connection.user
    # The design add-on as last found: asking the Supervisor afresh is for terrazzo/design_server,
    # which the app calls when it is about to use the add-on.
    addon = await design_server.async_find(hass)
    connection.send_result(
        msg["id"],
        capabilities.reply(
            (await async_get_integration(hass, DOMAIN)).version,
            user_id=user.id,
            is_admin=user.is_admin,
            design_server=addon,
            mcp_user=_pinned_user_id(hass),
        ),
    )


def async_register(hass):
    websocket_api.async_register_command(hass, ws_info)
