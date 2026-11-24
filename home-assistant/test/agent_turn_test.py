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

    def test_a_session_an_agent_opened_with_a_tool_gets_no_competing_first_turn(self):
        # edits.async_start(answered=True) marks it so as it is created.
        sessions = es.EditSessions()
        session = sessions.create('p', 'Hi', BASELINE, 'u', 1)
        session['assistant'] = {'handled': 0, 'error': None}
        self.assertFalse(es.EditSessions.assistant_pending(session))
        sessions.note(session['id'], 'Bigger, please', 'u', 2)
        self.assertTrue(es.EditSessions.assistant_pending(session))

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

    def test_a_busy_provider_is_retried_but_an_unavailable_entity_is_not(self):
        for text in ('503 UNAVAILABLE. The model is overloaded.', 'Error talking to API: 429',
                     'RESOURCE_EXHAUSTED', 'Rate limit reached', 'Service Unavailable'):
            self.assertTrue(turn.transient(text), text)
        for text in ('light.kitchen is unavailable', 'Invalid API key', '', None, 'id 15030'):
            self.assertFalse(turn.transient(text), text)
        self.assertTrue(turn.RETRY_DELAYS and all(d > 0 for d in turn.RETRY_DELAYS))

    def test_every_setup_repair_has_words(self):
        import json
        for file in ('strings.json', 'translations/en.json'):
            issues = json.loads((ROOT / file).read_text())['issues']
            for key in ('agent_moved', 'agent_no_tools'):
                self.assertIn('title', issues[key], f'{file} {key}')
                self.assertIn('description', issues[key], f'{file} {key}')
            self.assertIn('{service}', issues['agent_moved']['description'])
            self.assertIn('{integration}', issues['agent_no_tools']['description'])



DASHBOARD = {'views': [
    {'cards': [{'type': 'tile', 'entity': 'light.sink'}]},
    {'type': 'sections', 'sections': [
        {'type': 'grid', 'cards': [
            {'type': 'heading', 'heading': 'Kitchen'},
            {'type': 'custom:remote-compose-card', 'card': {'type': 'tile', 'entity': 'sensor.dw'},
             'design_id': 'pack:dw'},
            {'type': 'custom:remote-compose-card', 'card': {'type': 'tile', 'entity': 'switch.other'},
             'bindings': {'door': {'entity': 'binary_sensor.dw_door'}}},
            {'type': 'entities', 'entities': ['light.sink', {'entity': 'light.hob'}]},
        ]},
    ]},
]}


class ActiveTurnTest(unittest.TestCase):
    def test_cards_showing_an_entity_are_found_wrapped_bound_or_listed(self):
        self.assertEqual(es.card_positions(DASHBOARD, 'light.sink'), [
            {'view': 0, 'section': None, 'card': 0, 'entity': 'light.sink'},
            {'view': 1, 'section': 0, 'card': 3, 'entity': 'light.sink'},
        ])
        self.assertEqual([c['card'] for c in es.card_positions(DASHBOARD, 'sensor.dw')], [1])
        self.assertEqual([c['card'] for c in es.card_positions(DASHBOARD, 'binary_sensor.dw_door')], [2])
        self.assertEqual([c['card'] for c in es.card_positions(DASHBOARD, 'light.hob')], [3])
        self.assertEqual(es.card_positions(DASHBOARD, 'light.nope'), [])
        self.assertEqual(es.card_positions({'views': 'x'}, 'light.sink'), [])

    def test_a_turn_tracks_its_phase_steps_and_the_cards_it_is_about(self):
        sessions = es.EditSessions()
        session = sessions.create('k', 'Kitchen', DASHBOARD, 'u', 1,
                                  {'kind': 'section', 'view': 1, 'section': 0, 'label': 'Kitchen'})
        sessions.note(session['id'], 'Make it the real thing', 'u', 2, target='sensor.dw')
        sessions.note(session['id'], 'And this', 'u', 3, target='Wall light (tile)')
        self.assertEqual(es.note_entities(session), ['sensor.dw'])
        sessions.turn_started(session['id'], 4, es.note_entities(session))
        turn_state = es.summary(session)['active_turn']
        self.assertEqual(turn_state['phase'], 'reading')
        self.assertTrue(turn_state['cancellable'])
        self.assertEqual(turn_state['started_at'], 4)
        self.assertEqual(turn_state['target_cards'], [
            {'view': 1, 'section': 0, 'card': None, 'entity': None},
            {'view': 1, 'section': 0, 'card': 1, 'entity': 'sensor.dw'},
        ])
        sessions.turn_progress(session['id'], 5, tool='find_designs', status='Looking for card designs',
                               entity_id='light.hob')
        sessions.turn_progress(session['id'], 6, tool='find_designs', entity_id='light.hob')
        turn_state = session['active_turn']
        self.assertEqual((turn_state['phase'], turn_state['step'], turn_state['status']),
                         ('searching_designs', 2, 'Looking for card designs'))
        self.assertEqual(turn_state['target_cards'][-1], {'view': 1, 'section': 0, 'card': 3, 'entity': 'light.hob'})
        self.assertEqual(len(turn_state['target_cards']), 3)  # Not added twice.
        sessions.turn_progress(session['id'], 7, phase='retrying', status='Provider busy, retrying')
        self.assertEqual(session['active_turn']['phase'], 'retrying')
        sessions.turn_progress(session['id'], 8, tool='submit_draft')
        self.assertEqual(session['active_turn']['phase'], 'drafting')
        sessions.turn_ended(session['id'], 9)
        self.assertIsNone(es.summary(session)['active_turn'])
        sessions.turn_progress(session['id'], 10, tool='find_designs')  # No turn: nothing to track.
        self.assertIsNone(session['active_turn'])

    def test_cancelling_ends_the_turn_and_counts_its_notes_as_answered(self):
        sessions = es.EditSessions()
        session = sessions.create('k', 'Kitchen', DASHBOARD, 'u', 1)
        with self.assertRaisesRegex(es.SessionError, "isn't working"):
            sessions.cancel_turn(session['id'], 'u', 2)
        sessions.note(session['id'], 'Bigger', 'u', 2)
        sessions.assistant_started(session['id'], 3)
        sessions.turn_started(session['id'], 3)
        sessions.cancel_turn(session['id'], 'u', 4)
        self.assertIsNone(session['active_turn'])
        self.assertEqual(session['agent']['status'], 'Cancelled')
        self.assertFalse(es.EditSessions.assistant_pending(session))
        sessions.note(session['id'], 'Try again', 'u', 5)
        self.assertTrue(es.EditSessions.assistant_pending(session))

    def test_a_turn_running_when_home_assistant_stopped_is_gone_after_a_restart(self):
        sessions = es.EditSessions()
        session = sessions.create('k', 'Kitchen', DASHBOARD, 'u', 1)
        sessions.turn_started(session['id'], 2)
        reloaded = es.EditSessions(sessions.to_data())
        self.assertIsNone(reloaded.get(session['id'], 3)['active_turn'])

    def test_a_warm_conversation_is_reused_and_a_cold_one_is_not(self):
        sessions = es.EditSessions()
        session = sessions.create('k', 'Kitchen', DASHBOARD, 'u', 1)
        self.assertIsNone(es.warm_conversation(session, 2))
        sessions.remember_conversation(session['id'], 'conv1', 100)
        self.assertEqual(es.warm_conversation(session, 100 + es.WARM_CONVERSATION_SECONDS - 1), 'conv1')
        self.assertIsNone(es.warm_conversation(session, 100 + es.WARM_CONVERSATION_SECONDS))
        # Under Home Assistant's own five-minute idle limit, with margin.
        self.assertLess(es.WARM_CONVERSATION_SECONDS, 5 * 60)
        sessions.remember_conversation(session['id'], None, 200)
        self.assertIsNone(es.warm_conversation(session, 201))


