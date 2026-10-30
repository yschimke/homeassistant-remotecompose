"""The private card design server: the Terrazzo Card Designs add-on (design-server/addon), when this
Home Assistant runs it.

The app asks `terrazzo/design_server` whether there is one, and then reaches its API at
DESIGNS_URL with its own Home Assistant token: this view passes the call on to the add-on with that
token (the add-on checks it with Core) and the person's username. A browser opens the add-on's
pages, UI Builder among them, through its admin-only ingress panel instead.
"""
import logging
import os
import secrets
import time

import voluptuous as vol
from aiohttp import ClientError, ClientTimeout, web
from homeassistant.components import websocket_api
from homeassistant.components.http import HomeAssistantView
from homeassistant.core import callback
from homeassistant.helpers.aiohttp_client import async_get_clientsession

from .const import DOMAIN
from .design_route import (
    ADDON_PORT,
    DESIGNS_URL,
    OPEN_URL,
    app_path,
    downstream_headers,
    find_addon,
    page_path,
    upstream_headers,
)

_LOGGER = logging.getLogger(__name__)

# How long a found add-on is trusted before the Supervisor is asked again.
FOUND_FOR = 300
SUPERVISOR_TIMEOUT = ClientTimeout(total=10)
DESIGN_TIMEOUT = ClientTimeout(total=60)
# How long a one-time link to the add-on's pages stays good.
LINK_FOR = 60


async def async_find(hass, fresh=False):
    """The design add-on's Supervisor info (`slug`, `hostname`, `state`, `version`,
    `ingress_url`, ...), or None: no Supervisor, no add-on, or the Supervisor didn't answer."""
    state = hass.data.setdefault(DOMAIN, {})
    found, at = state.get("design_server", (None, 0))
    if not fresh and found is not None and time.monotonic() - at < FOUND_FOR:
        return found
    if not _has_supervisor():
        return None
    try:
        addon = find_addon((await _supervisor(hass, "GET", "/addons"))["addons"])
        info = await _supervisor(hass, "GET", f"/addons/{addon['slug']}/info") if addon else None
    except (ClientError, TimeoutError, KeyError, ValueError) as failure:
        _LOGGER.warning("Couldn't ask the Supervisor for the card design add-on: %s", failure)
        return found
    state["design_server"] = (info, time.monotonic())
    return info


def _has_supervisor():
    return bool(os.environ.get("SUPERVISOR") and os.environ.get("SUPERVISOR_TOKEN"))


async def _supervisor(hass, method, path, body=None):
    """The `data` of the Supervisor's answer to [method] [path], as Core asks it."""
    async with async_get_clientsession(hass).request(
        method,
        f"http://{os.environ['SUPERVISOR']}{path}",
        json=body,
        headers={"Authorization": f"Bearer {os.environ['SUPERVISOR_TOKEN']}"},
        timeout=SUPERVISOR_TIMEOUT,
    ) as response:
        response.raise_for_status()
        return (await response.json())["data"]


def _username(user):
    """The person's Home Assistant username, as ingress names them (`X-Remote-User-Name`); None
    for a user without one (signed in through another provider)."""
    for credential in user.credentials:
        if credential.auth_provider_type == "homeassistant":
            return credential.data.get("username")
    return None


@websocket_api.websocket_command({vol.Required("type"): "terrazzo/design_server"})
@websocket_api.async_response
async def ws_design_server(hass, connection, msg):
    """Whether this Home Assistant has a private design server, and where: `url` for the app's API
    calls (with its token), `panel` for a browser, signed in to Home Assistant."""
    info = await async_find(hass, fresh=True)
    if info is None or info.get("state") != "started":
        connection.send_result(msg["id"], {"available": False})
        return
    connection.send_result(
        msg["id"],
        {
            "available": True,
            "url": DESIGNS_URL,
            "panel": f"/hassio/ingress/{info['slug']}",
            "version": info.get("version"),
        },
    )


