"""Public HA facade with explicitly allowlisted demo entity controls."""
import asyncio
import contextlib
import json
import math
import os
from pathlib import Path
import time

import aiohttp
from aiohttp import web

ROOT = Path(__file__).parent
VERSION = "demo-controls-v4"
CONFIG = {
    "version": VERSION, "location_name": "Demo Home", "time_zone": "UTC",
    "unit_system": {"temperature": "°C", "length": "km", "mass": "kg", "volume": "L"},
    "internal_url": None, "external_url": "https://ha.coo.ee/demo-ha",
    "components": [], "state": "RUNNING", "read_only": False,
}
ATTRIBUTES = frozenset((
    "friendly_name", "unit_of_measurement", "device_class", "state_class", "icon",
    "brightness", "color_temp_kelvin", "rgb_color", "supported_color_modes", "color_mode",
    "temperature", "current_temperature", "hvac_modes", "hvac_action", "min_temp", "max_temp", "target_temp_step",
    "humidity", "pressure", "wind_speed", "temperature_unit", "pressure_unit", "wind_speed_unit",
    "current_position", "supported_features", "battery_level", "percentage",
))
# Per-socket delivery bounds. A visitor that stops reading is disconnected, never waited on.
SEND_TIMEOUT = 1.0
CLOSE_TIMEOUT = 5.0
OUTBOX_LIMIT = 64  # several full broadcasts of every demo entity plus pending replies
_CLOSE = object()


