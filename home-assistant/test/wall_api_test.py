"""The wall display API's rules, driven with plain data (wall_api.py imports nothing from HA)."""
import importlib.util
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    'terrazzo_wall_api',
    Path(__file__).resolve().parents[2] / 'custom_components/terrazzo/wall_api.py')
wall = importlib.util.module_from_spec(spec)
spec.loader.exec_module(wall)

SECTIONS = {
    'title': 'Home',
    'views': [
        {
            'title': 'Rooms',
            'path': 'rooms',
            'type': 'sections',
            'sections': [
                {
                    'type': 'grid',
                    'cards': [
                        {'type': 'heading', 'heading': 'Kitchen'},
                        {'type': 'tile', 'entity': 'light.kitchen'},
                        {'type': 'tile', 'entity': 'light.pantry'},
                        {'type': 'tile', 'entity': 'sensor.oven', 'grid_options': {'columns': 12, 'rows': 2}},
                        'not a card',
                        {'type': 'entities', 'title': 'Doors',
                         'entities': ['lock.front', {'entity': 'binary_sensor.back', 'tap_action': {'action': 'more-info'}}]},
                    ],
                },
            ],
        },
        {
            'title': 'Lights',
            'cards': [
                {'type': 'button', 'entity': 'switch.fan', 'tap_action': {'action': 'perform-action', 'perform_action': 'fan.turn_on', 'target': {'entity_id': 'fan.ceiling'}, 'data': {'percentage': 50}}},
                {'type': 'markdown', 'content': 'Hi'},
            ],
        },
    ],
}


def ha_action(**fields):
    kind = fields.pop('kind')
    return json.dumps({'type': wall.HA_ACTION_TYPE + kind, **fields})


class DashboardListTest(unittest.TestCase):
    def test_the_chosen_dashboard_comes_first_and_overview_is_lovelace(self):
        listed = wall.dashboard_list(
            {None: {}, 'wall-panel': {'title': 'Wall panel', 'mode': 'yaml'}}, 'wall-panel')
        self.assertEqual(['wall-panel', 'lovelace'], [d['url_path'] for d in listed])
        self.assertTrue(listed[0]['default'])
        self.assertEqual('Overview', listed[1]['title'])
        self.assertEqual('/api/terrazzo/wall/v1/dashboards/wall-panel', listed[0]['layout'])

    def test_without_a_choice_overview_is_the_default(self):
        listed = wall.dashboard_list({'b': {'title': 'B'}, None: {}})
        self.assertEqual('lovelace', listed[0]['url_path'])
        self.assertTrue(listed[0]['default'])

    def test_without_overview_or_the_choice_the_first_is_the_default(self):
        listed = wall.dashboard_list({'b': {'title': 'B'}, 'a': {'title': 'A'}}, 'gone')
        self.assertEqual(['a', 'b'], [d['url_path'] for d in listed])
        self.assertEqual([True, False], [d['default'] for d in listed])

    def test_a_strategy_dashboard_has_no_cards(self):
        self.assertFalse(wall.has_cards({'strategy': {'type': 'original-states'}}))
        self.assertFalse(wall.has_cards(None))
        self.assertTrue(wall.has_cards({'views': [{'cards': []}]}))
        self.assertTrue(wall.has_cards({'strategy': {'type': 'x'}, 'views': [{'cards': []}]}))


