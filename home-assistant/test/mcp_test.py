"""The MCP protocol and tools, driven with a fake home; the installed test covers the HTTP view."""
import asyncio
import base64
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
            entity('cover.garage_door', 'Garage door', 'closed', device_class='garage'),
            entity('cover.side_gate', 'Side gate', 'closed', device_class='gate'),
        ]
        self.calls = []
        self.index = {'serial': 1, 'designs': [
            {'designId': 'pack:garage-door-controls-skeuomorphic', 'cardType': 'tile',
             'device': {'deviceClass': 'garage'}, 'domain': 'cover', 'title': 'Garage door · Wall console'},
            {'designId': 'user:ratgdo', 'cardType': 'tile', 'device': {'integration': 'ratgdo'}, 'domain': 'cover'},
            {'designId': 'user:printer', 'cardType': 'tile', 'device': {'integration': 'bambu_lab'}, 'domain': 'light'},
            {'designId': 'user:wall', 'cardType': 'picture-elements', 'device': {'deviceClass': 'garage'}},
            {'designId': 'user:dial', 'cardType': 'thermostat', 'device': {'deviceClass': 'garage'}},
        ]}

    def device_info(self, entity_id):
        if entity_id.startswith('cover.'):
            return {'integration': 'ratgdo', 'manufacturer': 'Chamberlain', 'model': None, 'translationKey': None}
        return None

    async def design_index(self):
        return self.index

    def states(self):
        return list(self.entities)

    def state(self, entity_id):
        return next((e for e in self.entities if e['entity_id'] == entity_id), None)

    async def switch(self, service, entity_id):
        self.calls.append((service, entity_id))

    def dashboards(self):
        return [{'url_path': None, 'title': 'Overview', 'terrazzo_url': 'http://ha/terrazzo?dashboard='}]

    def edit_sessions(self):
        return [{'session_id': 's1', 'notes': [{'text': 'Bigger, please'}]}]

    async def start_edit_session(self, url_path, request):
        self.started = (url_path, request)
        return {'session_id': 's1', 'url_path': url_path, 'request': request, 'status': 'open',
                'dashboard': {'views': []}}

    async def start_new_dashboard(self, title, request):
        self.started_new = (title, request)
        return {'session_id': 's2', 'url_path': 'new', 'request': request, 'status': 'open',
                'dashboard': {'views': []}}

    async def edit_session(self, session_id):
        if session_id != 's1':
            raise mcp.ToolError(f'No edit session {session_id}')
        return {'session_id': 's1', 'notes': [{'text': 'Bigger, please'}], 'dashboard': {'views': []}}

    async def submit_draft(self, session_id, config, summary):
        self.submitted = (session_id, config, summary)
        return {'draft_id': 'd1', 'status': 'submitted'}

    async def draft_render(self, session_id):
        renders = getattr(self, 'renders', {
            's1': {'draft_id': 'd1', 'drawn_by': mcp.DRAWN_BY_APP, 'pngs': [b'\x89PNG\r\n\x1a\nfake']},
        })
        return renders.get(session_id)

    async def await_request(self, since):
        self.listened = since
        return {'cursor': 5.0, 'requests': [{'session_id': 's2', 'request': 'Garden'}]}

    async def await_feedback(self, session_id, since):
        self.awaited = (session_id, since)
        return {'cursor': 3, 'feedback': [{'seq': 3, 'text': 'Bigger', 'target': 'Camera'}],
                'status': 'open', 'applied': None}

    def send_message(self, session_id, text):
        self.sent = (session_id, text)
        return {'status': 'sent'}

    def set_status(self, session_id, status):
        self.status = (session_id, status)
        return {'status': 'shown'}

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
        for name in (['find_entities'], {'tool': 1}, None, 3):
            self.assertEqual(rpc(FakeHome(), 'tools/call', {'name': name})['error']['code'], mcp.INVALID_PARAMS)

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


    def test_edit_session_tools_pass_through_and_check_their_arguments(self):
        home = FakeHome()
        self.assertEqual(call(home, 'list_edit_sessions')['structuredContent']['sessions'][0]['notes'][0]['text'], 'Bigger, please')
        started = call(home, 'start_edit_session', {'url_path': 'garden', 'request': 'Add soil'})
        self.assertEqual(started['structuredContent']['session_id'], 's1')
        self.assertEqual(home.started, ('garden', 'Add soil'))
        self.assertEqual(call(home, 'start_edit_session')['structuredContent']['url_path'], None)

        read = call(home, 'get_edit_session', {'session_id': 's1'})['structuredContent']
        self.assertEqual(read['notes'][0]['text'], 'Bigger, please')
        self.assertTrue(call(home, 'get_edit_session', {'session_id': 'nope'})['isError'])
        self.assertTrue(call(home, 'get_edit_session', {})['isError'])

        config = {'views': [{'cards': []}]}
        submitted = call(home, 'submit_draft', {'session_id': 's1', 'config': config, 'summary': 'Garden'})
        self.assertEqual(submitted['structuredContent']['draft_id'], 'd1')
        self.assertEqual(home.submitted, ('s1', config, 'Garden'))
        self.assertTrue(call(home, 'submit_draft', {'session_id': 's1', 'config': '{"views": []}'})['isError'])

    def test_submit_draft_without_a_session_opens_one_and_drafts_in_one_call(self):
        home = FakeHome()
        config = {'views': [{'cards': []}]}
        read = call(home, 'get_dashboard', {'url_path': 'garden'})['structuredContent']
        self.assertEqual(read['revision'], mcp.dashboard_revision(read['config']))
        # An existing dashboard needs the read the draft changed; a changed one is refused.
        self.assertTrue(call(home, 'submit_draft', {'config': config, 'url_path': 'garden'})['isError'])
        # The fake's session starts from {'views': []}, not what get_dashboard read: edited since.
        stale = call(home, 'submit_draft', {'config': config, 'url_path': 'garden', 'revision': read['revision']})
        self.assertTrue(stale['isError'])
        self.assertIn('changed since get_dashboard', stale['content'][0]['text'])
        self.assertIn('s1', stale['content'][0]['text'])
        home.submitted = None
        revision = mcp.dashboard_revision({'views': []})
        result = call(home, 'submit_draft', {'config': config, 'url_path': 'garden', 'summary': 'Soil',
                                             'revision': revision})
        self.assertEqual(result['structuredContent']['session_id'], 's1')
        self.assertEqual(result['structuredContent']['draft_id'], 'd1')
        self.assertEqual(home.started, ('garden', 'Soil'))  # The summary stands in for a request.
        self.assertEqual(home.submitted, ('s1', config, 'Soil'))
        new = call(home, 'submit_draft', {'config': config, 'new_dashboard_title': 'Garden', 'request': 'A garden'})
        self.assertEqual(new['structuredContent']['session_id'], 's2')
        self.assertEqual(home.started_new, ('Garden', 'A garden'))

        async def refuse(session_id, config, summary):
            raise mcp.ToolError('The draft names entities this home doesn\'t have: light.nope')
        home.submit_draft = refuse
        failed = call(home, 'submit_draft', {'config': config, 'revision': revision})
        self.assertTrue(failed['isError'])
        self.assertIn('s1', failed['content'][0]['text'])  # Retry in the session it opened.
        schema = next(t for t in mcp.TOOLS if t['name'] == 'submit_draft')['inputSchema']
        self.assertEqual(schema['required'], ['config'])

    def test_find_designs_lists_device_designs_then_class_designs_as_ready_cards(self):
        result = call(FakeHome(), 'find_designs', {'entity_id': 'cover.garage_door'})['structuredContent']
        self.assertEqual([d['design_id'] for d in result['designs']], ['user:ratgdo', 'pack:garage-door-controls-skeuomorphic'])
        door = result['designs'][1]
        self.assertEqual(door['title'], 'Garage door · Wall console')
        self.assertEqual(door['made_for'], {'deviceClass': 'garage'})
        self.assertEqual(door['config'], {
            'type': 'custom:remote-compose-card',
            'card': {'type': 'tile', 'entity': 'cover.garage_door'},
            'design_id': 'pack:garage-door-controls-skeuomorphic',
        })
        # Only cards the wrapper takes: no thermostat or picture-elements design is offered.
        # A gate is no garage door; a light has nothing made for its device here.
        gate = call(FakeHome(), 'find_designs', {'entity_id': 'cover.side_gate'})['structuredContent']['designs']
        self.assertEqual([d['design_id'] for d in gate], ['user:ratgdo'])
        self.assertEqual(call(FakeHome(), 'find_designs', {'entity_id': 'light.kitchen'})['structuredContent']['designs'], [])

    def test_every_argument_is_a_plain_value_or_the_config_object(self):
        # A property's own schema, never a nested input schema: an agent would send an object.
        for tool in mcp.TOOLS:
            for name, prop in tool['inputSchema'].get('properties', {}).items():
                expected = 'object' if name == 'config' else prop['type']
                self.assertIn(prop['type'], ('string', 'integer', 'number', 'boolean') if name != 'config' else ('object',), f"{tool['name']}.{name}")
                self.assertEqual(prop['type'], expected)
        designs = next(t for t in mcp.TOOLS if t['name'] == 'find_designs')
        self.assertEqual(designs['inputSchema']['properties']['entity_id']['type'], 'string')

    def test_find_designs_needs_a_known_entity_and_the_library(self):
        self.assertTrue(call(FakeHome(), 'find_designs', {'entity_id': 'cover.nope'})['isError'])
        offline = FakeHome()
        offline.index = None
        self.assertTrue(call(offline, 'find_designs', {'entity_id': 'cover.garage_door'})['isError'])

    def test_design_matching_follows_device_match(self):
        self.assertTrue(mcp.design_matches({'model': 'x1*'}, {'model': 'X1 Carbon'}))
        self.assertFalse(mcp.design_matches({'model': 'x1'}, {'model': 'X1 Carbon'}))
        self.assertFalse(mcp.design_matches({}, {'model': 'X1'}))
        self.assertGreater(mcp.design_rank({'translationKey': 'door'}), mcp.design_rank({'deviceClass': 'garage'}))
        self.assertGreater(mcp.design_rank({'deviceClass': 'garage'}), mcp.design_rank({}))

    def test_card_guide_names_the_native_types_and_sizes(self):
        guide = call(FakeHome(), 'get_card_guide')['structuredContent']
        self.assertIn('tile', guide['native_card_types'])
        self.assertTrue(any('grid_options' in rule for rule in guide['sizing']))
        self.assertIn('homeassistant-remotecompose', guide['designs'])
        self.assertTrue(any('custom:remote-compose-card' in rule for rule in guide['remote_compose']))
        self.assertTrue(any('find_designs' in rule for rule in guide['remote_compose']))
        self.assertTrue(any('bindings' in rule and 'device_id' in rule for rule in guide['remote_compose']))

    def test_find_designs_includes_contract_inputs_and_actions_for_multi_entity_packs(self):
        index = {
            'designs': [{
                'designId': 'pack:bosch-dishwasher-fascia',
                'cardType': 'tile',
                'domain': 'sensor',
                'device': {'integration': 'home_connect', 'manufacturer': 'Bosch'},
                'title': 'Bosch Dishwasher · Front Fascia',
                'inputs': ['sensor_operation_state', 'sensor_remaining_program_time', 'binary_sensor_door'],
                'actions': ['start', 'eco_50', 'auto'],
            }],
        }
        entity = {'entity_id': 'sensor.dw_operation_state', 'state': 'run', 'attributes': {}}
        device = {'integration': 'home_connect', 'manufacturer': 'Bosch'}
        found = mcp.find_designs(index, entity, device)
        self.assertEqual(len(found), 1)
        self.assertEqual(
            found[0]['inputs'],
            ['sensor_operation_state', 'sensor_remaining_program_time', 'binary_sensor_door'],
        )
        self.assertEqual(found[0]['actions'], ['start', 'eco_50', 'auto'])

    def test_find_designs_waives_the_integration_when_the_brand_and_model_match(self):
        index = {'designs': [{
            'designId': 'pack:bosch-dishwasher', 'cardType': 'tile', 'title': 'Bosch dishwasher',
            'device': {'integration': 'home_connect', 'manufacturer': 'Bosch', 'model': 'Serie 4*'},
        }, {
            'designId': 'pack:bosch-native', 'cardType': 'tile',
            'device': {'integration': 'mqtt', 'manufacturer': 'Bosch', 'model': 'Serie 4*'},
        }]}
        entity = {'entity_id': 'switch.dishwasher', 'state': 'off', 'attributes': {}}
        bridged = {'integration': 'mqtt', 'manufacturer': 'Bosch', 'model': 'Serie 4 Dishwasher'}
        found = mcp.find_designs(index, entity, bridged)
        # Both fit; the one naming the device's own integration ranks first.
        self.assertEqual([d['design_id'] for d in found], ['pack:bosch-native', 'pack:bosch-dishwasher'])
        # Without a model the integration still decides.
        self.assertFalse(mcp.design_matches({'integration': 'home_connect', 'manufacturer': 'Bosch'}, bridged))

    def test_find_designs_searches_by_words_for_entities_without_a_device(self):
        home = FakeHome()
        home.entities.append(entity('input_boolean.dishwasher_running', 'Dishwasher running'))
        home.index['designs'].append({
            'designId': 'pack:bosch-dishwasher-fascia', 'cardType': 'tile', 'domain': 'sensor',
            'title': 'Bosch Dishwasher · Front Fascia', 'inputs': ['sensor_operation_state'],
            'device': {'integration': 'home_connect', 'manufacturer': 'Bosch'},
        })
        alone = call(home, 'find_designs', {'entity_id': 'input_boolean.dishwasher_running'})['structuredContent']
        self.assertEqual(alone['designs'], [])
        self.assertIn('query', alone['next'])
        # A helper finds a design for another domain by words: its card's entity is left to fill.
        both = call(home, 'find_designs', {'entity_id': 'input_boolean.dishwasher_running', 'query': 'dishwasher'})
        helper = both['structuredContent']['designs']
        self.assertEqual([d['design_id'] for d in helper], ['pack:bosch-dishwasher-fascia'])
        self.assertEqual(helper[0]['config']['card'], {'type': 'tile'})
        self.assertIn('bindings', both['structuredContent']['next'])
        found = call(home, 'find_designs', {'query': 'bosch DISHWASHER'})['structuredContent']
        self.assertEqual([d['design_id'] for d in found['designs']], ['pack:bosch-dishwasher-fascia'])
        design = found['designs'][0]
        self.assertEqual(design['domain'], 'sensor')
        self.assertEqual(design['inputs'], ['sensor_operation_state'])
        self.assertEqual(design['config']['card'], {'type': 'tile'})
        # With an entity, query results are bound to it after the device's own designs.
        garage = call(home, 'find_designs', {'entity_id': 'cover.garage_door', 'query': 'wall console'})
        self.assertEqual([d['design_id'] for d in garage['structuredContent']['designs']],
                         ['user:ratgdo', 'pack:garage-door-controls-skeuomorphic'])
        self.assertTrue(call(home, 'find_designs', {})['isError'])
        self.assertTrue(call(home, 'find_designs', {'query': '  '})['isError'])

    def test_a_broad_search_shows_each_device_before_a_second_style_and_variant_narrows_it(self):
        home = FakeHome()
        styles = ('Default', 'Front Fascia', 'Glasses', 'XR Repair', 'XREAL AR')
        home.index['designs'] = [
            {'designId': f'pack:{device}-{style.lower().replace(" ", "-")}', 'cardType': 'tile',
             'title': f'{name} · {style}', 'device': {'manufacturer': name.split()[0]}}
            for device, name in (('kettle', 'Fellow Kettle'), ('lamp', 'Ikea Lamp'), ('dw', 'Bosch Dishwasher'))
            for style in styles
        ]
        broad = call(home, 'find_designs', {'query': 'pack'})['structuredContent']
        # One of each device first, then their second styles: not five kettle variants.
        self.assertEqual([d['design_id'] for d in broad['designs'][:3]],
                         ['pack:kettle-default', 'pack:lamp-default', 'pack:dw-default'])
        self.assertEqual(broad['more'], 10)
        self.assertIn('variant', broad['next'])
        first = broad['designs'][0]
        self.assertEqual(first['variant'], 'Default')
        self.assertEqual(first['other_variants'], ['Front Fascia', 'Glasses', 'XR Repair', 'XREAL AR'])
        fascia = call(home, 'find_designs', {'query': 'pack', 'variant': 'fascia'})['structuredContent']
        self.assertEqual([d['design_id'] for d in fascia['designs']],
                         ['pack:kettle-front-fascia', 'pack:lamp-front-fascia', 'pack:dw-front-fascia'])
        self.assertEqual(fascia['more'], 0)
        self.assertEqual(fascia['variant'], 'fascia')
        # An untitled design is its own device, in the default style.
        self.assertEqual(mcp.design_group({'designId': 'user:Wall'}), ('user:wall', None))

    def test_edit_tools_never_claim_to_save(self):
        # An agent submits drafts; only a person applies them.
        names = {tool['name'] for tool in mcp.TOOLS}
        self.assertFalse({'save_dashboard', 'apply_draft'} & names)
        self.assertIn('submit_draft', names)



