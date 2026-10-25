#!/usr/bin/env python3
"""Local-only Wasmo routing/settings stand-in. Uses fake HA data and test-only owner cookies."""
import argparse
import http.server
import json
import re
import threading
from pathlib import Path
from urllib.parse import urlparse

API = '/api/wasmo/v1/'
KEY = re.compile(r'[a-z0-9][a-z0-9._-]{0,63}\Z')
LOCK = threading.Lock()
VALUES = {}
FAILURE = 0
AUTH_FAILURE = 0
SESSION = None
INITIALIZED = False

# Seed a fake device-local HA sign-in. No real credentials or Home Assistant are used.
FIXTURE_JS = r'''
localStorage.setItem('terrazzo-oauth', JSON.stringify({hassUrl: 'https://ha.example.test',
  clientId: location.origin + '/', access_token: 'fixture-only', refresh_token: 'fixture-only',
  expires: Date.now() + 3600000}));
window.__fixtureCommands = [];
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('distribution', type=Path)
    parser.add_argument('--before', type=Path)
    parser.add_argument('--port', type=int, default=8765)
    parser.add_argument('--cloud-session', action='store_true', help='Enable fake private HA custody; no real HA calls')
    parser.add_argument('--static', action='store_true', help='API 404s; serve static browser mode')
    args = parser.parse_args()
    root = args.distribution.resolve()
    before = args.before.resolve() if args.before else None

    class Handler(http.server.BaseHTTPRequestHandler):
        def respond(self, status, value=None, content_type='application/json', headers=None):
            data = (json.dumps(value).encode() if content_type == 'application/json'
                    else value if isinstance(value, bytes) else (value or '').encode())
            if status == 204:
                data = b''
            self.send_response(status)
            self.send_header('Content-Type', content_type)
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Content-Length', str(len(data)))
            for key, value in (headers or {}).items():
                self.send_header(key, value)
            self.end_headers()
            self.wfile.write(data)

        def owner(self):
            return 'wasmo-fixture-owner=1' in self.headers.get('Cookie', '').split('; ')

        def dispatch(self):
            global FAILURE, AUTH_FAILURE, SESSION, INITIALIZED
            path = urlparse(self.path).path
            if path == '/__fixture__/owner' and self.command == 'GET':
                return self.respond(302, headers={'Set-Cookie': 'wasmo-fixture-owner=1; HttpOnly; SameSite=Strict; Path=/', 'Location': '/'})
            if path.startswith(API):
                if args.static or not self.owner():
                    return self.respond(404, {'error': 'not-found'})
                if self.command != 'GET' and self.headers.get('X-Wasmo-Api') != '1':
                    return self.respond(403, {'error': 'csrf'})
                route = path[len(API):]
                if FAILURE and route != 'capabilities':
                    return self.respond(FAILURE, {'error': 'fixture-failure'})
                if route == 'capabilities' and self.command == 'GET':
                    return self.respond(200, {'api': 1, 'backend': 'fixture', 'kv': True, 'kvImport': True, 'haSession': args.cloud_session, 'publish': False})
                if route.startswith('ha/') and args.cloud_session:
                    if AUTH_FAILURE:
                        return self.respond(AUTH_FAILURE, {'error': 'fixture-auth-failure'})
                    if route == 'ha/session' and self.command == 'GET':
                        return self.respond(200, SESSION or {'signedIn': False, 'sessionId': 'signed-out' if INITIALIZED else ''})
                    value = json.loads(self.rfile.read(int(self.headers.get('Content-Length', 0))))
                    with LOCK:
                        if route == 'ha/session/import' or (route == 'ha/session' and self.command == 'PUT'):
                            if route.endswith('/import') and INITIALIZED:
                                return self.respond(409, {'error': 'already-initialized'})
                            INITIALIZED = True
                            SESSION = {'signedIn': True, 'sessionId': 'fixture-only-session', 'hassUrl': value['hassUrl']}
                            return self.respond(200, SESSION)
                        if not SESSION and route == 'ha/session' and self.command == 'DELETE':
                            INITIALIZED = True
                            return self.respond(200, {'signedIn': False})
                        if not SESSION or SESSION['sessionId'] != value.get('sessionId'):
                            return self.respond(401, {'error': 'session-changed'})
                        if route == 'ha/token' and self.command == 'POST':
                            return self.respond(200, dict(SESSION, access_token='fixture-only-cloud-access', expires=9999999999999))
                        if route == 'ha/session' and self.command == 'DELETE':
                            SESSION = None
                            INITIALIZED = True
                            return self.respond(200, {'signedIn': False})
                    return self.respond(404, {'error': 'not-found'})
                if route == 'kv' and self.command == 'GET':
                    with LOCK:
                        return self.respond(200, dict(VALUES))
                key = route.removeprefix('kv/')
                if not route.startswith('kv/') or not KEY.fullmatch(key):
                    return self.respond(400, {'error': 'invalid-key'})
                is_import = key == 'import' and self.command == 'POST'
                if self.command not in ('PUT', 'DELETE') and not is_import:
                    return self.respond(404, {'error': 'not-found'})
                if self.command != 'DELETE':
                    size = int(self.headers.get('Content-Length', 0))
                    if size > (1024 * 1024 if is_import else 256 * 1024):
                        return self.respond(413, {'error': 'too-large'})
                    if self.headers.get('Content-Type', '').split(';')[0] != 'application/json':
                        return self.respond(415, {'error': 'json-required'})
                    try:
                        value = json.loads(self.rfile.read(size))
                    except (ValueError, RecursionError):
                        return self.respond(400, {'error': 'invalid-json'})
                with LOCK:
                    if is_import:
                        if not isinstance(value, dict) or any(not KEY.fullmatch(k) for k in value):
                            return self.respond(400, {'error': 'invalid-import'})
                        if VALUES:
                            return self.respond(409, {'error': 'already-initialized'})
                        candidate = value
                    else:
                        candidate = dict(VALUES)
                        if self.command == 'DELETE':
                            candidate.pop(key, None)
                        else:
                            candidate[key] = value
                    encoded = {k: json.dumps(v, ensure_ascii=False).encode() for k, v in candidate.items()}
                    if any(len(v) > 256 * 1024 for v in encoded.values()) or sum(len(k.encode()) + len(v) for k, v in encoded.items()) > 1024 * 1024:
                        return self.respond(413, {'error': 'storage-limit'})
                    VALUES.clear()
                    VALUES.update(candidate)
                    return self.respond(204)
            if path == '/__fixture__/auth-failure' and self.owner() and self.command == 'PUT':
                AUTH_FAILURE = int(self.rfile.read(int(self.headers.get('Content-Length', 0))))
                return self.respond(204)
            if path == '/__fixture__/failure' and self.owner() and self.command == 'PUT':
                FAILURE = int(self.rfile.read(int(self.headers.get('Content-Length', 0))))
                return self.respond(204)
            if not args.static and not self.owner():
                # Wasmo's private archive/API are owner-only; no sign-in redirect is simulated.
                return self.respond(404, {'error': 'not-found'})
            if self.command != 'GET':
                return self.respond(404, {'error': 'not-found'})
            if path == '/__fixture__/ha.js':
                return self.respond(200, 'window.__fixtureCommands = [];' if args.cloud_session else FIXTURE_JS, 'text/javascript')
            directory = before if path.startswith('/before/') else root
            relative = path.removeprefix('/before/') if path.startswith('/before/') else path.lstrip('/')
            file = (directory / (relative or 'index.html')).resolve() if directory else None
            if file is None or not file.is_relative_to(directory) or not file.is_file():
                return self.respond(404, {'error': 'not-found'})
            data = file.read_bytes()
            if file.suffix == '.html':
                data = data.replace(b'<head>', b'<head><script src="/__fixture__/ha.js"></script>')
            types = {'.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.wasm': 'application/wasm', '.css': 'text/css', '.json': 'application/json'}
            return self.respond(200, data, types.get(file.suffix, 'application/octet-stream'))

        do_GET = dispatch
        do_POST = dispatch
        do_PUT = dispatch
        do_DELETE = dispatch

        def log_message(self, *args):
            pass

    server = http.server.ThreadingHTTPServer(('127.0.0.1', args.port), Handler)
    print(f'Wasmo fixture ready at http://127.0.0.1:{server.server_port}/__fixture__/owner', flush=True)
    server.serve_forever()


if __name__ == '__main__':
    main()
