"""The conversation agent's turn on an edit session (agent_turn.py): what it is told, what it
answers and how its steps read; and the edit session rules it relies on (new dashboards, which
notes it still owes an answer)."""
import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2] / 'custom_components/terrazzo'


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, ROOT / file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


# Neither imports Home Assistant, so both load on their own outside the package.
turn = load('terrazzo_agent_turn', 'agent_turn.py')
es = load('terrazzo_edit_sessions', 'edit_sessions.py')

BASELINE = {'views': [{'type': 'sections', 'sections': [
    {'type': 'grid', 'cards': [{'type': 'tile', 'entity': 'light.kitchen'}]}]}]}


class TurnTest(unittest.TestCase):
    def session(self, request='Add the kitchen light', scope=None):
        sessions = es.EditSessions()
        return sessions, sessions.create('home', request, BASELINE, 'u', 1, scope)

    def test_a_first_turn_answers_the_request_and_names_the_session(self):
        _, session = self.session()
        self.assertEqual('Add the kitchen light', turn.turn_text(session))
        prompt = turn.extra_system_prompt(session, 'Test home')
        self.assertIn(session['id'], prompt)
        self.assertIn('get_edit_session', prompt)
        self.assertIn("'Test home'", prompt)
        self.assertNotIn('The conversation so far', prompt)

    def test_a_later_turn_answers_only_new_notes_with_the_rest_recapped(self):
        sessions, session = self.session()
        sessions.assistant_started(session['id'], 2)
        sessions.agent_message(session['id'], 'Which light?', 'u', 3)
        sessions.note(session['id'], 'The one over the sink', 'u', 4, target='light.sink')
        self.assertEqual('(About the card light.sink) The one over the sink', turn.turn_text(session))
        prompt = turn.extra_system_prompt(session, 'Test home')
        self.assertIn('You: Which light?', prompt)
        self.assertNotIn('over the sink', prompt)
        sessions.submit(session['id'], BASELINE, 'Sink light', lambda e: True, 'u', 5)
        sessions.assistant_started(session['id'], 6)
        sessions.note(session['id'], 'Bigger', 'u', 7)
        prompt = turn.extra_system_prompt(session, 'Test home')
        self.assertIn('Person: (About the card light.sink) The one over the sink', prompt)
        self.assertIn('You: [submitted a draft: Sink light]', prompt)
        self.assertEqual('Bigger', turn.turn_text(session))

    def test_a_long_recap_keeps_the_newest(self):
        sessions, session = self.session()
        sessions.assistant_started(session['id'], 2)
        for i in range(400):
            sessions.agent_message(session['id'], f'message {i:03} ' + 'x' * 40, 'u', 3 + i)
        prompt = turn.extra_system_prompt(session, 'Test home')
        self.assertIn('message 399', prompt)
        self.assertNotIn('message 000', prompt)

    def test_scope_shapes_the_prompt(self):
        _, section = self.session(scope={'kind': 'section', 'view': 0, 'section': 0, 'label': 'Garage'})
        self.assertIn('views[0].sections[0]', turn.extra_system_prompt(section, 'h'))
        _, new = self.session(scope={'kind': 'new_dashboard', 'title': 'Garden'})
        self.assertIn("NEW dashboard titled 'Garden'", turn.extra_system_prompt(new, 'h'))

    def test_the_reply_is_clipped_or_stands_in_for_a_silent_draft(self):
        self.assertEqual('Done.', turn.reply_text('  Done. ', False))
        self.assertEqual("I've submitted a draft.", turn.reply_text('', True))
        self.assertEqual('', turn.reply_text(None, False))
        self.assertEqual(turn.MAX_REPLY_CHARS, len(turn.reply_text('x' * 5000, False)))

    def test_each_step_is_described_in_words(self):
        self.assertEqual(('kitchen · light', '1 found: Kitchen', True), turn.describe_step(
            'find_entities', {'query': 'kitchen', 'domain': 'light'}, {'entities': [{'name': 'Kitchen'}]}))
        self.assertEqual(('Sink light', 'Draft submitted', True),
                         turn.describe_step('submit_draft', {'summary': 'Sink light'}, {'draft_id': 'd'}))
        self.assertEqual(('', 'Refused', False), turn.describe_step('submit_draft', {}, {'error': 'Refused'}))
        self.assertEqual(('', 'Garage door', True), turn.describe_step(
            'find_designs', {}, {'designs': [{'title': 'Garage door'}]}))


