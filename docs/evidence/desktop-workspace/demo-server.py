#!/usr/bin/env python3
"""Local, synthetic Home Assistant WebSocket fixture for desktop screenshots.

Requires websockets 16.0. Pass a local test certificate and key for HTTPS on port 18124.
Connect to https://localhost:18124 using desktop-demo-token.
Nothing connects to a real home and dashboard writes are deliberately unsupported.
"""

import asyncio
import argparse
import json
import ssl
from websockets.asyncio.server import serve


STATES = [
    {"entity_id": "sensor.demo_temperature", "state": "21.8",
     "attributes": {"friendly_name": "Living room", "unit_of_measurement": "°C"}},
    {"entity_id": "sensor.demo_humidity", "state": "42",
     "attributes": {"friendly_name": "Humidity", "unit_of_measurement": "%"}},
    {"entity_id": "sensor.demo_battery", "state": "87",
     "attributes": {"friendly_name": "Window sensor battery", "unit_of_measurement": "%"}},
]


def dashboard(upstairs):
    return {"title": "Upstairs" if upstairs else "Overview", "views": [{
        "title": "Home", "path": "home", "cards": [
            {"type": "markdown", "content": "## " + ("Upstairs" if upstairs else "Welcome home")
             + "\nA desktop workspace for your dashboards.\n\nSynthetic data · local test server"},
            {"type": "entities", "title": "Window sensors" if upstairs else "Live comfort",
             "entities": ["sensor.demo_battery"] if upstairs else ["sensor.demo_temperature", "sensor.demo_humidity"]},
        ],
    }]}


async def handle(socket):
    await socket.send(json.dumps({"type": "auth_required", "ha_version": "2026.11.0"}))
    async for raw in socket:
        message = json.loads(raw)
        kind = message["type"]
        if kind == "auth":
            valid = message.get("access_token") == "desktop-demo-token"
            await socket.send(json.dumps({"type": "auth_ok" if valid else "auth_invalid",
                                          "ha_version": "2026.11.0"}))
            if not valid:
                return
            continue
        results = {
            "lovelace/dashboards/list": [{"url_path": "upstairs", "title": "Upstairs"}],
            "lovelace/config": dashboard(message.get("url_path") == "upstairs"),
            "get_states": STATES,
            "get_config": {"components": [], "unit_system": {"temperature": "°C"}},
            "subscribe_events": None, "unsubscribe_events": None,
            "config/entity_registry/list_for_display": {"entities": []},
            "config/device_registry/list": [], "config/area_registry/list": [],
            "terrazzo/design_server": {"available": False},
        }
        reply = {"id": message["id"], "type": "result", "success": kind in results}
        if kind in results:
            reply["result"] = results[kind]
        else:
            reply["error"] = {"code": "unknown_command", "message": "Not supported by this screenshot fixture"}
        await socket.send(json.dumps(reply))


async def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cert", required=True)
    parser.add_argument("--key", required=True)
    args = parser.parse_args()
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.load_cert_chain(args.cert, args.key)
    async with serve(handle, "127.0.0.1", 18124, ssl=tls):
        print("Synthetic HA fixture: https://localhost:18124", flush=True)
        await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())
