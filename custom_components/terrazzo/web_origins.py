"""Let a Terrazzo web install on another origin load HA's images, once an admin approves it.

The app on a Wasmo or any other site talks to HA over its websocket, which CORS doesn't cover, but
it draws camera and entity pictures with `fetch`, which needs HA to allow its origin. HA keeps that
list in its HTTP config store (Home Assistant 2026.10 and later; `http:` YAML is no longer read).

When the app connects it calls `terrazzo/web_origin`. The origin is taken from the connection's own
refresh token, issued to that page by HA's OAuth sign-in, so a caller can't name another site. If
HA doesn't allow it, the integration raises a repair. Fixing it stages HA's HTTP config with the
origin added and restarts, as Settings would; once HA is back it confirms the config, which HA would
otherwise revert after five minutes.
"""
import logging

import voluptuous as vol
from homeassistant.components import websocket_api
from homeassistant.const import EVENT_HOMEASSISTANT_STARTED
from homeassistant.core import CoreState, callback
from homeassistant.helpers import issue_registry as ir
from homeassistant.helpers.network import NoURLAvailableError, get_url
from homeassistant.helpers.storage import Store

from . import cors_origins
from .const import DOMAIN

_LOGGER = logging.getLogger(__name__)

# The origins a fix staged, to confirm once HA starts with them.
STORAGE_KEY = f"{DOMAIN}.web_origins"
STORAGE_VERSION = 1
# Seconds between answering the fix flow and restarting, so its answer reaches the browser.
RESTART_DELAY = 1


async def _http_store(hass):
    """HA's HTTP config store, or None on a Home Assistant that still reads CORS from YAML."""
    try:
        from homeassistant.components.http.config import async_get_and_load_store
    except ImportError:
        return None
    return await async_get_and_load_store(hass)


def _running(store):
    """The config the server runs with now: a pending trial while one is active, else stable."""
    if str(store.active_config_type) == "pending" and store.pending is not None:
        return store.pending
    return store.stable


def _own_origins(hass):
    origins = set()
    for prefer_external in (True, False):
        try:
            origins.add(cors_origins.origin_of(get_url(hass, prefer_external=prefer_external) + "/"))
        except NoURLAvailableError:
            pass
    return origins


@websocket_api.websocket_command({vol.Required("type"): "terrazzo/web_origin"})
@websocket_api.async_response
async def ws_web_origin(hass, connection, msg):
    """Whether HA lets this page's origin load images; raises the repair that allows it if not."""
    token = hass.auth.async_get_refresh_token(connection.refresh_token_id)
    origin = cors_origins.origin_of(token.client_id if token else None)
    if origin is None or origin in _own_origins(hass):
        # A long-lived token, an app sign-in, or HA's own pages: nothing for CORS to allow.
        connection.send_result(msg["id"], {"status": "not_needed"})
        return
    issue = cors_origins.issue_id(origin)
    store = await _http_store(hass)
    if store is None:
        connection.send_result(msg["id"], {"status": "unsupported", "origin": origin})
        return
    if cors_origins.allows(_running(store), origin):
        ir.async_delete_issue(hass, DOMAIN, issue)
        connection.send_result(msg["id"], {"status": "allowed", "origin": origin})
        return
    ir.async_create_issue(
        hass,
        DOMAIN,
        issue,
        is_fixable=True,
        # Raised again on the next connection, so it never outlives the origin being allowed.
        is_persistent=False,
        severity=ir.IssueSeverity.WARNING,
        translation_key="web_origin",
        translation_placeholders={"origin": origin},
        data={"origin": origin},
    )
    connection.send_result(msg["id"], {"status": "needs_approval", "origin": origin})


async def async_allow(hass, origin):
    """Stages [origin] in HA's HTTP config and restarts to try it. Answers an abort reason when it
    can't, or None."""
    store = await _http_store(hass)
    if store is None:
        return "unsupported"
    if hass.state is not CoreState.running:
        return "not_running"
    if store.pending is not None and store.pending.get("error") is None:
        # Someone's own change is waiting to be confirmed; confirming ours would confirm it too.
        return "pending_change"
    if cors_origins.allows(store.stable, origin):
        return None
    from homeassistant.components.http.config import HTTP_STORAGE_SCHEMA

    config = HTTP_STORAGE_SCHEMA(cors_origins.with_origin(store.stable, origin))
    await Store(hass, STORAGE_VERSION, STORAGE_KEY).async_save({"promote": [origin]})
    await store.async_set_pending(config)
    hass.loop.call_later(
        RESTART_DELAY,
        lambda: hass.async_create_task(hass.services.async_call("homeassistant", "restart")),
    )
    return None


async def _async_promote(hass):
    """Confirms the HTTP config a fix staged, now that HA is running with it."""
    saved = Store(hass, STORAGE_VERSION, STORAGE_KEY)
    origins = ((await saved.async_load()) or {}).get("promote") or []
    if not origins:
        return
    await saved.async_save({})
    store = await _http_store(hass)
    pending = store.pending if store else None
    if (
        str(store.active_config_type) != "pending"
        or pending is None
        or pending.get("error") is not None
        or not all(cors_origins.allows(pending, origin) for origin in origins)
    ):
        # HA started without it (a failed trial, or recovery mode): leave it to revert.
        _LOGGER.warning("HTTP config allowing %s did not start; it is not confirmed", origins)
        return
    await store.async_promote_pending()
    _LOGGER.info("Confirmed the HTTP config allowing %s", ", ".join(origins))


def async_register(hass):
    websocket_api.async_register_command(hass, ws_web_origin)

    @callback
    def started(_event=None):
        hass.async_create_task(_async_promote(hass))

    if hass.state is CoreState.running:
        started()
    else:
        hass.bus.async_listen_once(EVENT_HOMEASSISTANT_STARTED, started)
