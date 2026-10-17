"""Public read-only HA facade. Client input never selects an upstream request."""
import asyncio
import contextlib
import json
import os
from pathlib import Path
import time

import aiohttp
from aiohttp import web

ROOT = Path(__file__).parent
VERSION = "demo-readonly-v1"
CONFIG = {
    "version": VERSION, "location_name": "Demo Home", "time_zone": "UTC",
    "unit_system": {"temperature": "°C", "length": "km", "mass": "kg", "volume": "L"},
    "internal_url": None, "external_url": "https://ha.coo.ee/demo-ha",
    "components": [], "state": "RUNNING", "read_only": True,
}
ATTRIBUTES = frozenset((
    "friendly_name", "unit_of_measurement", "device_class", "state_class", "icon",
    "brightness", "color_temp_kelvin", "rgb_color", "supported_color_modes", "color_mode",
    "temperature", "current_temperature", "hvac_modes", "hvac_action", "min_temp", "max_temp",
    "humidity", "pressure", "wind_speed", "temperature_unit", "pressure_unit", "wind_speed_unit",
    "current_position", "supported_features", "battery_level", "percentage",
))


class Gateway:
    def __init__(self, upstream, token, poll_seconds=5, max_age=15):
        self.upstream = upstream.rstrip("/")
        self.token = token
        self.poll_seconds = poll_seconds
        self.max_age = max_age
        self.dashboard = json.loads((ROOT / "dashboard.json").read_text())
        self.entities = frozenset(json.loads((ROOT / "entities.json").read_text()))
        self.states = {}
        self.updated = 0
        self.sockets = {}

    def fresh(self):
        return self.updated > 0 and time.monotonic() - self.updated < self.max_age

    def sanitize(self, rows):
        return {
            row["entity_id"]: {
                "entity_id": row["entity_id"], "state": row["state"],
                "attributes": {k: v for k, v in row.get("attributes", {}).items() if k in ATTRIBUTES},
                "last_changed": row["last_changed"], "last_updated": row["last_updated"],
                "context": {"id": None, "parent_id": None, "user_id": None},
            }
            for row in rows if row.get("entity_id") in self.entities
        }

    async def poll_once(self, session):
        # This is the entire runtime upstream API. No client URL, method, body or headers enter it.
        async with session.get(
            self.upstream + "/api/states", headers={"Authorization": "Bearer " + self.token},
            allow_redirects=False,
        ) as response:
            response.raise_for_status()
            states = self.sanitize(await response.json())
        if states.keys() != self.entities:
            raise ValueError("Demo entities are not all ready")
        # Entity IDs arrive before template sensors have their first value. Do not declare the
        # initial snapshot healthy until initialization completes. Later unavailable states are
        # real live updates and must still reach clients.
        if not self.updated and any(row['state'] in ('unknown', 'unavailable') for row in states.values()):
            raise ValueError("Demo entities are still initializing")
        old = self.states
        self.states, self.updated = states, time.monotonic()
        for entity in old.keys() | states.keys():
            if old.get(entity) == states.get(entity):
                continue
            event = {"event_type": "state_changed", "data": {
                "entity_id": entity, "old_state": old.get(entity), "new_state": states.get(entity),
            }, "origin": "LOCAL", "time_fired": states.get(entity, old.get(entity))["last_updated"],
                "context": {"id": None, "parent_id": None, "user_id": None}}
            for ws, subscriptions in list(self.sockets.items()):
                for subscription in list(subscriptions):
                    try:
                        await asyncio.wait_for(ws.send_json({
                            "id": subscription, "type": "event", "event": event,
                        }), timeout=1)
                    except (ConnectionError, asyncio.TimeoutError, RuntimeError):
                        await ws.close()

    async def lifecycle(self, app):
        async def polling():
            async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=5)) as session:
                while True:
                    try:
                        await self.poll_once(session)
                    except (aiohttp.ClientError, asyncio.TimeoutError, ValueError, KeyError, TypeError):
                        # Never log upstream bodies or credentials; fail closed when cache expires.
                        if not self.fresh():
                            for ws in list(self.sockets):
                                await ws.close(code=1013, message=b"Demo temporarily unavailable")
                    await asyncio.sleep(self.poll_seconds)
        task = asyncio.create_task(polling())
        yield
        task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await task
        for ws in list(self.sockets):
            await ws.close(code=1001)

    async def http(self, request):
        if request.method == "OPTIONS":
            return web.Response(status=204)
        if request.method not in ("GET", "HEAD"):
            return web.json_response({"code": "read_only", "message": "Demo is read-only"}, status=403)
        path = request.path
        if path == "/api/websocket":
            return await self.websocket(request)
        if path not in ("/healthz", "/api/", "/api/config", "/api/states", "/dashboard"):
            if not (path.startswith("/api/states/") and path[12:] in self.entities):
                return web.json_response({"code": "not_found"}, status=404)
        if not self.fresh():
            return web.json_response({"code": "upstream_unavailable"}, status=503)
        if path == "/healthz":
            return web.json_response({"status": "ok", "read_only": True})
        if path == "/api/":
            return web.json_response({"message": "API running.", "read_only": True})
        if path == "/api/config":
            return web.json_response(CONFIG)
        if path == "/dashboard":
            return web.json_response(self.dashboard)
        if path == "/api/states":
            return web.json_response(list(self.states.values()))
        state = self.states.get(path[12:])
        return web.json_response(state if state else {"code": "not_found"}, status=200 if state else 404)

    async def websocket(self, request):
        if len(self.sockets) >= 100:
            return web.json_response({"code": "too_many_connections"}, status=429)
        if not self.fresh():
            return web.json_response({"code": "upstream_unavailable"}, status=503)
        ws = web.WebSocketResponse(heartbeat=30, max_msg_size=16384, receive_timeout=90)
        await ws.prepare(request)
        self.sockets[ws] = set()
        try:
            await ws.send_json({"type": "auth_required", "ha_version": VERSION})
            try:
                auth = await asyncio.wait_for(ws.receive_json(), timeout=10)
            except (ValueError, TypeError, asyncio.TimeoutError):
                return ws
            if not isinstance(auth, dict) or auth.get("type") != "auth":
                await ws.send_json({"type": "auth_invalid", "message": "Expected public demo auth"})
                return ws
            # Compatibility handshake only. No credential is needed or forwarded.
            await ws.send_json({"type": "auth_ok", "ha_version": VERSION})
            async for frame in ws:
                if frame.type != aiohttp.WSMsgType.TEXT:
                    break
                try:
                    msg = json.loads(frame.data)
                except ValueError:
                    await ws.close(code=1007, message=b"Expected JSON object")
                    break
                if not isinstance(msg, dict) or type(msg.get("id")) is not int or msg["id"] <= 0:
                    await ws.close(code=1008, message=b"Expected positive integer id")
                    break
                reply = await self.command(ws, msg)
                await ws.send_json(reply)
        finally:
            self.sockets.pop(ws, None)
            await ws.close()
        return ws

    async def command(self, ws, msg):
        ident, kind = msg["id"], msg.get("type")
        def result(value=None):
            return {"id": ident, "type": "result", "success": True, "result": value}
        def error(code):
            return {"id": ident, "type": "result", "success": False,
                    "error": {"code": code, "message": code.replace("_", " ")}}
        if kind == "ping":
            return {"id": ident, "type": "pong"}
        if kind == "get_states":
            return result(list(self.states.values())) if self.fresh() else error("upstream_unavailable")
        if kind == "get_config":
            return result(CONFIG)
        if kind == "lovelace/config":
            return result(self.dashboard) if msg.get("url_path") in (None, "lovelace", "demo") else error("not_found")
        if kind == "lovelace/dashboards/list":
            return result([{"url_path": "demo", "title": "Demo Home", "icon": "mdi:home", "mode": "yaml", "require_admin": False}])
        if kind == "config/entity_registry/list_for_display":
            return result({"entities": []})
        if kind in ("config/device_registry/list", "config/area_registry/list"):
            return result([])
        if kind == "subscribe_events":
            if msg.get("event_type") != "state_changed":
                return error("read_only")
            if self.sockets[ws]:
                return error("subscription_limit")
            self.sockets[ws].add(ident)
            return result()
        if kind == "unsubscribe_events":
            sub = msg.get("subscription")
            if type(sub) is not int or sub not in self.sockets[ws]:
                return error("not_found")
            self.sockets[ws].remove(sub)
            return result()
        # Deny by default, including unknown future HA commands and every mutation.
        return error("read_only")


@web.middleware
async def headers(request, handler):
    response = await handler(request)
    response.headers.update({
        "Access-Control-Allow-Origin": "https://ha.coo.ee",
        "Access-Control-Allow-Methods": "GET, HEAD, OPTIONS",
        "Access-Control-Allow-Headers": "Content-Type, Authorization",
        "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff",
    })
    return response


def create_app(gateway, poll=True):
    app = web.Application(middlewares=[headers], client_max_size=16384)
    app.router.add_route("*", "/{path:.*}", gateway.http)
    if poll:
        app.cleanup_ctx.append(gateway.lifecycle)
    return app


if __name__ == "__main__":
    token = Path(os.environ.get("HA_TOKEN_FILE", "/run/secrets/ha_token")).read_text().strip()
    if not token:
        raise SystemExit("Private HA token file is empty")
    web.run_app(create_app(Gateway(os.environ.get("HA_URL", "http://homeassistant:8123"), token)),
                host="0.0.0.0", port=8080, access_log=None)
