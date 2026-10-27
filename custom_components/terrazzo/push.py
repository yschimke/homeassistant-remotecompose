"""Silent push for the Terrazzo app: HA's mobile_app `push_url` for the app's registration, the
held messages, and the content-free wake (push_queue.py, docs/architecture/notifications.md). The
same wake keeps the app's widgets current when an entity they read changes (state_wakes.py,
docs/architecture/live-widget-updates.md)."""
import logging
import time

import voluptuous as vol
from aiohttp import ClientError, ClientTimeout, web
from homeassistant.components import websocket_api
from homeassistant.components.http import HomeAssistantView
from homeassistant.core import callback
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.config_validation import entity_id as cv_entity_id
from homeassistant.helpers.event import async_call_later, async_track_state_change_event
from homeassistant.helpers.storage import Store

from .const import DOMAIN
from .push_queue import PushQueue
from .state_wakes import MAX_ENTITIES, StateWakes

_LOGGER = logging.getLogger(__name__)

STORAGE_KEY = f"{DOMAIN}.push"
STORAGE_VERSION = 1
PUSH_URL = "/api/terrazzo/push"
APP_ID = "ee.schimke.harc"


class Push:
    """The queue, saved to `.storage/terrazzo.push` as it changes, and the state wakes for the
    entities each device's widgets read."""

    def __init__(self, hass, store, queue):
        self.hass = hass
        self._store = store
        self.queue = queue
        self.wakes = StateWakes()
        for webhook_id, entity_ids in queue.watched().items():
            self.wakes.restore(webhook_id, entity_ids)
        self._untrack = None
        self._tracked = set()
        self._cancel_timer = None
        self.track()

    @classmethod
    async def async_load(cls, hass):
        store = Store(hass, STORAGE_VERSION, STORAGE_KEY)
        return cls(hass, store, PushQueue(await store.async_load()))

    @callback
    def changed(self):
        self._store.async_delay_save(self.queue.to_data, 1)

    @callback
    def track(self):
        """Follows the state of every watched entity, and only those."""
        watched = self.wakes.watched()
        if watched == self._tracked:
            return
        if self._untrack is not None:
            self._untrack()
            self._untrack = None
        self._tracked = watched
        if watched:
            self._untrack = async_track_state_change_event(
                self.hass, sorted(watched), self._state_changed
            )

    @callback
    def _state_changed(self, event):
        old, new = event.data.get("old_state"), event.data.get("new_state")
        wakes = self.wakes.changed(
            event.data["entity_id"],
            old.state if old is not None else None,
            new.state if new is not None else None,
            time.time(),
        )
        self._send(wakes)
        self.schedule()

    @callback
    def schedule(self):
        """Arms the timer for the next folded wake, if one waits."""
        if self._cancel_timer is not None:
            self._cancel_timer()
            self._cancel_timer = None
        now = time.time()
        due = self.wakes.next_due(now)
        if due is not None:
            self._cancel_timer = async_call_later(self.hass, max(due - now, 0), self._timer)

    @callback
    def _timer(self, _now):
        self._cancel_timer = None
        self._send(self.wakes.due(time.time()))
        self.schedule()

    @callback
    def _send(self, wakes):
        for webhook_id, urgent in wakes:
            target = self.queue.wake_target(webhook_id)
            if target is None:
                continue
            token, relay_url = target
            self.hass.async_create_background_task(
                _async_wake(self.hass, relay_url, token, webhook_id, "state", urgent),
                "terrazzo_state_wake",
            )


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


async def _async_wake(hass, relay_url, token, webhook_id, kind="sync", urgent=True):
    """Asks the relay to wake the app: [kind] `sync` for held messages, `state` for a watched
    entity's change. The wake names the registration and nothing else; the app fetches the rest
    from here. A routine state wake isn't [urgent], so FCM may hold it while the phone dozes."""
    body = {"token": token, "webhook_id": webhook_id, "app_id": APP_ID}
    if kind != "sync":
        body.update(type=kind, urgent=urgent)
    try:
        async with async_get_clientsession(hass).post(
            relay_url,
            json=body,
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
    if not push.queue.owns(msg["webhook_id"], connection.user.id):
        # Another user's device before: it starts out watching nothing.
        push.wakes.forget(msg["webhook_id"])
        push.track()
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


@websocket_api.websocket_command(
    {
        vol.Required("type"): "terrazzo/push/watch",
        vol.Required("webhook_id"): str,
        vol.Required("entity_ids"): vol.All([cv_entity_id], vol.Length(max=MAX_ENTITIES)),
        # Capped at MAX_LEASE by StateWakes.watch, which answers the lease granted.
        vol.Optional("lease_seconds", default=0): vol.All(int, vol.Range(min=0)),
    }
)
@callback
def ws_watch(hass, connection, msg):
    """The entities the registration's widgets read, whose changes wake it; with a lease while the
    app is connected and follows them itself, so nothing wakes it until the lease runs out."""
    push = push_of(hass)
    webhook_id = msg["webhook_id"]
    if not push.queue.owns(webhook_id, connection.user.id):
        connection.send_error(msg["id"], "not_found", "Register for silent push first")
        return
    if push.queue.set_watched(webhook_id, msg["entity_ids"]):
        push.changed()
    lease_until = push.wakes.watch(webhook_id, msg["entity_ids"], msg["lease_seconds"], time.time())
    push.track()
    push.schedule()
    connection.send_result(msg["id"], {"lease_seconds": max(0, round(lease_until - time.time()))})


def async_register(hass):
    hass.http.register_view(TerrazzoPushView(hass))
    for command in (ws_register, ws_take, ws_watch):
        websocket_api.async_register_command(hass, command)