class LayoutTest(unittest.TestCase):
    def setUp(self):
        self.layout = wall.layout(SECTIONS, 'home')

    def cards(self, view=0):
        return [c for s in self.layout['views'][view]['sections'] for c in s['cards']]

    def test_sections_pack_on_a_12_column_grid(self):
        boxes = [(c['id'], c['x'], c['y'], c['w'], c['h']) for c in self.cards()]
        self.assertEqual([
            ('v0.s0.c0', 0, 0, 12, 1),
            ('v0.s0.c1', 0, 1, 6, 1),
            ('v0.s0.c2', 6, 1, 6, 1),
            ('v0.s0.c3', 0, 2, 12, 2),
            # Index 4 isn't a card; the next keeps its configured index.
            ('v0.s0.c5', 0, 4, 12, 3),
        ], boxes)
        self.assertTrue(self.cards()[-1]['auto_height'])
        self.assertEqual('Kitchen', self.layout['views'][0]['sections'][0]['title'])

    def test_cards_name_their_entities_and_where_they_are_resolved(self):
        doors = self.cards()[-1]
        self.assertEqual(['lock.front', 'binary_sensor.back'], doors['entities'])
        self.assertEqual('/api/terrazzo/wall/v1/cards/home/v0.s0.c5', doors['card'])

    def test_a_masonry_view_stacks_full_width(self):
        boxes = [(c['id'], c['x'], c['y'], c['w']) for c in self.cards(1)]
        self.assertEqual([('v1.c0', 0, 0, 12), ('v1.c1', 0, 2, 12)], boxes)
        self.assertEqual('masonry', self.layout['views'][1]['type'])

    def test_every_id_finds_its_card(self):
        for cid in wall.view_card_ids(self.layout):
            self.assertIsInstance(wall.find_card(SECTIONS, cid), dict)
        self.assertEqual('sensor.oven', wall.find_card(SECTIONS, 'v0.s0.c3')['entity'])

    def test_a_remote_compose_card_is_the_card_it_wraps(self):
        config = {'views': [{'cards': [{'type': 'custom:remote-compose-card',
                                        'card': {'type': 'tile', 'entity': 'light.a'}}]}]}
        card = wall.layout(config, None)['views'][0]['sections'][0]['cards'][0]
        self.assertEqual(('tile', ['light.a']), (card['type'], card['entities']))
        self.assertEqual('light.a', wall.find_card(config, 'v0.c0')['entity'])

    def test_unknown_ids_are_404(self):
        for cid in ('v0.s0.c4', 'v9.c0', 'v0.c0', 'nope', '', 'v0.s0.c1/../x'):
            with self.assertRaises(wall.WallError) as raised:
                wall.find_card(SECTIONS, cid)
            self.assertEqual(404, raised.exception.status, cid)

    def test_the_etag_follows_the_config(self):
        changed = json.loads(json.dumps(SECTIONS))
        changed['views'][0]['title'] = 'Other'
        self.assertNotEqual(self.layout['etag'], wall.layout(changed, 'home')['etag'])
        self.assertEqual(self.layout['etag'], wall.layout(json.loads(json.dumps(SECTIONS)), 'home')['etag'])

    def test_a_strategy_dashboard_says_how_to_take_control(self):
        with self.assertRaises(wall.WallError) as raised:
            wall.layout({'strategy': {'type': 'original-states'}}, None)
        self.assertEqual(422, raised.exception.status)


class SharedValuesTest(unittest.TestCase):
    def test_a_card_reads_shared_values_when_its_values_carry_one(self):
        self.assertEqual(('sun.sun',), wall.SHARED_ENTITIES)
        self.assertTrue(wall.reads_shared({'light.s0.is_on': True, 'HA:sun.sun.elevation': -12.5}))
        self.assertFalse(wall.reads_shared({'light.s0.is_on': True}))
        self.assertFalse(wall.reads_shared({}))


class SlotTest(unittest.TestCase):
    def test_slots_follow_card_design_slots(self):
        stack = {'type': 'vertical-stack', 'cards': [
            {'type': 'tile', 'entity': 'light.a'},
            {'type': 'glance', 'entities': ['sensor.b', {'entity': 'sensor.c'}, {'type': 'divider'}]},
            {'entity': 'light.untyped'},
        ]}
        self.assertEqual(['light.a', 'sensor.b', 'sensor.c'], [e for e, _ in wall.slot_entries(stack)])
        self.assertIsNone(wall.slot_entries({'type': 'markdown'}))
        self.assertEqual(['light.a'], wall.entity_ids({'type': 'entities', 'entities': ['light.a', 'light.a']}))


class TapTest(unittest.TestCase):
    doors = SECTIONS['views'][0]['sections'][0]['cards'][5]
    fan = SECTIONS['views'][1]['cards'][0]

    def test_a_tap_on_the_card_runs_its_default(self):
        self.assertEqual({'kind': 'Toggle', 'entity_id': 'lock.front'}, wall.resolve_tap(self.doors))
        self.assertEqual(('homeassistant', 'toggle', {'entity_id': 'lock.front'}),
                         wall.service_call(wall.resolve_tap(self.doors)))

    def test_a_slot_tap_runs_that_rows_action(self):
        tap = ha_action(kind='Toggle', entityId='binary_sensor.s1')
        self.assertEqual({'kind': 'MoreInfo', 'entity_id': 'binary_sensor.back'}, wall.resolve_tap(self.doors, tap))
        self.assertIsNone(wall.service_call(wall.resolve_tap(self.doors, tap)))

    def test_a_template_never_picks_the_service(self):
        evil = ha_action(kind='CallService', domain='alarm_control_panel', service='alarm_disarm',
                         entityId='lock.s0', serviceData={'code': '1234'})
        self.assertEqual(('homeassistant', 'toggle', {'entity_id': 'lock.front'}),
                         wall.service_call(wall.resolve_tap(self.doors, evil)))
        # A slot of the wrong domain, or past the card's slots, is the card's own tap.
        for slot in ('light.s0', 'lock.s7', 'lock.front'):
            self.assertEqual({'kind': 'Toggle', 'entity_id': 'lock.front'},
                             wall.resolve_tap(self.doors, ha_action(kind='Toggle', entityId=slot)), slot)
        self.assertEqual({'kind': 'Toggle', 'entity_id': 'lock.front'}, wall.resolve_tap(self.doors, 'not json'))

    def test_a_configured_action_carries_its_target_and_data(self):
        self.assertEqual(('fan', 'turn_on', {'percentage': 50, 'entity_id': 'fan.ceiling'}),
                         wall.service_call(wall.resolve_tap(self.fan)))

    def test_none_and_navigate_are_not_service_calls(self):
        card = {'type': 'tile', 'entity': 'light.a', 'tap_action': {'action': 'none'}}
        self.assertEqual({'kind': 'None'}, wall.resolve_tap(card))
        card['tap_action'] = {'action': 'navigate', 'navigation_path': '/home/lights'}
        self.assertEqual({'kind': 'Navigate', 'path': '/home/lights'}, wall.resolve_tap(card))
        self.assertIsNone(wall.service_call(wall.resolve_tap(card)))


