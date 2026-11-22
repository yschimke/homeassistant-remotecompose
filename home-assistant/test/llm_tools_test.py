"""The dashboard tools as a Home Assistant LLM API (llm_tools.py), without Home Assistant: which
tools, their schemas, and how a call runs; installed-llm-api.test.mjs plays them in a real one."""
import asyncio
import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2] / 'custom_components/terrazzo'
spec = importlib.util.spec_from_file_location('terrazzo_llm_tools', ROOT / 'llm_tools.py')
llm_tools = importlib.util.module_from_spec(spec)
spec.loader.exec_module(llm_tools)
mcp = llm_tools.mcp


class FakeHome:
    name = 'Test home'

    def __init__(self):
        self.entities = [{'entity_id': 'cover.garage_door', 'state': 'closed',
                          'attributes': {'friendly_name': 'Garage door', 'device_class': 'garage'}}]

    def states(self):
        return self.entities

    def state(self, entity_id):
        return next((e for e in self.entities if e['entity_id'] == entity_id), None)

    def device_info(self, entity_id):
        return None

    async def design_index(self):
        return {'designs': [{'designId': 'pack:garage-door-controls-skeuomorphic', 'cardType': 'tile',
                             'device': {'deviceClass': 'garage'}, 'domain': 'cover', 'title': 'Garage door · Wall console'}]}

    def overview(self):
        return {'areas': [{'area': 'Garage', 'entities': ['cover.garage_door (Garage door): closed']}]}


def run(name, arguments=None):
    return asyncio.run(llm_tools.call(FakeHome(), name, arguments or {}))


class LlmToolsTest(unittest.TestCase):
    def test_offers_the_editing_tools_and_never_switching_or_long_polling(self):
        names = [spec['name'] for spec in llm_tools.tool_specs()]
        self.assertEqual(len(names), len(set(names)))
        self.assertTrue(set(names) - {'home_overview'} <= mcp.TOOL_NAMES)
        for name in ('start_edit_session', 'submit_draft', 'find_designs', 'get_card_guide', 'home_overview'):
            self.assertIn(name, names)
        for name in ('turn_on', 'turn_off', 'toggle', 'await_feedback', 'await_request'):
            self.assertNotIn(name, names)

    def test_every_tool_schema_converts(self):
        for spec in llm_tools.tool_specs():
            fields = llm_tools.schema_fields(spec['inputSchema'])
            required = {name for name, _, req, _ in fields if req}
            self.assertEqual(required, set(spec['inputSchema'].get('required') or []), spec['name'])
            for name, kind, _, _ in fields:
                # Only the draft's config is an object; a nested input schema here is a bug.
                self.assertEqual(kind is dict, name == 'config', f"{spec['name']}.{name}")
        submit = next(s for s in llm_tools.tool_specs() if s['name'] == 'submit_draft')
        self.assertIn(('config', dict), [(n, t) for n, t, _, _ in llm_tools.schema_fields(submit['inputSchema'])])
        with self.assertRaises(ValueError):
            llm_tools.schema_fields({'properties': {'x': {'type': 'array'}}})

    def test_calls_run_the_mcp_tools_and_home_overview(self):
        self.assertEqual(run('home_overview')['areas'][0]['area'], 'Garage')
        designs = run('find_designs', {'entity_id': 'cover.garage_door'})['designs']
        self.assertEqual(designs[0]['config']['design_id'], 'pack:garage-door-controls-skeuomorphic')
        self.assertIn('remote_compose', run('get_card_guide'))

    def test_refusals_and_unoffered_tools_are_errors_for_the_model(self):
        self.assertIn('error', run('get_entity_state', {'entity_id': 'light.nope'}))
        self.assertIn('error', run('turn_on', {'entity_id': 'cover.garage_door'}))
        self.assertIn('error', run('nonsense'))

    def test_the_prompt_says_drafts_are_reviewed_not_saved(self):
        self.assertIn('submit_draft', llm_tools.API_PROMPT)
        self.assertIn('applies it themselves', llm_tools.API_PROMPT)
        self.assertEqual(llm_tools.API_ID, 'terrazzo_dashboards')


    def test_a_read_only_mcp_server_keeps_the_reading_tools(self):
        # Home Assistant's MCP Server, set to read only, serves only tools marked read only.
        read_only = {spec['name'] for spec in llm_tools.tool_specs() if llm_tools.annotations(spec)['read_only']}
        self.assertEqual({'start_edit_session', 'submit_draft'}, {s['name'] for s in llm_tools.tool_specs()} - read_only)
        self.assertIn('home_overview', read_only)
        for spec in llm_tools.tool_specs():
            hints = llm_tools.annotations(spec)
            self.assertFalse(hints['destructive'], spec['name'])
            # Only find_designs reaches outside Home Assistant: the design server's index.
            self.assertEqual(spec['name'] == 'find_designs', hints['open_world'], spec['name'])

if __name__ == '__main__':
    unittest.main()