class SessionRulesTest(unittest.TestCase):
    def test_assistant_answers_a_new_session_then_each_new_note(self):
        sessions = es.EditSessions()
        session = sessions.create('p', 'Hi', BASELINE, 'u', 1)
        pending = es.EditSessions.assistant_pending
        self.assertTrue(pending(session))
        sessions.assistant_started(session['id'], 2)
        sessions.agent_message(session['id'], 'Hello!', 'u', 3)
        self.assertFalse(pending(session))
        sessions.note(session['id'], 'Add a clock', 'u', 4)
        self.assertTrue(pending(session))
        sessions.assistant_started(session['id'], 5)
        self.assertFalse(pending(session))
        sessions.note(session['id'], 'Never mind', 'u', 6)
        sessions.discard(session['id'], 'u', 7)
        self.assertFalse(pending(session))  # A discarded session owes nothing.

    def test_a_session_an_outside_agent_already_answered_gets_no_greeting(self):
        sessions = es.EditSessions()
        session = sessions.create('p', 'Hi', BASELINE, 'u', 1)
        sessions.agent_message(session['id'], 'Hello from Claude', 'mcp', 2)
        self.assertFalse(es.EditSessions.assistant_pending(session))

    def test_failures_show_in_the_summary(self):
        sessions = es.EditSessions()
        session = sessions.create('p', 'Hi', BASELINE, 'u', 1)
        sessions.assistant_failed(session['id'], 'Key refused', 2)
        self.assertEqual('Key refused', es.summary(session)['assistant_error'])
        sessions.assistant_started(session['id'], 3)
        self.assertIsNone(es.summary(session)['assistant_error'])

    def test_new_dashboard_paths_have_a_hyphen_and_dont_collide(self):
        self.assertEqual('dashboard-garden', es.new_dashboard_path('Garden', set()))
        self.assertEqual('garden-room', es.new_dashboard_path('Garden room!', set()))
        self.assertEqual('dashboard-garden-2', es.new_dashboard_path('Garden', {'dashboard-garden'}))
        self.assertEqual('dashboard-unicode', es.new_dashboard_path('Ünïcode', set()))
        self.assertEqual('new-dashboard', es.new_dashboard_path('!!!', set()))

    def test_new_dashboard_scope(self):
        scope = es.validate_scope({'kind': 'new_dashboard', 'title': ' Garden ', 'icon': 'mdi:flower'}, None)
        self.assertEqual({'kind': 'new_dashboard', 'title': 'Garden', 'label': 'Garden', 'icon': 'mdi:flower'}, scope)
        with self.assertRaises(es.SessionError):
            es.validate_scope({'kind': 'new_dashboard', 'title': ''}, None)
        with self.assertRaises(es.SessionError):
            es.validate_scope({'kind': 'new_dashboard', 'title': 'x', 'icon': 'flower'}, None)
        sessions = es.EditSessions()
        new = sessions.create('dashboard-garden', '', es.new_dashboard_baseline('Garden'), 'u', 1, scope)
        sessions.create('other', '', BASELINE, 'u', 1)
        self.assertTrue(es.is_new_dashboard(new))
        self.assertEqual([new['id']], [s['id'] for s in sessions.new_dashboards('u', 2)])
        self.assertEqual([], sessions.new_dashboards('someone-else', 2))
        self.assertEqual(1, len(sessions.new_dashboards('someone-else', 2, is_admin=True)))
        # Its first draft can be anything that's a dashboard: the scope holds it to nothing.
        sessions.submit(new['id'], BASELINE, 'First', lambda e: True, 'u', 3)


class SetupTest(unittest.TestCase):
    """What Terrazzo tells a person while the agent isn't set up (agent_turn.py's setup helpers)."""

    def test_tools_on_reads_the_agents_api_list(self):
        self.assertTrue(turn.tools_on(['assist', 'terrazzo_dashboards'], True))
        self.assertTrue(turn.tools_on('terrazzo_dashboards', False))
        self.assertFalse(turn.tools_on(['assist'], False))
        self.assertFalse(turn.tools_on([], True))
        # Absent means none only for an AI integration's conversation agent.
        self.assertFalse(turn.tools_on(None, True))
        self.assertIsNone(turn.tools_on(None, False))
        self.assertIsNone(turn.tools_on({'odd': 1}, True))

    def test_the_api_id_is_the_registered_one(self):
        source = (ROOT / 'llm_tools.py').read_text()
        self.assertIn(f'API_ID = "{turn.API_ID}"', source)
        self.assertIn(f'API_NAME = "{turn.API_NAME}"', source)

    def test_a_built_in_agent_that_was_on_names_its_replacement(self):
        self.assertEqual({'service': 'OpenRouter', 'integration': 'OpenRouter'}, turn.moved_placeholders('openrouter'))
        self.assertEqual('Google Generative AI', turn.moved_placeholders('gemini')['integration'])
        self.assertIsNotNone(turn.moved_placeholders('openai_compatible'))
        self.assertIsNone(turn.moved_placeholders('off'))
        self.assertIsNone(turn.moved_placeholders(None))

    def test_the_no_tools_reply_says_what_to_tick(self):
        reply = turn.no_tools_reply('Garden agent')
        self.assertIn('Garden agent', reply)
        self.assertIn('"Terrazzo dashboards"', reply)
        self.assertIn('This agent', turn.no_tools_reply(None))

    def test_every_setup_repair_has_words(self):
        import json
        for file in ('strings.json', 'translations/en.json'):
            issues = json.loads((ROOT / file).read_text())['issues']
            for key in ('agent_moved', 'agent_no_tools'):
                self.assertIn('title', issues[key], f'{file} {key}')
                self.assertIn('description', issues[key], f'{file} {key}')
            self.assertIn('{service}', issues['agent_moved']['description'])
            self.assertIn('{integration}', issues['agent_no_tools']['description'])



if __name__ == '__main__':
    unittest.main()