class ValueStoreTest(unittest.TestCase):
    def test_the_first_poll_gets_everything_then_only_changes(self):
        store = wall.ValueStore(epoch=7)
        store.update('a', 'sha1', '/rc/sha1', True, {'light.s0.is_on': True, 'light.s0.state': 'On'})
        store.update('b', 'sha2', '/rc/sha2', True, {'sensor.s0.state': '21 °C'})
        first = store.changes(['a', 'b'], store.since(''))
        self.assertEqual({'design': 'sha1', 'rc': '/rc/sha1', 'exact': True,
                          'values': {'light.s0.is_on': True, 'light.s0.state': 'On'}}, first['a'])
        cursor = store.cursor()
        self.assertEqual({}, store.changes(['a', 'b'], store.since(cursor)))

        self.assertFalse(store.update('a', 'sha1', '/rc/sha1', True, {'light.s0.is_on': True, 'light.s0.state': 'On'}))
        self.assertTrue(store.update('a', 'sha1', '/rc/sha1', True, {'light.s0.is_on': False, 'light.s0.state': 'Off'}))
        self.assertEqual({'a': {'values': {'light.s0.is_on': False, 'light.s0.state': 'Off'}}},
                         store.changes(['a', 'b'], store.since(cursor)))

    def test_a_new_design_comes_whole(self):
        store = wall.ValueStore(epoch=1)
        store.update('a', 'sha1', '/rc/sha1', True, {'x': 1})
        cursor = store.cursor()
        store.update('a', 'sha9', '/rc/sha9', False, {'x': 1})
        self.assertEqual('sha9', store.changes(['a'], store.since(cursor))['a']['design'])

    def test_types_matter(self):
        store = wall.ValueStore(epoch=1)
        store.update('a', 's', 'r', True, {'x': 1})
        self.assertTrue(store.update('a', 's', 'r', True, {'x': 1.0}))

    def test_a_cursor_from_another_epoch_is_answered_in_full(self):
        store = wall.ValueStore(epoch=2)
        store.update('a', 's', 'r', True, {'x': 1})
        for cursor in ('1-5', 'garbage', '', '2-x'):
            self.assertEqual(0, store.since(cursor), cursor)
        self.assertFalse(store.is_current('1-5'))
        self.assertTrue(store.is_current(store.cursor()))

    def test_errors_are_reported_once(self):
        store = wall.ValueStore(epoch=1)
        self.assertTrue(store.fail('a', 'No design'))
        self.assertFalse(store.fail('a', 'No design'))
        self.assertEqual({'a': {'error': 'No design'}}, store.changes(['a'], 0))


