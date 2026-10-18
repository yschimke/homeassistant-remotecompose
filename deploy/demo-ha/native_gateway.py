"""Native HA frontend with guest authentication and a deny-by-default API boundary."""
import asyncio
import contextlib
import json
import os
from pathlib import Path
import re
from urllib.parse import urlencode

import aiohttp
from aiohttp import web

ROOT = Path(__file__).parent
ENTITIES = frozenset(json.loads((ROOT / 'entities.json').read_text()))
STATIC_MARKDOWN = frozenset(card['content'] for view in json.loads((ROOT / 'dashboard.json').read_text())['views']
                            for card in view.get('cards', []) if card.get('type') == 'markdown'
                            and not any(marker in card.get('content', '') for marker in ('{{', '{%', '{#')))
READ_COMMANDS = frozenset({
    'auth/current_user', 'get_config', 'get_states', 'get_services', 'get_panels',
    'frontend/get_themes', 'frontend/get_translations', 'frontend/get_icons', 'frontend/get_user_data',
    'frontend/subscribe_user_data', 'frontend/subscribe_system_data',
    'lovelace/config', 'lovelace/dashboards/list', 'lovelace/resources',
    'config/entity_registry/list', 'config/entity_registry/list_for_display',
    'config/device_registry/list', 'config/area_registry/list', 'config/floor_registry/list',
    'config/label_registry/list', 'config_entries/get', 'person/list',
    'repairs/list_issues', 'recorder/info', 'brands/access_token',
})
READ_EVENTS = frozenset({'state_changed', 'core_config_updated', 'panels_updated',
                         'themes_updated', 'lovelace_updated', 'component_loaded',
                         'service_registered', 'service_removed', 'entity_registry_updated',
                         'device_registry_updated', 'area_registry_updated', 'floor_registry_updated',
                         'repairs_issue_registry_updated'})
FLOW = re.compile(r'^/auth/login_flow/[a-f0-9]+$')
STATIC = ('/frontend_latest/', '/frontend_es5/', '/static/')
BRAND_IMAGE = re.compile(r'^/api/brands/integration/[a-z0-9_]+/(?:dark_)?(?:icon|logo)(?:@2x)?\.png$')


def command_allowed(message):
    kind = message.get('type')
    if not isinstance(kind, str):
        return False
    if kind in {'lovelace/config', 'lovelace/info'}:
        return message.get('url_path') in (None, 'lovelace')
    if kind == 'render_template':
        # Native Markdown cards ask HA to render even literal text. Never execute arbitrary Jinja.
        return isinstance(message.get('template'), str) and message['template'] in STATIC_MARKDOWN
    if kind == 'subscribe_events':
        return isinstance(message.get('event_type'), str) and message['event_type'] in READ_EVENTS
    return kind in READ_COMMANDS or kind in {'ping', 'subscribe_entities', 'unsubscribe_events', 'supported_features'}


