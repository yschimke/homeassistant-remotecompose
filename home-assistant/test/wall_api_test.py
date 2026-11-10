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
        self.assertEqual((1.0, 1.0, None, None), wall.player('1', None))
        self.assertEqual((2.0, 1.3, None, None), wall.player('2', '1.3'))
        self.assertEqual((1.0, 1.5, None, None), wall.player(None, '1.5'))

    def test_nonsense_is_ignored_not_an_error(self):
        self.assertIsNone(wall.player('abc', '-1'))
        self.assertEqual((1.0, 1.0, None, None), wall.player('0', '1'))
        self.assertEqual((3.0, 1.0, None, None), wall.player('3', '99'))

    def test_a_whole_screen_is_both_sides_or_neither(self):
        self.assertEqual((1.0, 1.0, 800, 480), wall.player('1', '1', '800', '480'))
        self.assertEqual((1.0, 1.0, None, None), wall.player('1', '1', '800', None))
        self.assertEqual((1.0, 1.0, None, None), wall.player('1', '1', '0', '480'))
        # A size alone doesn't make a small player.
        self.assertIsNone(wall.player(None, None, '800', '480'))

if __name__ == '__main__':
    unittest.main()