class PictureTest(unittest.TestCase):
    glance = {'type': 'picture-glance', 'entities': ['camera.front', 'person.ann', 'light.porch']}

    def test_a_slot_names_its_own_entity_by_position_and_domain(self):
        self.assertEqual('camera.front', wall.picture_entity(self.glance, 'camera.s0'))
        self.assertEqual('person.ann', wall.picture_entity(self.glance, 'person.s1'))
        for slot in ('camera.s1', 'camera.s9', 'camera.front', '../x', '', None):
            self.assertIsNone(wall.picture_entity(self.glance, slot), slot)

    def test_values_point_at_the_slots_with_pictures_and_move_with_them(self):
        values = wall.picture_values(None, 'v0.c0', self.glance, {
            'camera.front': '/api/camera_proxy/camera.front?token=a', 'person.ann': '/local/ann.png'})
        self.assertEqual({'camera.s0.picture_url', 'person.s1.picture_url'}, set(values))
        self.assertTrue(values['camera.s0.picture_url'].startswith(
            '/api/terrazzo/wall/v1/pictures/lovelace/v0.c0/camera.s0?v='))
        moved = wall.picture_values(None, 'v0.c0', self.glance, {
            'camera.front': '/api/camera_proxy/camera.front?token=b'})
        self.assertNotEqual(values['camera.s0.picture_url'], moved['camera.s0.picture_url'])

    def test_sources(self):
        self.assertEqual(('local', '/local/a.png'), wall.picture_source('/local/a.png'))
        self.assertEqual(('remote', 'https://x/a.png'), wall.picture_source('https://x/a.png'))
        for bad in ('//evil/a.png', 'file:///etc/passwd', 'a.png', '', None):
            self.assertIsNone(wall.picture_source(bad), bad)

    def test_the_query(self):
        self.assertEqual((None, None, 'contain', None), wall.picture_query({}))
        self.assertEqual((320, 240, 'cover', 'jpeg'),
                         wall.picture_query({'w': '320', 'h': '240', 'fit': 'cover', 'format': 'jpg'}))
        for bad in ({'w': '0'}, {'h': '99999'}, {'w': '-1'}, {'fit': 'stretch'}, {'format': 'gif'}):
            with self.assertRaises(wall.WallError):
                wall.picture_query(bad)

    def test_boxes(self):
        # Contain: all of it, inside the box.
        self.assertEqual((320, 180, None), wall.picture_box(1920, 1080, 320, 320, 'contain'))
        # Cover: the box filled, centred crop.
        self.assertEqual((569, 320, (124, 0, 444, 320)), wall.picture_box(1920, 1080, 320, 320, 'cover'))
        # One side: the other follows.
        self.assertEqual((640, 360, None), wall.picture_box(1920, 1080, 640, None, 'contain'))
        # Never scaled up.
        self.assertEqual((100, 50, None), wall.picture_box(100, 50, 400, 400, 'contain'))
        self.assertEqual((100, 50, (0, 0, 100, 50)), wall.picture_box(100, 50, 400, 400, 'cover'))
        self.assertEqual((1920, 1080, None), wall.picture_box(1920, 1080, None, None, 'contain'))


class FontTest(unittest.TestCase):
    sha = 'a' * 64
    manifest = {'families': [
        {'name': 'digital7', 'role': 'display',
         'fonts': [{'file': 'digital7.ttf', 'weight': 400, 'style': 'italic', 'sha256': 'a' * 64}]},
        {'name': 'broken', 'fonts': [{'file': 'x.ttf'}]},
    ]}

    def test_faces_are_fetched_by_hash(self):
        out = wall.font_manifest(self.manifest)
        self.assertEqual(['digital7'], [f['name'] for f in out['families']])
        self.assertEqual(f'/api/terrazzo/wall/v1/fonts/{self.sha}.ttf', out['families'][0]['fonts'][0]['url'])
        self.assertEqual('italic', out['families'][0]['fonts'][0]['style'])

    def test_a_hash_finds_its_file(self):
        self.assertEqual('digital7.ttf', wall.font_file(self.manifest, self.sha))
        self.assertIsNone(wall.font_file(self.manifest, 'b' * 64))
        sneaky = {'families': [{'fonts': [{'file': '../secrets', 'sha256': self.sha}]}]}
        self.assertIsNone(wall.font_file(sneaky, self.sha))
        self.assertTrue(wall.FONT_FILE.match(f'{self.sha}.woff2'))
        self.assertFalse(wall.FONT_FILE.match('fonts.json'))


class QueryTest(unittest.TestCase):
    def test_wait_is_clamped(self):
        self.assertEqual(wall.DEFAULT_WAIT, wall.clamp_wait(None))
        self.assertEqual(0, wall.clamp_wait('-3'))
        self.assertEqual(wall.MAX_WAIT, wall.clamp_wait('9999'))

    def test_cards_are_limited_to_the_dashboards(self):
        self.assertEqual(['a', 'b'], wall.parse_card_ids('', ['a', 'b']))
        self.assertEqual(['b'], wall.parse_card_ids('b,zzz,', ['a', 'b']))



class PlayerTest(unittest.TestCase):
    def test_no_display_is_a_full_player(self):
        self.assertIsNone(wall.player(None, None))
        self.assertIsNone(wall.player('', ''))

    def test_a_display_is_density_and_font_scale(self):
        self.assertEqual((1.0, 1.0, None, None, None), wall.player('1', None))
        self.assertEqual((2.0, 1.3, None, None, None), wall.player('2', '1.3'))
        self.assertEqual((1.0, 1.5, None, None, None), wall.player(None, '1.5'))

    def test_nonsense_is_ignored_not_an_error(self):
        self.assertIsNone(wall.player('abc', '-1'))
        self.assertEqual((1.0, 1.0, None, None, None), wall.player('0', '1'))
        self.assertEqual((3.0, 1.0, None, None, None), wall.player('3', '99'))

    def test_a_whole_screen_is_both_sides_or_neither(self):
        self.assertEqual((1.0, 1.0, 800, 480, None), wall.player('1', '1', '800', '480'))
        self.assertEqual((1.0, 1.0, None, None, None), wall.player('1', '1', '800', None))
        self.assertEqual((1.0, 1.0, None, None, None), wall.player('1', '1', '0', '480'))
        # A profile by name; anything that isn't a name is dropped.
        self.assertEqual((1.0, 1.0, None, None, 'kobento-mcu'), wall.player('1', '1', profile='kobento-mcu'))
        self.assertEqual((1.0, 1.0, None, None, None), wall.player('1', '1', profile='../etc'))
        self.assertEqual((1.0, 1.0, None, None, None), wall.player('1', '1', profile='Kobento MCU'))
        # A size alone doesn't make a small player.
        self.assertIsNone(wall.player(None, None, '800', '480'))