class HouseNotesTest(unittest.TestCase):
    def test_a_warm_turn_leaves_the_recap_out_and_every_turn_gets_the_house_notes(self):
        sessions = es.EditSessions()
        session = sessions.create('home', 'Kitchen', BASELINE, 'u', 1)
        sessions.assistant_started(session['id'], 2)
        sessions.agent_message(session['id'], 'Which light?', 'u', 3)
        sessions.note(session['id'], 'The sink one', 'u', 4)
        cold = turn.extra_system_prompt(session, 'Test home', house_notes='Prefer Front Fascia packs.')
        self.assertIn('You: Which light?', cold)
        self.assertIn('Prefer Front Fascia packs.', cold)
        warm = turn.extra_system_prompt(session, 'Test home', warm=True, house_notes='Prefer Front Fascia packs.')
        self.assertNotIn('Which light?', warm)
        self.assertIn('Prefer Front Fascia packs.', warm)
        self.assertNotIn('standing notes', turn.extra_system_prompt(session, 'Test home', house_notes='  '))
        long = turn.extra_system_prompt(session, 'Test home', house_notes='x' * 5000)
        self.assertNotIn('x' * (turn.MAX_HOUSE_NOTES_CHARS + 1), long)

    def test_notes_are_set_per_home_and_dashboard_and_combined_for_a_turn(self):
        data = turn.with_note(None, None, '  Helper dashboards need bindings and actions. ', home=True)
        data = turn.with_note(data, 'kitchen', 'Cards 6 columns wide.')
        self.assertEqual(turn.notes_for(data, 'kitchen'),
                         'Helper dashboards need bindings and actions.\nFor this dashboard: Cards 6 columns wide.')
        self.assertEqual(turn.notes_for(data, None), 'Helper dashboards need bindings and actions.')
        data = turn.with_note(data, 'kitchen', '')
        self.assertEqual(data['dashboards'], {})
        self.assertEqual(turn.notes_for({}, 'kitchen'), '')
        self.assertEqual(len(turn.with_note(None, 'k', 'y' * 5000)['dashboards']['k']), turn.MAX_NOTE_CHARS)
        with self.assertRaises(ValueError):
            turn.with_note(None, 'k', 42)
        full = {'home': '', 'dashboards': {str(i): 'n' for i in range(turn.MAX_DASHBOARDS)}}
        with self.assertRaises(ValueError):
            turn.with_note(full, 'one-more', 'n')
        turn.with_note(full, '0', 'changed')  # An existing one can still change.


if __name__ == '__main__':
    unittest.main()
