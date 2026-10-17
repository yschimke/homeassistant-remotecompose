"""One-shot private onboarding. Does not print credentials or upstream responses."""
import asyncio
import json
import os
from pathlib import Path
import secrets

import aiohttp


async def bootstrap():
    private = Path('/private')
    private.mkdir(mode=0o700, exist_ok=True)
    private.chmod(0o700)  # Compose may have created the bind-mount directory as 0755.
    token_file = private / 'ha_token'
    if token_file.exists():
        print('Private token already exists; leaving instance unchanged.')
        return
    password = secrets.token_urlsafe(36)
    # Persist before creating owner, so a failed onboarding can be recovered privately.
    owner = private / 'owner.json'
    if owner.exists():
        password = json.loads(owner.read_text())['password']
    else:
        fd = os.open(owner, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w') as f:
            json.dump({'username': 'demo-operator', 'password': password}, f)
    base = os.environ.get('HA_URL', 'http://homeassistant:8123').rstrip('/')
    client = 'http://localhost/'
    async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=30)) as session:
        for attempt in range(120):
            try:
                async with session.get(base + '/api/onboarding') as r:
                    if r.status == 200:
                        break
            except (aiohttp.ClientError, asyncio.TimeoutError):
                pass
            await asyncio.sleep(2)
        else:
            raise RuntimeError('Home Assistant did not become ready')
        async with session.post(base + '/api/onboarding/users', json={
            'name': 'Demo Operator', 'username': 'demo-operator', 'password': password,
            'client_id': client, 'language': 'en',
        }) as r:
            if r.status != 200:
                raise RuntimeError('Owner creation failed; use private owner.json for recovery')
            code = (await r.json())['auth_code']
        async with session.post(base + '/auth/token', data={
            'grant_type': 'authorization_code', 'code': code, 'client_id': client,
        }) as r:
            if r.status != 200:
                raise RuntimeError('Private token exchange failed')
            access = (await r.json())['access_token']
        async with session.ws_connect(base + '/api/websocket') as ws:
            await ws.receive_json()
            await ws.send_json({'type': 'auth', 'access_token': access})
            if (await ws.receive_json()).get('type') != 'auth_ok':
                raise RuntimeError('Private websocket authentication failed')
            await ws.send_json({'id': 1, 'type': 'auth/long_lived_access_token',
                                'client_name': 'Public read facade (private credential)', 'lifespan': 365})
            reply = await ws.receive_json()
            if not reply.get('success'):
                raise RuntimeError('Private token creation failed')
            fd = os.open(token_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, 'w') as f:
                f.write(reply['result'])
            os.chown(token_file, 65532, 65532)
    print('Private owner and gateway token provisioned; no credentials were printed.')


if __name__ == '__main__':
    try:
        asyncio.run(bootstrap())
    except Exception:
        raise SystemExit('Private onboarding failed. Inspect host privately; no credentials logged.') from None