class HiddenTest(unittest.TestCase):
    def test_hidden_then_shown_again(self):
        store = wall.ValueStore(epoch=1)
        self.assertTrue(store.hide('c'))
        self.assertFalse(store.hide('c'))
        self.assertEqual(store.changes(['c'], 0), {'c': {'hidden': True}})
        self.assertTrue(store.update('c', 'sha', '/b', True, {'a.s0.state': 'x'}))
        self.assertEqual(store.changes(['c'], 1)['c']['design'], 'sha')


class ConditionalTest(unittest.TestCase):
    STATES = {'sensor.oven': '200', 'binary_sensor.door': 'on', 'input_number.limit': '150'}

    def shown(self, card):
        return wall.shown_card(card, self.STATES.get)

    def cond(self, conditions):
        return {'type': 'conditional', 'conditions': conditions,
                'card': {'type': 'tile', 'entity': 'sensor.oven'}}

    def test_a_met_condition_shows_the_inner_card(self):
        card = self.cond([{'entity': 'binary_sensor.door', 'state': 'on'}])
        self.assertEqual(self.shown(card), {'type': 'tile', 'entity': 'sensor.oven'})

    def test_an_unmet_or_missing_condition_hides_it(self):
        self.assertIsNone(self.shown(self.cond([{'entity': 'binary_sensor.door', 'state_not': ['on']}])))
        self.assertIsNone(self.shown(self.cond([{'entity': 'binary_sensor.gone', 'state': 'on'}])))

    def test_numeric_state_reads_a_number_or_an_entity(self):
        self.assertIsNotNone(self.shown(self.cond([{'condition': 'numeric_state', 'entity': 'sensor.oven', 'above': 100}])))
        self.assertIsNone(self.shown(self.cond([{'condition': 'numeric_state', 'entity': 'sensor.oven', 'below': 'input_number.limit'}])))

    def test_and_or_not(self):
        door = {'entity': 'binary_sensor.door', 'state': 'on'}
        off = {'entity': 'binary_sensor.door', 'state': 'off'}
        self.assertIsNotNone(self.shown(self.cond([{'condition': 'or', 'conditions': [off, door]}])))
        self.assertIsNone(self.shown(self.cond([{'condition': 'and', 'conditions': [off, door]}])))
        self.assertIsNotNone(self.shown(self.cond([{'condition': 'not', 'conditions': [off]}])))

    def test_nested_conditionals_unwrap(self):
        inner = self.cond([{'entity': 'binary_sensor.door', 'state': 'on'}])
        outer = {'type': 'conditional', 'conditions': [], 'card': inner}
        self.assertEqual(self.shown(outer)['type'], 'tile')

    def test_a_condition_only_home_assistant_can_evaluate_is_an_error(self):
        card = self.cond([{'condition': 'template', 'value_template': '{{ true }}'}])
        with self.assertRaises(wall.WallError):
            self.shown(card)

    def test_watched_entities_include_the_conditions(self):
        card = self.cond([{'condition': 'or', 'conditions': [{'entity': 'binary_sensor.door', 'state': 'on'}]}])
        self.assertEqual(wall.watched_entities(card), ['binary_sensor.door', 'sensor.oven'])