@websocket_api.websocket_command(
    {vol.Required("type"): "terrazzo/design_server/open", vol.Required("path"): str}
)
@websocket_api.require_admin
@callback
def ws_open(hass, connection, msg):
    """A one-time link (OPEN_URL) that signs a browser in to the add-on's ingress, as the admin-only
    panel would, and opens [path] there: a page the design server's API named, such as the
    `/ui-builder/<id>` Card Studio opened. Good for one use within a minute."""
    path = page_path(msg["path"])
    if path is None:
        connection.send_error(msg["id"], "invalid_format", "Not a design server page")
        return
    links = hass.data.setdefault(DOMAIN, {}).setdefault("design_links", {})
    now = time.monotonic()
    for nonce in [nonce for nonce, link in links.items() if link[2] < now]:
        del links[nonce]
    nonce = secrets.token_urlsafe(32)
    links[nonce] = (connection.user.id, path, now + LINK_FOR)
    connection.send_result(msg["id"], {"url": f"{OPEN_URL}/{nonce}"})


class TerrazzoDesignsOpenView(HomeAssistantView):
    """Spends a one-time link: an ingress session for its user, then the page it names."""

    url = OPEN_URL + "/{nonce}"
    name = "api:terrazzo:designs-open"
    # The browser opening it holds no Home Assistant token; the link stands for the user who asked.
    requires_auth = False

    def __init__(self, hass):
        self.hass = hass

    async def get(self, request, nonce):
        link = self.hass.data.get(DOMAIN, {}).get("design_links", {}).pop(nonce, None)
        if link is None or link[2] < time.monotonic():
            return web.Response(
                status=410,
                text="This link was used or has expired. Open the design from the app again.",
            )
        user_id, path, _ = link
        info = await async_find(self.hass)
        if info is None or not info.get("ingress_url"):
            return web.Response(status=404, text="The card design add-on isn't running.")
        try:
            session = (
                await _supervisor(self.hass, "POST", "/ingress/session", {"user_id": user_id})
            )["session"]
        except (ClientError, TimeoutError, KeyError, ValueError) as failure:
            _LOGGER.warning("Couldn't open an ingress session for the card design add-on: %s", failure)
            return web.Response(status=502, text="Home Assistant couldn't open the add-on.")
        response = web.HTTPFound(info["ingress_url"].rstrip("/") + "/" + path)
        # As Home Assistant's frontend sets it for an ingress panel.
        response.set_cookie(
            "ingress_session",
            session,
            path="/api/hassio_ingress/",
            secure=request.secure,
            httponly=True,
            samesite="Lax",
        )
        response.headers["Cache-Control"] = "no-store"
        return response


class TerrazzoDesignsView(HomeAssistantView):
    """The design server's app API, for a signed-in Home Assistant user."""

    url = DESIGNS_URL + "/{path:.*}"
    name = "api:terrazzo:designs"
    requires_auth = True

    def __init__(self, hass):
        self.hass = hass

    async def _forward(self, request, path):
        path = app_path(path)
        if path is None:
            return web.json_response({"message": "Not part of the design server's API."}, status=404)
        info = await async_find(self.hass)
        if info is None or not info.get("hostname"):
            return web.json_response({"message": "No card design add-on."}, status=404)
        headers = upstream_headers(
            request.headers, _username(request["hass_user"]), request.host
        )
        body = await request.read() if request.body_exists else None
        try:
            async with async_get_clientsession(self.hass).request(
                request.method,
                f"http://{info['hostname']}:{ADDON_PORT}/{path}",
                params=request.query,
                data=body,
                headers=headers,
                allow_redirects=False,
                timeout=DESIGN_TIMEOUT,
            ) as response:
                return web.Response(
                    status=response.status,
                    body=await response.read(),
                    headers=downstream_headers(response.headers),
                )
        except (ClientError, TimeoutError) as failure:
            # Maybe moved or restarted: look again next time.
            self.hass.data.get(DOMAIN, {}).pop("design_server", None)
            _LOGGER.warning("Couldn't reach the card design add-on: %s", failure)
            return web.json_response({"message": "The card design add-on didn't answer."}, status=502)

    get = post = put = delete = _forward


@callback
def async_register(hass):
    hass.http.register_view(TerrazzoDesignsView(hass))
    hass.http.register_view(TerrazzoDesignsOpenView(hass))
    websocket_api.async_register_command(hass, ws_design_server)
    websocket_api.async_register_command(hass, ws_open)
