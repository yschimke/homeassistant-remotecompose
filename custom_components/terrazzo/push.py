"""Silent push for the Terrazzo app: HA's mobile_app `push_url` for the app's registration, the
held messages, and the content-free wake (push_queue.py, docs/architecture/notifications.md)."""
import logging
import time

import voluptuous as vol
from aiohttp import ClientError, ClientTimeout, web
from homeassistant.components import websocket_api
from homeassistant.components.http import HomeAssistantView
from homeassistant.core import callback
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.storage import Store

from .const import DOMAIN
from .push_queue import PushQueue

_LOGGER = logging.getLogger(__name__)

STORAGE_KEY = f"{DOMAIN}.push"
STORAGE_VERSION = 1
PUSH_URL = "/api/terrazzo/push"
APP_ID = "ee.schimke.harc"


class Push:
    """The queue, saved to `.storage/terrazzo.push` as it changes."""

    def __init__(self, hass, store, queue):
        self.hass = hass
        self._store = store
        self.queue = queue

    @classmethod
    async def async_load(cls, hass):
        store = Store(hass, STORAGE_VERSION, STORAGE_KEY)
        return cls(hass, store, PushQueue(await store.async_load()))

    @callback
    def changed(self):
        self._store.async_delay_save(self.queue.to_data, 1)


def push_of(hass):
    return hass.data[DOMAIN]["push"]


def _registration(hass, webhook_id):
    """The mobile_app config entry with [webhook_id], or None."""
    for entry in hass.config_entries.async_entries("mobile_app"):
        if entry.data.get("webhook_id") == webhook_id:
            return entry
    return None


def push_url(hass, webhook_id):
    """Where HA's mobile_app is to POST this registration's notifications: HA itself, on the
    loopback interface, so a message never leaves the machine. With TLS on HA's own port the
    certificate won't name 127.0.0.1, so HA's configured URL is used instead."""
    api = hass.config.api
    path = f"{PUSH_URL}/{webhook_id}"
    if api is not None and not api.use_ssl:
        return f"http://127.0.0.1:{api.port}{path}"
    from homeassistant.helpers.network import get_url

    return get_url(hass, prefer_external=False) + path


class TerrazzoPushView(HomeAssistantView):
    """HA's mobile_app POSTs here; unauthenticated, so the body's `push_token` must be the
    device's secret."""

    url = PUSH_URL + "/{webhook_id}"
    name = "api:terrazzo:push"
    requires_auth = False

    def __init__(self, hass):
        self.hass = hass

    async def post(self, request, webhook_id):
        push = self.hass.data.get(DOMAIN, {}).get("push")
        try:
            body = await request.json()
        except ValueError:
            return web.json_response({"message": "Expected JSON."}, status=400)
        if push is None or not isinstance(body, dict) or not push.queue.accepts(
            webhook_id, body.get("push_token")
        ):
            return web.json_response({"message": "Unknown device."}, status=403)
        token, relay_url = push.queue.hold(webhook_id, body, time.time())
        push.changed()
        # HA waits 10 s for this answer; the relay call mustn't hold it.
        self.hass.async_create_background_task(
            _async_wake(self.hass, relay_url, token, webhook_id), "terrazzo_push_wake"
        )
        # Held either way: the app collects it the next time it connects.
        return web.json_response({"held": True}, status=201)


async def _async_wake(hass, relay_url, token, webhook_id):
    """Asks the relay to wake the app. The wake names the registration and nothing else; the app
    fetches the rest from here."""
    try:
        async with async_get_clientsession(hass).post(
            relay_url,
            json={"token": token, "webhook_id": webhook_id, "app_id": APP_ID},
            timeout=ClientTimeout(total=10),
        ) as response:
            if response.status >= 300:
                _LOGGER.warning("Terrazzo push relay refused the wake: %s", response.status)
    except (ClientError, TimeoutError) as failure:
        _LOGGER.warning("Couldn't reach the Terrazzo push relay: %s", failure)


@websocket_api.websocket_command(
    {
        vol.Required("type"): "terrazzo/push/register",
        vol.Required("webhook_id"): str,
        vol.Required("fcm_token"): vol.All(str, vol.Length(min=20, max=4096)),
        vol.Required("relay_url"): vol.All(str, vol.Match(r"^https://")),
    }
)
@callback
def ws_register(hass, connection, msg):
    """The app's FCM token for its own registration; answers the push URL and token to give HA."""
    entry = _registration(hass, msg["webhook_id"])
    if entry is None or entry.data.get("user_id") != connection.user.id:
        connection.send_error(msg["id"], "not_found", "No such mobile_app registration")
        return
    push = push_of(hass)
    secret = push.queue.register(
        msg["webhook_id"], connection.user.id, msg["fcm_token"], msg["relay_url"]
    )
    push.changed()
    connection.send_result(
        msg["id"], {"push_url": push_url(hass, msg["webhook_id"]), "push_token": secret}
    )


@websocket_api.websocket_command(
    {vol.Required("type"): "terrazzo/push/take", vol.Required("webhook_id"): str}
)
@callback
def ws_take(hass, connection, msg):
    """The registration's held messages, for its own user; each goes once."""
    push = push_of(hass)
    messages = push.queue.take(msg["webhook_id"], connection.user.id, time.time())
    if messages:
        push.changed()
    connection.send_result(msg["id"], {"messages": messages})


def async_register(hass):
    hass.http.register_view(TerrazzoPushView(hass))
    for command in (ws_register, ws_take):
        websocket_api.async_register_command(hass, command)