class LiveFeedbackToolsTest(unittest.TestCase):
    def test_await_feedback_passes_the_cursor_and_returns_notes(self):
        home = FakeHome()
        result = call(home, 'await_feedback', {'session_id': 's1', 'since': 2})['structuredContent']
        self.assertEqual(home.awaited, ('s1', 2))
        self.assertEqual(result['feedback'][0]['target'], 'Camera')
        call(home, 'await_feedback', {'session_id': 's1'})
        self.assertEqual(home.awaited, ('s1', 0))
        for bad in (-1, 'two', True):
            self.assertTrue(call(home, 'await_feedback', {'session_id': 's1', 'since': bad})['isError'])

    def test_await_request_listens_from_the_cursor(self):
        home = FakeHome()
        result = call(home, 'await_request', {'since': 2.5})['structuredContent']
        self.assertEqual(home.listened, 2.5)
        self.assertEqual(result['requests'][0]['session_id'], 's2')
        call(home, 'await_request')
        self.assertEqual(home.listened, 0)
        self.assertTrue(call(home, 'await_request', {'since': 'x'})['isError'])

    def test_a_draft_render_is_image_content(self):
        result = call(FakeHome(), 'get_draft_render', {'session_id': 's1'})
        image = result['content'][0]
        self.assertEqual((image['type'], image['mimeType']), ('image', 'image/png'))
        self.assertEqual(base64.b64decode(image['data']), b'\x89PNG\r\n\x1a\nfake')
        self.assertEqual(result['structuredContent']['draft_id'], 'd1')
        self.assertEqual(result['structuredContent']['drawn_by'], 'app')
        self.assertIn('live states', result['structuredContent']['note'])
        none = call(FakeHome(), 'get_draft_render', {'session_id': 's2'})['structuredContent']
        self.assertEqual(none['status'], 'none')
        self.assertIn('submit_draft', none['next'])

    def test_a_draft_nobody_opened_is_drawn_with_sample_states(self):
        body = {
            'data': 'sample',
            'images': [{'index': 0, 'caption': 'Sample data: Home (view 0)', 'png': base64.b64encode(b'p0').decode()},
                       {'index': 1, 'caption': 'Sample data: Home (view 0), section 2', 'png': base64.b64encode(b'p1').decode()},
                       {'index': 2, 'png': 'not base64!'}],
            'undrawable': [{'path': 'views[0].cards[1]', 'reason': 'custom:nope', 'type': 'x'}],
        }
        sample = mcp.sample_render(body)
        self.assertEqual(sample['pngs'], [b'p0', b'p1'])
        self.assertEqual(sample['undrawable'], [{'path': 'views[0].cards[1]', 'reason': 'custom:nope'}])
        home = FakeHome()
        home.renders = {'s1': {'draft_id': 'd2', **sample}}
        result = call(home, 'get_draft_render', {'session_id': 's1'})
        images = [c for c in result['content'] if c['type'] == 'image']
        self.assertEqual([base64.b64decode(i['data']) for i in images], [b'p0', b'p1'])
        structured = result['structuredContent']
        self.assertEqual((structured['draft_id'], structured['drawn_by']), ('d2', 'sample'))
        self.assertIn('made-up sample states', structured['note'])
        self.assertEqual(structured['undrawable'][0]['reason'], 'custom:nope')
        self.assertEqual(len(structured['captions']), 2)
        # Unreachable or undrawable: no picture, and why.
        home.renders = {'s1': {'draft_id': 'd2', 'drawn_by': None, 'pngs': [],
                               'reason': "The design server couldn't draw it: timeout"}}
        failed = call(home, 'get_draft_render', {'session_id': 's1'})['structuredContent']
        self.assertEqual(failed['status'], 'none')
        self.assertIn('timeout', failed['next'])
        self.assertEqual(mcp.sample_render(None)['pngs'], [])

    def test_messages_and_status_reach_the_person(self):
        home = FakeHome()
        call(home, 'send_message', {'session_id': 's1', 'text': 'Two columns?'})
        self.assertEqual(home.sent, ('s1', 'Two columns?'))
        self.assertTrue(call(home, 'send_message', {'session_id': 's1', 'text': '  '})['isError'])
        call(home, 'set_status', {'session_id': 's1', 'status': 'Drafting'})
        self.assertEqual(home.status, ('s1', 'Drafting'))


if __name__ == '__main__':
    unittest.main()