class ScreensTest(unittest.TestCase):
    CONFIG = {'views': [{'type': 'sections', 'sections': [
        {'cards': [
            {'type': 'heading', 'heading': 'Speeds', 'icon': 'mdi:speedometer'},
            {'type': 'grid', 'columns': 3, 'cards': [
                {'type': 'tile', 'entity': 'sensor.a'}, {'type': 'tile', 'entity': 'sensor.b'}]},
            {'type': 'tile', 'entity': 'light.x'},
            {'type': 'tile', 'entity': 'light.y'},
            {'type': 'heading', 'heading': 'Later'},
            {'type': 'history-graph', 'entities': ['sensor.a'], 'grid_options': {'rows': 5}},
        ]}]}]}

    def setUp(self):
        self.stack = wall.STACK_SCREENS
        wall.STACK_SCREENS = True

    def tearDown(self):
        wall.STACK_SCREENS = self.stack

    def layout(self, height=480, apart=frozenset(), skip=frozenset()):
        return wall.screens(wall.layout(self.CONFIG, None), height, apart, skip)

    def test_a_screen_holds_at_most_its_entity_budget(self):
        budget = wall.SCREEN_ENTITIES
        wall.SCREEN_ENTITIES = 3
        try:
            screens = self.layout(4096)['views'][0]['screens']
        finally:
            wall.SCREEN_ENTITIES = budget
        # heading + grid (2) + the two tiles (2) would be 4: the tiles start the next screen.
        self.assertEqual(screens[0]['id'], 'v0.p0-1')

    def test_without_stacks_each_card_with_entities_is_a_screen(self):
        wall.STACK_SCREENS = False
        screens = self.layout()['views'][0]['screens']
        self.assertEqual([s['id'] for s in screens], ['v0.p1-1', 'v0.p2-2', 'v0.p3-3', 'v0.p5-5'])

    def test_a_grid_is_estimated_from_its_rows(self):
        cards = self.layout()['views'][0]['sections'][0]['cards']
        self.assertEqual(cards[1]['h'], 1)

    def test_cards_pack_into_screens_and_a_heading_moves_with_its_cards(self):
        screens = self.layout()['views'][0]['screens']
        # 480 dp is 7 rows: heading, grid, the two tiles side by side; then the heading carried
        # over. Showing the tiles again would be a step of 2 rows, under half a screen: a clean page.
        self.assertEqual([s['id'] for s in screens], ['v0.p0-3', 'v0.p4-5'])
        self.assertEqual(screens[1]['cards'], ['v0.s0.c4', 'v0.s0.c5'])

    def test_a_long_view_steps_down_most_of_a_screen_at_a_time(self):
        rows = [{'type': 'tile', 'entity': f'sensor.t{i}', 'grid_options': {'columns': 'full'}}
                for i in range(10)]
        config = {'views': [{'type': 'sections', 'sections': [{'cards': rows}]}]}
        screens = wall.screens(wall.layout(config, None), 480)['views'][0]['screens']
        # 7 rows a screen, a step of 5 (80% of 7, to a row): each screen shows 2 rows again.
        self.assertEqual([s['id'] for s in screens], ['v0.p0-6', 'v0.p5-9'])
        firsts = [int(s['id'].split('.p')[1].split('-')[0]) for s in screens]
        self.assertTrue(all(b - a >= 1 for a, b in zip(firsts, firsts[1:])))

    def test_a_card_that_doesnt_stack_is_a_screen_of_its_own(self):
        screens = self.layout(4096, apart={'v0.s0.c1'})['views'][0]['screens']
        # The heading before the grid goes with it; the tiles and what follows fill the next.
        self.assertEqual([s['id'] for s in screens], ['v0.p1-1', 'v0.p2-5'])

    def test_a_card_with_no_design_is_left_out_and_the_rest_packed_around_it(self):
        screens = self.layout(4096, skip={'v0.s0.c1'})['views'][0]['screens']
        # The screen runs on over it, and its id says so.
        self.assertEqual([s['id'] for s in screens], ['v0.p0-5x1'])
        self.assertNotIn('v0.s0.c1', screens[0]['cards'])
        card = wall.find_card(self.CONFIG, 'v0.p0-5x1')
        self.assertEqual(wall.entity_ids(card), ['light.x', 'light.y', 'sensor.a'])
        self.assertEqual(wall.screen_size('v0.p0-5x1'), 5)
        self.assertEqual(wall.screen_left_out('v0.p0-5x1'), {1})
        # A heading with nothing after it on its screen has nothing to show.
        screens = self.layout(4096, skip={'v0.s0.c5'})['views'][0]['screens']
        self.assertEqual([s['id'] for s in screens], ['v0.p0-3'])

    def test_a_screen_leaves_out_only_cards_inside_its_run(self):
        for bad in ('v0.p0-3x0', 'v0.p0-3x3', 'v0.p0-3x9'):
            with self.assertRaises(wall.WallError):
                wall.find_card(self.CONFIG, bad)

    def test_a_screen_is_a_stack_of_shelves(self):
        card = wall.find_card(self.CONFIG, 'v0.p0-3')
        self.assertEqual(card['type'], 'vertical-stack')
        self.assertEqual([c['type'] for c in card['cards']], ['heading', 'grid', 'horizontal-stack'])
        self.assertEqual(wall.entity_ids(card), ['sensor.a', 'sensor.b', 'light.x', 'light.y'])
        with self.assertRaises(wall.WallError):
            wall.find_card(self.CONFIG, 'v0.p3-9')

    def test_screen_ids_and_their_cards(self):
        self.assertEqual(wall.screen_range('v0.p4-5'), (0, 4, 5))
        self.assertEqual(wall.screen_size('v0.p4-5'), 2)
        self.assertEqual(wall.screen_size('v0.s0.c4'), 0)
        lay = wall.layout(self.CONFIG, None)
        self.assertEqual(wall.screen_cards(lay, 0, 4, 5), [(4, 'v0.s0.c4'), (5, 'v0.s0.c5')])
        # A screen of one card is that card, not a stack of it.
        self.assertEqual(wall.find_card(self.CONFIG, 'v0.p5-5')['type'], 'history-graph')

    PRINTER = {'views': [{'type': 'sections', 'sections': [
        {'cards': [{'type': 'heading', 'heading': 'P2s'}, {'type': 'picture-entity', 'entity': 'camera.p2s'}]},
        {'cards': [{'type': 'tile', 'entity': f'sensor.t{i}'} for i in range(8)]
         + [{'type': 'button', 'entity': 'button.open', 'grid_options': {'columns': 12, 'rows': 1}}]},
        {'cards': [{'type': 'heading', 'heading': 'Remote Compose'},
                   {'type': 'tile', 'entity': 'sensor.s'}, {'type': 'tile', 'entity': 'sensor.p'}]},
    ]}]}

    def test_a_wide_screen_has_home_assistants_section_columns(self):
        view = wall.layout(self.PRINTER, None)['views'][0]
        self.assertEqual(wall.section_columns(view, None), 1)
        self.assertEqual(wall.section_columns(view, 600), 1)
        self.assertEqual(wall.section_columns(view, 776), 2)
        self.assertEqual(wall.section_columns(view, 1280), 3)
        self.assertEqual(wall.section_columns(dict(view, max_columns=2), 1280), 2)
        self.assertEqual(wall.section_columns(dict(view, type='masonry'), 1280), 1)

    def test_sections_stand_side_by_side_on_a_wide_screen(self):
        screens = wall.screens(wall.layout(self.PRINTER, None), 432, width=776)['views'][0]['screens']
        # The picture beside the tiles, then the button again over the last section: two screens
        # where one column took three.
        self.assertEqual([s['id'] for s in screens], ['v0.p0-10c2', 'v0.p10-13c2'])
        card = wall.find_card(self.PRINTER, 'v0.p0-10c2')
        band = card['cards'][0]
        self.assertEqual(band['type'], 'horizontal-stack')
        left, right = band['cards']
        self.assertEqual([c['type'] for c in left['cards']], ['heading', 'picture-entity'])
        # The section's tiles two to a row in its column: four across the screen.
        self.assertEqual(right['cards'][0]['type'], 'horizontal-stack')
        self.assertEqual(len(right['cards'][0]['cards']), 2)
        self.assertEqual(wall.screen_range('v0.p0-10c2'), (0, 0, 10))
        self.assertEqual(wall.screen_size('v0.p0-10c2'), 11)
        # Without a width the sections stack, one across the screen at a time, as before.
        flat = wall.screens(wall.layout(self.PRINTER, None), 432)['views'][0]['screens']
        self.assertTrue(all(not s['id'].endswith('c2') for s in flat))

    def test_a_column_with_nothing_on_a_screen_keeps_its_width(self):
        card = wall.find_card(self.PRINTER, 'v0.p10-13c2')
        top, bottom = card['cards']
        # The button's column beside an empty one; the last section in the first column of its
        # band, as Home Assistant leaves a short row of sections its column's width.
        self.assertEqual(top['cards'][0], wall.COLUMN_SPACER)
        self.assertEqual(top['cards'][1]['type'], 'button')
        self.assertEqual(bottom['cards'][1], wall.COLUMN_SPACER)

    def test_a_section_spanning_columns_goes_across_the_screen(self):
        config = json.loads(json.dumps(self.PRINTER))
        config['views'][0]['sections'][1]['column_span'] = 2
        screens = wall.screens(wall.layout(config, None), 4096, width=776)['views'][0]['screens']
        card = wall.find_card(config, screens[0]['id'])
        kinds = [c['type'] for c in card['cards']]
        # Its own band, between the two others (each a band with a spacer beside it).
        self.assertEqual(kinds[0], 'horizontal-stack')
        self.assertEqual(card['cards'][0]['cards'][1], wall.COLUMN_SPACER)
        self.assertIn('button', [c['type'] for c in card['cards'][1:]])

    def test_a_wide_section_holds_its_full_width_cards_side_by_side(self):
        config = {'views': [{'type': 'sections', 'sections': [{'column_span': 2, 'cards': [
            {'type': 'heading', 'heading': 'P2s'},
            {'type': 'picture-entity', 'entity': 'camera.p2s'},
            {'type': 'grid', 'columns': 2, 'cards': [{'type': 'tile', 'entity': f'sensor.t{i}'} for i in range(8)]},
        ]}]}]}
        screens = wall.screens(wall.layout(config, None), 432, width=776)['views'][0]['screens']
        self.assertEqual([s['id'] for s in screens], ['v0.p0-2c2'])
        card = wall.find_card(config, 'v0.p0-2c2')
        # The heading across, the picture beside the tiles, each in half the screen.
        self.assertEqual([c['type'] for c in card['cards']], ['heading', 'horizontal-stack'])
        self.assertEqual([c['type'] for c in card['cards'][1]['cards']], ['picture-entity', 'grid'])
        # One column: stacked across the screen, as before.
        flat = wall.screens(wall.layout(config, None), 432)['views'][0]['screens']
        self.assertTrue(all(not s['id'].endswith('c2') for s in flat))

    def test_the_store_stamps_each_version_of_a_views_screens(self):
        store = wall.ValueStore(epoch=1)
        self.assertTrue(store.set_screens(0, ['v0.p0-3']))
        self.assertFalse(store.set_screens(0, ['v0.p0-3']))
        self.assertEqual(store.screens_since(0, 0), ['v0.p0-3'])
        self.assertIsNone(store.screens_since(0, 1))
        store.set_screens(0, ['v0.p0-1', 'v0.p2-3'])
        self.assertEqual(store.screens_since(0, 1), ['v0.p0-1', 'v0.p2-3'])

    def test_a_panel_card_drops_what_its_template_cant_draw(self):
        card = wall.panel_card(wall.find_card(self.CONFIG, 'v0.p0-3'))
        self.assertEqual(card['cards'][0], {'type': 'heading', 'heading': 'Speeds'})
        md = wall.panel_card({'type': 'markdown', 'text_only': True,
                              'content': '# Hi\n**Down:** {{ x }} · [link](http://x) _a_ *b* `c`\n* item'})
        self.assertEqual(md, {'type': 'markdown', 'content': '# Hi\nDown: {{ x }} · link a b c\n* item'})

    def test_a_panel_picture_is_drawn_from_its_entity_alone(self):
        camera = {'type': 'picture-entity', 'entity': 'camera.door', 'camera_image': 'camera.door',
                  'camera_view': 'auto', 'aspect_ratio': '16:9', 'name': 'Door',
                  'tap_action': {'action': 'more-info'}}
        grid = wall.panel_card({'type': 'grid', 'columns': 3, 'cards': [camera]})
        self.assertEqual(grid['cards'][0], {'type': 'picture-entity', 'entity': 'camera.door', 'name': 'Door'})
        # Another entity's picture isn't one a design draws: left as it is.
        over = dict(camera, entity='binary_sensor.door')
        self.assertEqual(wall.panel_card(over), over)

    def test_a_picture_takes_at_most_its_share_of_the_screen(self):
        camera = {'type': 'picture-entity', 'entity': 'camera.door'}
        player = (1.0, 1.0, 776, 432)
        # Full width, 776 x 436 at 16:9: past a quarter of the screen, so half as wide (388 x 218).
        capped = wall.panel_card(camera, player, 0.25)
        self.assertEqual(capped, {'type': 'grid', 'columns': 2, 'square': False, 'cards': [camera]})
        self.assertEqual(wall.entity_ids(capped), ['camera.door'])
        # Three to a grid row are each 259 wide: within it already.
        grid = {'type': 'grid', 'columns': 3, 'cards': [camera, camera, camera]}
        self.assertEqual(wall.panel_card(grid, player, 0.25)['cards'], [camera] * 3)
        # A tenth of the screen: a third as wide (259 x 146).
        self.assertEqual(wall.panel_card(camera, player, 0.1)['columns'], 3)
        # A conditional's card, and a stack's, are capped where they sit; no limit, none are.
        cond = {'type': 'conditional', 'conditions': [], 'card': camera}
        self.assertEqual(wall.panel_card(cond, player, 0.25)['card']['type'], 'grid')
        self.assertEqual(wall.panel_card(camera, player, None), camera)
        self.assertEqual(wall.panel_card(camera), camera)

    def test_picture_max_is_a_percentage(self):
        self.assertEqual(wall.picture_max('25'), 0.25)
        self.assertIsNone(wall.picture_max(None))
        self.assertIsNone(wall.picture_max('0'))
        self.assertIsNone(wall.picture_max('150'))

    def test_a_hidden_child_leaves_its_stack(self):
        stack = {'type': 'vertical-stack', 'cards': [
            {'type': 'conditional', 'conditions': [{'entity': 'light.x', 'state': 'on'}],
             'card': {'type': 'tile', 'entity': 'light.x'}},
            {'type': 'tile', 'entity': 'light.y'}]}
        self.assertEqual(wall.shown_card(stack, {'light.x': 'off'}.get)['cards'], [{'type': 'tile', 'entity': 'light.y'}])
        self.assertIsNone(wall.shown_card({'type': 'vertical-stack', 'cards': [stack['cards'][0]]}, {}.get))
        self.assertEqual(wall.watched_entities(stack), ['light.x', 'light.y'])

if __name__ == '__main__':
    unittest.main()
