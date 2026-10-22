"""The MCP protocol and tools, driven with a fake home; the installed test covers the HTTP view."""
import asyncio
import importlib.util
import json
from pathlib import Path
import unittest

# mcp.py imports nothing from Home Assistant, so it loads on its own outside the package.
spec = importlib.util.spec_from_file_location(
    'terrazzo_mcp', Path(__file__).resolve().parents[2] / 'custom_components/terrazzo/mcp.py')
mcp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mcp)


def entity(entity_id, name=None, state='off', **attributes):
    if name:
        attributes['friendly_name'] = name
    return {'entity_id': entity_id, 'state': state, 'attributes': attributes, 'last_changed': '2026-10-22T08:00:00+00:00'}


class FakeHome:
    name = 'Test home'

    def __init__(self):
        self.entities = [
            entity('light.kitchen', 'Kitchen'),
            entity('light.kitchen_ceiling', 'Kitchen ceiling', 'on'),
            entity('sensor.kitchen_temperature', 'Kitchen temperature', '21.5', unit_of_measurement='°C'),
            entity('lock.front_door', 'Front door', 'locked'),
        ]
        self.calls = []

    def states(self):
        return list(self.entities)

    def state(self, entity_id):
        return next((e for e in self.entities if e['entity_id'] == entity_id), None)

    async def switch(self, service, entity_id):
        self.calls.append((service, entity_id))

    def dashboards(self):
        return [{'url_path': None, 'title': 'Overview', 'terrazzo_url': 'http://ha/terrazzo?dashboard='}]

    async def dashboard(self, url_path):
        if url_path == 'missing':
            raise mcp.ToolError('No dashboard missing')
        return {'views': [{'cards': [{'type': 'tile', 'entity': 'light.kitchen'}]}]}


def rpc(home, method, params=None, message_id=1):
    message = {'jsonrpc': '2.0', 'id': message_id, 'method': method}
    if params is not None:
        message['params'] = params
    return asyncio.run(mcp.handle(message, home, '0.0.0'))


def call(home, name, arguments=None):
    return rpc(home, 'tools/call', {'name': name, 'arguments': arguments or {}})['result']


class McpProtocolTest(unittest.TestCase):
    def test_initialize_negotiates_and_names_the_one_instance(self):
        home = FakeHome()
        result = rpc(home, 'initialize', {'protocolVersion': '2025-06-18', 'capabilities': {}})['result']
        self.assertEqual(result['protocolVersion'], '2025-06-18')
        self.assertEqual(result['capabilities'], {'tools': {'listChanged': False}})
        self.assertIn("'Test home'", result['instructions'])
        unknown = rpc(home, 'initialize', {'protocolVersion': '1999-01-01'})['result']
        self.assertEqual(unknown['protocolVersion'], mcp.PROTOCOL_VERSIONS[0])

    def test_notifications_get_no_answer_and_unknown_methods_an_error(self):
        home = FakeHome()
        self.assertIsNone(asyncio.run(mcp.handle(
            {'jsonrpc': '2.0', 'method': 'notifications/initialized'}, home, '0')))
        self.assertEqual(rpc(home, 'resources/list')['error']['code'], mcp.METHOD_NOT_FOUND)
        self.assertEqual(rpc(home, 'ping')['result'], {})
        self.assertEqual(asyncio.run(mcp.handle({'id': 1}, home, '0'))['error']['code'], mcp.INVALID_REQUEST)

    def test_tools_list_matches_what_call_accepts(self):
        tools = rpc(FakeHome(), 'tools/list')['result']['tools']
        self.assertEqual({tool['name'] for tool in tools}, mcp.TOOL_NAMES)
        for tool in tools:
            self.assertEqual(tool['inputSchema']['type'], 'object')
            self.assertIn('readOnlyHint', tool['annotations'])
        self.assertEqual(rpc(FakeHome(), 'tools/call', {'name': 'nope'})['error']['code'], mcp.INVALID_PARAMS)

    def test_find_entities_ranks_exact_then_prefix(self):
        result = call(FakeHome(), 'find_entities', {'query': 'kitchen'})
        ids = [e['entity_id'] for e in result['structuredContent']['entities']]
        self.assertEqual(ids, ['light.kitchen', 'light.kitchen_ceiling', 'sensor.kitchen_temperature'])
        self.assertEqual(json.loads(result['content'][0]['text']), result['structuredContent'])
        lights = call(FakeHome(), 'find_entities', {'domain': 'light'})['structuredContent']['entities']
        self.assertEqual(len(lights), 2)

    def test_get_entity_state_includes_attributes(self):
        result = call(FakeHome(), 'get_entity_state', {'entity_id': 'sensor.kitchen_temperature'})
        self.assertEqual(result['structuredContent']['unit'], '°C')
        self.assertEqual(result['structuredContent']['attributes']['friendly_name'], 'Kitchen temperature')
        missing = call(FakeHome(), 'get_entity_state', {'entity_id': 'light.garage'})
        self.assertTrue(missing['isError'])
        self.assertTrue(call(FakeHome(), 'get_entity_state', {'entity_id': 'nonsense'})['isError'])

    def test_switching_calls_home_and_refuses_locks(self):
        home = FakeHome()
        result = call(home, 'turn_on', {'entity_id': 'light.kitchen'})
        self.assertEqual(result['structuredContent']['state'], 'off', 'the state from before the call')
        call(home, 'toggle', {'entity_id': 'light.kitchen_ceiling'})
        self.assertEqual(home.calls, [('turn_on', 'light.kitchen'), ('toggle', 'light.kitchen_ceiling')])
        refused = call(home, 'turn_off', {'entity_id': 'lock.front_door'})
        self.assertTrue(refused['isError'])
        self.assertEqual(len(home.calls), 2)
        self.assertFalse(call(home, 'get_entity_state', {'entity_id': 'lock.front_door'})['structuredContent']['can_switch'])

    def test_dashboards(self):
        home = FakeHome()
        self.assertEqual(call(home, 'list_dashboards')['structuredContent']['dashboards'][0]['title'], 'Overview')
        config = call(home, 'get_dashboard')['structuredContent']
        self.assertIsNone(config['url_path'])
        self.assertEqual(config['config']['views'][0]['cards'][0]['entity'], 'light.kitchen')
        self.assertTrue(call(home, 'get_dashboard', {'url_path': 'missing'})['isError'])


if __name__ == '__main__':
    unittest.main()