class NativeGateway:
    def __init__(self, upstream, user_id, public_url='https://ha-demo.coo.ee'):
        self.upstream = upstream.rstrip('/')
        self.user_id = user_id.strip()
        if not self.user_id:
            raise ValueError('Read-only user must be provisioned')
        self.public_url = public_url.rstrip('/')
        self.sockets = set()
        self.session = None

    async def lifecycle(self, app):
        async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=30)) as session:
            self.session = session
            yield
            for socket in list(self.sockets):
                await socket.close(code=1001)

    async def authenticated(self, token):
        upstream = await self.session.ws_connect(self.upstream + '/api/websocket', max_msg_size=2**20)
        greeting = await upstream.receive_json(timeout=10)
        await upstream.send_json({'type': 'auth', 'access_token': token})
        if (await upstream.receive_json(timeout=10)).get('type') != 'auth_ok':
            await upstream.close()
            raise web.HTTPUnauthorized()
        await upstream.send_json({'id': 1, 'type': 'auth/current_user'})
        reply = await upstream.receive_json(timeout=10)
        user = reply.get('result') or {}
        if not reply.get('success') or user.get('id') != self.user_id or user.get('is_admin') or user.get('is_owner'):
            await upstream.close()
            raise web.HTTPForbidden(text='Only the read-only demo login is accepted')
        return upstream, greeting.get('ha_version', '')

    async def proxy(self, request, *, body=None, authenticated=False, content_type=None):
        headers = {}
        if authenticated:
            token = request.headers.get('Authorization', '').removeprefix('Bearer ')
            upstream, _ = await self.authenticated(token)
            await upstream.close()
            headers['Authorization'] = 'Bearer ' + token
        if content_type or request.headers.get('Content-Type'):
            headers['Content-Type'] = content_type or request.headers['Content-Type']
        async with self.session.request(request.method, self.upstream + request.path_qs,
                                        data=body, headers=headers, allow_redirects=False) as response:
            data = await response.read()
            return web.Response(status=response.status, body=data,
                                headers={'Content-Type': response.headers.get('Content-Type', 'application/octet-stream')})

    async def http(self, request):
        path = request.path
        if '\\' in path or '..' in path.split('/'):
            raise web.HTTPNotFound()
        if path == '/api/websocket' and request.method == 'GET':
            return await self.websocket(request)
        if request.method in {'GET', 'HEAD'}:
            if path == '/healthz':
                return web.json_response({'status': 'ok', 'read_only': True, 'native_ui': True})
            if path == '/api/onboarding':
                raise web.HTTPNotFound()  # Onboarding is never exposed publicly.
            if path in {'/', '/lovelace', '/lovelace/home', '/lovelace/0', '/auth/authorize',
                        '/auth/providers', '/manifest.json', '/service_worker.js', '/robots.txt'} or path.startswith(STATIC) or BRAND_IMAGE.fullmatch(path):
                return await self.proxy(request)
            if path in {'/api/', '/api/config', '/api/states'} or (path.startswith('/api/states/') and path[12:] in ENTITIES):
                return await self.proxy(request, authenticated=True)
            raise web.HTTPNotFound()
        if request.method == 'POST' and (path == '/auth/login_flow' or FLOW.fullmatch(path)):
            try:
                data = await request.json()
            except (ValueError, TypeError):
                raise web.HTTPBadRequest() from None
            if not isinstance(data, dict) or not isinstance(data.get('client_id'), str) or data['client_id'].rstrip('/') != self.public_url:
                raise web.HTTPForbidden()
            if path == '/auth/login_flow':
                if data.get('handler') != ['homeassistant', None]:
                    raise web.HTTPForbidden()
                redirect = data.get('redirect_uri', '')
                if not isinstance(redirect, str) or not redirect.startswith(self.public_url + '/'):
                    raise web.HTTPForbidden()
            elif data.get('username') != 'demo':
                raise web.HTTPForbidden(text='Use the public demo login')
            return await self.proxy(request, body=json.dumps(data).encode())
        if request.method == 'POST' and path == '/auth/token':
            data = await request.post()
            if any(not isinstance(value, str) for value in data.values()):
                raise web.HTTPBadRequest()
            if (data.get('action') != 'revoke' or data.get('client_id') is not None) and data.get('client_id', '').rstrip('/') != self.public_url:
                raise web.HTTPForbidden()
            if data.get('grant_type') not in {'authorization_code', 'refresh_token'} and data.get('action') != 'revoke':
                raise web.HTTPForbidden()
            # Only normal session creation/refresh/revocation; no long-lived token API.
            return await self.proxy(request, body=urlencode(data).encode(), content_type='application/x-www-form-urlencoded')
        return web.json_response({'code': 'read_only', 'message': 'Demo is read-only'}, status=403)

    async def websocket(self, request):
        if len(self.sockets) >= 100:
            raise web.HTTPTooManyRequests()
        ws = web.WebSocketResponse(heartbeat=30, max_msg_size=16384, receive_timeout=90)
        await ws.prepare(request)
        self.sockets.add(ws)
        upstream = None
        receiver = None
        try:
            await ws.send_json({'type': 'auth_required', 'ha_version': '2026.10.2'})
            auth = await ws.receive_json(timeout=10)
            if not isinstance(auth, dict) or auth.get('type') != 'auth' or not isinstance(auth.get('access_token'), str):
                return ws
            try:
                upstream, version = await self.authenticated(auth['access_token'])
            except (web.HTTPUnauthorized, web.HTTPForbidden):
                await ws.send_json({'type': 'auth_invalid', 'message': 'Use the read-only demo login'})
                return ws
            await ws.send_json({'type': 'auth_ok', 'ha_version': version})

            async def receive_upstream():
                async for message in upstream:
                    if message.type != aiohttp.WSMsgType.TEXT:
                        break
                    payload = json.loads(message.data)
                    # HA may batch frames after supported_features negotiates coalescing.
                    for item in payload if isinstance(payload, list) else [payload]:
                        if isinstance(item, dict) and type(item.get('id')) is int:
                            item['id'] -= 1
                    await ws.send_json(payload)
                await ws.close()

            receiver = asyncio.create_task(receive_upstream())
            async for frame in ws:
                if frame.type != aiohttp.WSMsgType.TEXT:
                    break
                try:
                    message = json.loads(frame.data)
                except ValueError:
                    await ws.close(code=1007)
                    break
                if not isinstance(message, dict) or type(message.get('id')) is not int or message['id'] <= 0:
                    await ws.close(code=1008)
                    break
                if not command_allowed(message):
                    await ws.send_json({'id': message['id'], 'type': 'result', 'success': False,
                                        'error': {'code': 'read_only', 'message': 'Demo is read-only'}})
                    continue
                # The private identity check consumed ID 1 on this connection. Offset all client IDs.
                message['id'] += 1
                if message.get('type') == 'unsubscribe_events' and type(message.get('subscription')) is int:
                    message['subscription'] += 1
                if message.get('type') == 'subscribe_entities':
                    message['entity_ids'] = sorted(ENTITIES)
                await upstream.send_json(message)
        except (aiohttp.ClientError, asyncio.TimeoutError, ValueError, TypeError):
            await ws.close(code=1013)
        finally:
            if receiver:
                receiver.cancel()
                with contextlib.suppress(asyncio.CancelledError):
                    await receiver
            if upstream:
                await upstream.close()
            self.sockets.discard(ws)
            await ws.close()
        return ws


@web.middleware
async def response_headers(request, handler):
    try:
        response = await handler(request)
    except web.HTTPException as error:
        response = web.Response(status=error.status, text=error.text, headers=error.headers)
    except (aiohttp.ClientError, asyncio.TimeoutError):
        response = web.json_response({'code': 'upstream_unavailable'}, status=503)
    response.headers.update({'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff',
                             'Referrer-Policy': 'same-origin', 'X-Frame-Options': 'SAMEORIGIN'})
    return response


def create_app(gateway):
    app = web.Application(middlewares=[response_headers], client_max_size=16384)
    app.router.add_route('*', '/{path:.*}', gateway.http)
    app.cleanup_ctx.append(gateway.lifecycle)
    return app


if __name__ == '__main__':
    gateway = NativeGateway(os.environ.get('HA_URL', 'http://homeassistant:8123'),
                            Path('/run/secrets/native_user_id').read_text(),
                            os.environ.get('NATIVE_PUBLIC_URL', 'https://ha-demo.coo.ee'))
    web.run_app(create_app(gateway), host='0.0.0.0', port=8080, access_log=None)
