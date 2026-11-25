"""The dashboard chat (assist_chat.py): what the agent is told about the open dashboard, where its
drafts go, and what the chat answers."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    'terrazzo_assist_chat',
    Path(__file__).resolve().parents[2] / 'custom_components/terrazzo/assist_chat.py')
chat = importlib.util.module_from_spec(spec)
spec.loader.exec_module(chat)


class ScreenPromptTest(unittest.TestCase):
    def test_names_the_dashboard_view_and_what_is_on_screen(self):
        prompt = chat.screen_prompt(
            'Test home', 'Home', 'home-main', 'Kitchen',
            [('light.kitchen', 'Kitchen light', 'on'), ('sensor.temp', None, '21.5 °C')])
        self.assertIn("'Test home'", prompt)
        self.assertIn("the dashboard 'Home', on its 'Kitchen' view", prompt)
        self.assertIn('url_path is home-main', prompt)
        self.assertIn('- light.kitchen (Kitchen light): on', prompt)
        self.assertIn('- sensor.temp (sensor.temp): 21.5 °C', prompt)
        self.assertIn('Assist tools', prompt)
        self.assertIn('without a session_id', prompt)

    def test_an_open_session_takes_the_drafts(self):
        prompt = chat.screen_prompt('Test home', None, None, session_id='abc')
        self.assertIn("'Overview'", prompt)
        self.assertIn('omit url_path', prompt)
        self.assertIn('session_id abc', prompt)
        self.assertNotIn('without a session_id', prompt)
        self.assertNotIn('cards on the person', prompt)

    def test_long_states_are_clipped(self):
        prompt = chat.screen_prompt('H', 'D', 'd', on_screen=[('sensor.x', 'X', 'y' * 500)])
        self.assertNotIn('y' * 100, prompt)


class CleanTest(unittest.TestCase):
    def test_text_is_required_and_bounded(self):
        with self.assertRaises(ValueError):
            chat.clean_text('  ')
        with self.assertRaises(ValueError):
            chat.clean_text(None)
        self.assertEqual(chat.MAX_TEXT, len(chat.clean_text('x' * 5000)))

    def test_entities_are_well_formed_readable_unique_and_bounded(self):
        readable = lambda entity_id: entity_id != 'lock.front'
        self.assertEqual(
            ['light.a', 'sensor.b'],
            chat.clean_entities(['light.a', 'nodot', 7, 'lock.front', 'light.a', 'sensor.b'], readable))
        many = [f'sensor.s{i}' for i in range(200)]
        self.assertEqual(chat.MAX_ON_SCREEN, len(chat.clean_entities(many, lambda _: True)))

    def test_apis_on(self):
        self.assertEqual({'assist': True, 'dashboards': True},
                         chat.apis_on(['assist', 'terrazzo_dashboards']))
        self.assertEqual({'assist': True, 'dashboards': False}, chat.apis_on('assist'))
        self.assertIsNone(chat.apis_on(None))
        self.assertIsNone(chat.apis_on(3))


class ForChatTest(unittest.TestCase):
    def test_a_draft_goes_to_the_open_session(self):
        self.assertEqual(
            {'config': {}, 'session_id': 's1'},
            chat.for_chat('submit_draft', {'config': {}, 'url_path': 'home', 'revision': 'r'}, 'home', 's1'))

    def test_without_a_session_a_draft_names_the_open_dashboard(self):
        self.assertEqual(
            {'config': {}, 'revision': 'r', 'url_path': 'home'},
            chat.for_chat('submit_draft', {'config': {}, 'revision': 'r'}, 'home', None))
        # Overview: nothing to add.
        self.assertEqual({'config': {}}, chat.for_chat('submit_draft', {'config': {}}, None, None))

    def test_another_dashboard_or_a_new_one_is_left_alone(self):
        other = {'config': {}, 'url_path': 'other', 'revision': 'r'}
        self.assertEqual(other, chat.for_chat('submit_draft', other, 'home', 's1'))
        new = {'config': {}, 'new_dashboard_title': 'Garden'}
        self.assertEqual(new, chat.for_chat('submit_draft', new, 'home', 's1'))
        named = {'config': {}, 'session_id': 's2'}
        self.assertEqual(named, chat.for_chat('submit_draft', named, 'home', 's1'))

    def test_other_tools_are_untouched(self):
        self.assertEqual({'url_path': 'x'}, chat.for_chat('get_dashboard', {'url_path': 'x'}, 'home', 's1'))


class ReplyTest(unittest.TestCase):
    def test_drafted_session_is_read_from_a_successful_result(self):
        self.assertEqual('s1', chat.drafted_session('submit_draft', {'session_id': 's1'}))
        self.assertIsNone(chat.drafted_session('submit_draft', {'error': 'no', 'session_id': 's1'}))
        self.assertIsNone(chat.drafted_session('get_dashboard', {'session_id': 's1'}))
        # A draft to a session already open returns no id: its arguments name it.
        self.assertEqual('s2', chat.drafted_session('submit_draft', {'status': 'ok'}, {'session_id': 's2'}))
        self.assertEqual('s3', chat.drafted_session('submit_draft', {'session_id': 's3'}, {'session_id': 's2'}))
        self.assertIsNone(chat.drafted_session('start_edit_session', {}, {'session_id': 's2'}))

    def test_a_draft_without_words_still_says_something(self):
        answer = chat.reply('', 'answer', 'c1', 's1', True, [])
        self.assertEqual("I've drafted that on the dashboard.", answer['speech'])
        self.assertFalse(answer['error'])
        self.assertEqual('c1', answer['conversation_id'])
        self.assertTrue(chat.reply('No', 'error', None, None, False, [])['error'])


if __name__ == '__main__':
    unittest.main()