class Outbox:
    """Ordered, bounded sender owned by one WebSocket.

    Broadcasts and replies only enqueue; this socket's own task performs every network write,
    in order. A full queue or a send that outlives SEND_TIMEOUT aborts this socket alone, so a
    slow visitor cannot stall polling, call_service or anyone else.
    """

    def __init__(self, ws, limit=OUTBOX_LIMIT, timeout=SEND_TIMEOUT):
        self.ws, self.timeout = ws, timeout
        self.queue = asyncio.Queue(limit)
        self.closer = None
        self.finishing = False
        self.sender = asyncio.create_task(self._drain())

    def send(self, message):
        """Queue a message without waiting; False if this socket is (now) being dropped."""
        if self.closer or self.finishing:
            return False
        try:
            self.queue.put_nowait(message)
        except asyncio.QueueFull:
            self.abort()
            return False
        return True

    def finish(self, code=aiohttp.WSCloseCode.OK, message=b""):
        """Close after already-queued messages are delivered, without waiting here."""
        if self.closer or self.finishing:
            return
        try:
            self.queue.put_nowait((_CLOSE, code, message))
            self.finishing = True
        except asyncio.QueueFull:
            self.abort(code, message)

    def abort(self, code=aiohttp.WSCloseCode.TRY_AGAIN_LATER, message=b"Client too slow"):
        """Discard queued messages and close in the background."""
        if self.closer is None:
            self.sender.cancel()
            self.closer = asyncio.create_task(self._close(code, message))
        return self.closer

    async def shutdown(self):
        """Flush and close within a bound, then force-close if the visitor still is not reading."""
        self.finish()
        await asyncio.wait({self.sender}, timeout=CLOSE_TIMEOUT)
        if not self.sender.done():
            self.abort()
        if self.closer:
            await self.closer

    async def _close(self, code, message):
        await asyncio.wait({self.sender})
        await self._close_ws(code, message)

    async def _close_ws(self, code, message):
        # A timed-out close tears down the transport instead of waiting for the visitor.
        with contextlib.suppress(ConnectionError, asyncio.TimeoutError, RuntimeError):
            await asyncio.wait_for(self.ws.close(code=code, message=message), CLOSE_TIMEOUT)

    async def _drain(self):
        while True:
            message = await self.queue.get()
            if isinstance(message, tuple) and message[0] is _CLOSE:
                await self._close_ws(message[1], message[2])
                return
            try:
                await asyncio.wait_for(self.ws.send_json(message), self.timeout)
            except (ConnectionError, asyncio.TimeoutError, RuntimeError):
                self.abort()
                return


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
        self.outboxes = {}
        self.upstream_lock = asyncio.Lock()
        # One budget for the entire shared home, across HTTP, sockets and reconnects.
        # No client-controlled identity, proxy header or unbounded per-IP map.
        self.action_tokens = 8.0
        self.action_updated = time.monotonic()

    def reserve_action(self):
        now = time.monotonic()
        self.action_tokens = min(8.0, self.action_tokens + max(0, now - self.action_updated) / 2)
        self.action_updated = now
        if self.action_tokens < 1:
            return False
        self.action_tokens -= 1
        return True

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
        async with self.upstream_lock:
            await self._poll_once(session)

    async def _poll_once(self, session):
        # The read path is fixed; credentials and response metadata stay private.
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
            # Enqueue only: each socket's own sender writes, so the upstream lock is never
            # held across a visitor's network I/O.
            for ws, subscriptions in list(self.sockets.items()):
                outbox = self.outboxes.get(ws)
                for subscription in list(subscriptions):
                    if outbox is None or not outbox.send({
                        "id": subscription, "type": "event", "event": event,
                    }):
                        break

    async def call_service(self, domain, service, data, target=None):
        # Construct a fresh request from a finite policy, never relay client payloads.
        controls = {"light": "light.demo_lamp", "switch": "switch.demo_coffee",
                    "climate": "climate.demo_thermostat"}
        if not isinstance(domain, str) or domain not in controls:
            return "forbidden"
        climate = domain == "climate"
        services = ("set_temperature",) if climate else ("turn_on", "turn_off", "toggle")
        if not isinstance(service, str) or service not in services:
            return "forbidden"
        allowed = {"entity_id", "temperature"} if climate else {"entity_id"}
        if not isinstance(data, dict) or set(data) - allowed:
            return "forbidden"
        temperature = data.get("temperature")
        if climate and (type(temperature) not in (int, float) or not math.isfinite(temperature)
                        or not 16 <= temperature <= 28 or temperature * 2 != int(temperature * 2)):
            return "forbidden"
        if target is not None and (not isinstance(target, dict) or set(target) != {"entity_id"}):
            return "forbidden"
        if target is not None and "entity_id" in data:
            return "forbidden"
        entity = (target if target is not None else data).get("entity_id")
        if isinstance(entity, list) and len(entity) == 1:
            entity = entity[0]
        if entity != controls[domain] or entity not in self.entities:
            return "forbidden"
        async with self.upstream_lock:
            if not self.fresh():
                return "upstream_unavailable"
            if not self.reserve_action():
                return "rate_limited"
            try:
                async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=5)) as session:
                    async with session.post(
                        self.upstream + "/api/services/" + domain + "/" + service,
                        headers={"Authorization": "Bearer " + self.token},
                        json={"entity_id": controls[domain],
                              **({"temperature": temperature} if climate else {})},
                        allow_redirects=False,
                    ) as response:
                        response.raise_for_status()
                        if response.status != 200:
                            return "service_failed"
                    await self._poll_once(session)
            except (aiohttp.ClientError, asyncio.TimeoutError, ValueError, KeyError, TypeError):
                return "upstream_unavailable"
        return None

    async def lifecycle(self, app):
        async def polling():
            async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=5)) as session:
                while True:
                    try:
                        await self.poll_once(session)
                    except (aiohttp.ClientError, asyncio.TimeoutError, ValueError, KeyError, TypeError):
                        # Never log upstream bodies or credentials; fail closed when cache expires.
                        if not self.fresh():
                            for outbox in list(self.outboxes.values()):
                                outbox.finish(aiohttp.WSCloseCode.TRY_AGAIN_LATER,
                                              b"Demo temporarily unavailable")
                    await asyncio.sleep(self.poll_seconds)
        task = asyncio.create_task(polling())
        yield
        task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await task
        for outbox in list(self.outboxes.values()):
            outbox.finish(aiohttp.WSCloseCode.GOING_AWAY)
        await asyncio.gather(*(outbox.shutdown() for outbox in list(self.outboxes.values())))

    async def http(self, request):
        if request.method == "OPTIONS":
            return web.Response(status=204)
        if request.method == "POST" and request.path.startswith("/api/services/"):
            parts = request.path.split("/")
            if len(parts) != 5:
                return web.json_response({"code": "forbidden"}, status=403)
            try:
                data = await request.json()
            except (ValueError, TypeError):
                return web.json_response({"code": "invalid_json"}, status=400)
            code = await self.call_service(parts[3], parts[4], data)
            if code:
                status = {"forbidden": 403, "rate_limited": 429}.get(code, 503)
                return web.json_response({"code": code}, status=status,
                                         headers={"Retry-After": "2"} if status == 429 else None)
            return web.json_response(list(self.states.values()))
        if request.method not in ("GET", "HEAD"):
            return web.json_response({"code": "forbidden", "message": "Demo administration is disabled"}, status=403)
        path = request.path
        if path == "/api/websocket":
            return await self.websocket(request)
        if path not in ("/healthz", "/api/", "/api/config", "/api/states", "/dashboard"):
            if not (path.startswith("/api/states/") and path[12:] in self.entities):
                return web.json_response({"code": "not_found"}, status=404)
        if not self.fresh():
            return web.json_response({"code": "upstream_unavailable"}, status=503)
        if path == "/healthz":
            return web.json_response({"status": "ok", "read_only": False})
        if path == "/api/":
            return web.json_response({"message": "API running.", "read_only": False})
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
        outbox = Outbox(ws)
        self.sockets[ws] = set()
        self.outboxes[ws] = outbox
        try:
            outbox.send({"type": "auth_required", "ha_version": VERSION})
            try:
                auth = await asyncio.wait_for(ws.receive_json(), timeout=10)
            except (ValueError, TypeError, asyncio.TimeoutError):
                return ws
            if not isinstance(auth, dict) or auth.get("type") != "auth":
                outbox.send({"type": "auth_invalid", "message": "Expected public demo auth"})
                return ws
            # Compatibility handshake only. No credential is needed or forwarded.
            outbox.send({"type": "auth_ok", "ha_version": VERSION})
            async for frame in ws:
                if frame.type != aiohttp.WSMsgType.TEXT:
                    break
                try:
                    msg = json.loads(frame.data)
                except ValueError:
                    outbox.finish(aiohttp.WSCloseCode.INVALID_TEXT, b"Expected JSON object")
                    break
                if not isinstance(msg, dict) or type(msg.get("id")) is not int or msg["id"] <= 0:
                    outbox.finish(aiohttp.WSCloseCode.POLICY_VIOLATION, b"Expected positive integer id")
                    break
                reply = await self.command(ws, msg)
                if not outbox.send(reply):
                    break
        finally:
            self.sockets.pop(ws, None)
            self.outboxes.pop(ws, None)
            await outbox.shutdown()
        return ws

    async def command(self, ws, msg):
        ident, kind = msg["id"], msg.get("type")
        def result(value=None):
            return {"id": ident, "type": "result", "success": True, "result": value}
        def error(code):
            return {"id": ident, "type": "result", "success": False,
                    "error": {"code": code, "message": code.replace("_", " ")}}
        if kind == "call_service":
            if set(msg) - {"id", "type", "domain", "service", "service_data", "target", "return_response"}:
                return error("forbidden")
            if msg.get("return_response", False) is not False:
                return error("forbidden")
            code = await self.call_service(msg.get("domain"), msg.get("service"),
                                           msg.get("service_data", {}), msg.get("target"))
            return error(code) if code else result({"context": {"id": None, "parent_id": None, "user_id": None}, "response": None})
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
                return error("forbidden")
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
        # Deny by default, including unknown future HA commands and administrative mutations.
        return error("forbidden")


@web.middleware
async def headers(request, handler):
    response = await handler(request)
    response.headers.update({
        "Access-Control-Allow-Origin": "https://ha.coo.ee",
        "Access-Control-Allow-Methods": "GET, HEAD, POST, OPTIONS",
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
