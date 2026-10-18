"""Run with HA stopped, in its pinned image. Enable only the isolated demo's UI."""
import asyncio
import os
from pathlib import Path

USERNAME = 'demo'
PASSWORD = 'demo'  # Intentionally public demo login, never an operator credential.


async def load_auth(config):
    from homeassistant.auth import auth_manager_from_config
    from homeassistant.config_entries import ConfigEntries
    from homeassistant.core import HomeAssistant
    from homeassistant.helpers import device_registry as dr, entity_registry as er

    # Follow the pinned HA auth CLI's registry initialization sequence.
    hass = HomeAssistant(str(config))
    hass.config_entries = ConfigEntries(hass, {})
    await hass.config_entries.async_initialize()
    dr.async_setup(hass)
    await asyncio.gather(dr.async_load(hass), er.async_load(hass))
    hass.auth = await auth_manager_from_config(hass, [{'type': 'homeassistant'}], [])
    return hass


async def prepare(config, private):
    from homeassistant.components.onboarding import OnboardingStorage
    from homeassistant.components.onboarding.const import STEPS
    from homeassistant.auth.permissions.const import POLICY_READ, POLICY_CONTROL

    if not (private / 'ha_token').exists():
        raise RuntimeError('Provision the isolated backend first')
    hass = await load_auth(config)
    users = await hass.auth.async_get_users()
    if not any(user.is_owner for user in users):
        raise RuntimeError('Existing private demo owner required')
    provider = hass.auth.get_auth_provider('homeassistant', None)
    credentials = next((credential for user in users for credential in user.credentials
                        if credential.auth_provider_type == 'homeassistant'
                        and credential.data.get('username') == USERNAME), None)
    if credentials is not None:
        user = await hass.auth.async_get_user_by_credentials(credentials)
        if not user.is_active or user.is_owner or user.is_admin or {group.id for group in user.groups} != {'system-read-only'}:
            raise RuntimeError('Existing demo login is not exclusively read-only')
    else:
        await provider.async_add_auth(USERNAME, PASSWORD)
        credentials = await provider.async_get_or_create_credentials({'username': USERNAME})
        user = await hass.auth.async_create_user('Public demo (read only)', group_ids=['system-read-only'])
        await hass.auth.async_link_user(user, credentials)
    await provider.async_validate_login(USERNAME, PASSWORD)
    if not user.permissions.check_entity('switch.demo_coffee', POLICY_READ) or user.permissions.check_entity('switch.demo_coffee', POLICY_CONTROL):
        raise RuntimeError('Demo account permission check failed')
    # Pinned HA auth has delayed writes; explicitly flush before this one-shot process exits.
    await hass.auth._store._store.async_save(hass.auth._store._data_to_save())
    # Mark onboarding complete without running core_config's cloud integration discovery.
    await OnboardingStorage(hass, 4, 'onboarding', private=True).async_save({'done': STEPS})
    private.mkdir(mode=0o700, exist_ok=True)
    path = private / 'native_user_id'
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, 'w') as file:
        file.write(user.id)
    os.chown(path, 65532, 65532)
    await hass.async_block_till_done()
    print('Native demo user provisioned with read-only permissions; onboarding completed offline.')


if __name__ == '__main__':
    try:
        asyncio.run(prepare(Path('/config'), Path('/private')))
    except Exception:
        raise SystemExit('Native demo setup failed; inspect the host privately.') from None
