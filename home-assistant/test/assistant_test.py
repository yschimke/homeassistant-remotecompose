"""The built-in dashboard agent's turn, driven with a fake model and a fake home; and the edit
session rules it relies on (new dashboards, which notes it still owes an answer)."""
import asyncio
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2] / 'custom_components/terrazzo'


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, ROOT / file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


# Neither imports Home Assistant, so both load on their own outside the package.
assistant = load('terrazzo_assistant', 'assistant.py')
es = load('terrazzo_edit_sessions', 'edit_sessions.py')

BASELINE = {'views': [{'type': 'sections', 'sections': [
    {'type': 'grid', 'cards': [{'type': 'tile', 'entity': 'light.kitchen'}]}]}]}


class FakeHome:
    name = 'Test home'

    def __init__(self, session):
        self.session = session
        self.drafts = []
        self.entities = [
            {'entity_id': 'light.kitchen', 'state': 'on', 'attributes': {'friendly_name': 'Kitchen'}},
            {'entity_id': 'sensor.soil', 'state': '31', 'attributes': {'friendly_name': 'Soil moisture'}},
        ]

    def states(self):
        return self.entities

    def state(self, entity_id):
        return next((e for e in self.entities if e['entity_id'] == entity_id), None)

    def overview(self):
        return {'areas': [{'area': 'Garden', 'entities': ['sensor.soil (Soil moisture): 31']}]}

    async def current_config(self, session):
        return session['drafts'][-1]['config'] if session['drafts'] else session['baseline']

    async def submit_draft(self, session_id, config, summary):
        known = {e['entity_id'] for e in self.entities}
        try:
            es.validate_draft(config, known.__contains__)
        except es.SessionError as refused:
            raise assistant.mcp.ToolError(str(refused))
        self.drafts.append((session_id, config, summary))
        return {'draft_id': f'd{len(self.drafts)}', 'status': 'submitted'}


def call(name, arguments, call_id='c1'):
    return {'id': call_id, 'type': 'function',
            'function': {'name': name, 'arguments': json.dumps(arguments)}}


class ScriptedModel:
    """Answers each request with the next scripted assistant message, keeping what it was sent."""

    def __init__(self, *replies):
        self.replies = list(replies)
        self.requests = []

    async def __call__(self, messages, tools):
        self.requests.append(json.loads(json.dumps(messages)))
        return self.replies.pop(0)


def run(coro):
    return asyncio.run(coro)


class TurnTest(unittest.TestCase):
    def setUp(self):
        self.sessions = es.EditSessions()
        self.session = self.sessions.create('home-page', 'Work on this dashboard with me.', BASELINE, 'person', 100)

    def test_looks_up_an_entity_drafts_and_says_what_changed(self):
        self.sessions.note(self.session['id'], 'Add the soil moisture', 'person', 101, target=None)
        draft = {'views': [{'type': 'sections', 'sections': [{'type': 'grid', 'cards': [
            {'type': 'tile', 'entity': 'light.kitchen'}, {'type': 'gauge', 'entity': 'sensor.soil'}]}]}]}
        model = ScriptedModel(
            {'content': None, 'tool_calls': [call('find_entities', {'query': 'soil'})]},
            {'content': '', 'tool_calls': [call('submit_draft', {'config_json': json.dumps(draft), 'summary': 'Soil gauge'}, 'c2')]},
            {'content': 'Added a soil moisture gauge.'},
        )
        home = FakeHome(self.session)
        statuses = []
        reply = run(assistant.run_turn(self.session, home, model, statuses.append))
        self.assertEqual('Added a soil moisture gauge.', reply)
        self.assertEqual([(self.session['id'], draft, 'Soil gauge')], home.drafts)
        self.assertEqual(['Looking up entities', 'Drafting'], statuses)
        # The model saw the entity lookup's answer before drafting.
        tool_reply = model.requests[1][-1]
        self.assertEqual('tool', tool_reply['role'])
        self.assertIn('sensor.soil', tool_reply['content'])
        # The system prompt carries the dashboard and the card guide; the note is the last turn.
        first = model.requests[0]
        self.assertIn('light.kitchen', first[0]['content'])
        self.assertIn('native_card_types', first[0]['content'])
        self.assertEqual({'role': 'user', 'content': 'Work on this dashboard with me.\n\nAdd the soil moisture'}, first[1])

    def test_a_refused_draft_goes_back_to_the_model_to_fix(self):
        bad = {'views': [{'cards': [{'type': 'tile', 'entity': 'light.attic'}]}]}
        model = ScriptedModel(
            {'tool_calls': [call('submit_draft', {'config_json': json.dumps(bad), 'summary': 'x'})]},
            {'content': 'There is no attic light in this home.'},
        )
        home = FakeHome(self.session)
        reply = run(assistant.run_turn(self.session, home, model))
        self.assertEqual('There is no attic light in this home.', reply)
        self.assertEqual([], home.drafts)
        self.assertIn('light.attic', model.requests[1][-1]['content'])

    def test_invalid_json_and_unknown_tools_are_errors_for_the_model(self):
        model = ScriptedModel(
            {'tool_calls': [
                call('submit_draft', {'config_json': '{nope', 'summary': 'x'}),
                {'id': 'c2', 'function': {'name': 'delete_everything', 'arguments': '{}'}},
                {'id': 'c3', 'function': {'name': 'find_entities', 'arguments': '[1]'}},
            ]},
            {'content': 'Done.'},
        )
        run(assistant.run_turn(self.session, FakeHome(self.session), model))
        answers = [m['content'] for m in model.requests[1] if m['role'] == 'tool']
        self.assertIn("isn't valid JSON", answers[0])
        self.assertIn('Unknown tool', answers[1])
        self.assertIn('JSON object', answers[2])

    def test_stops_after_max_steps(self):
        model = ScriptedModel(*[{'tool_calls': [call('home_overview', {})]}] * 3)
        reply = run(assistant.run_turn(self.session, FakeHome(self.session), model, max_steps=3))
        self.assertIn("couldn't finish", reply)

    def test_conversation_interleaves_notes_drafts_and_messages_with_card_targets(self):
        sid = self.session['id']
        self.sessions.agent_message(sid, 'Hello!', 'person', 101)
        self.sessions.note(sid, 'Make it bigger', 'person', 102, target='Kitchen tile')
        self.sessions.submit(sid, BASELINE, 'Bigger tile', lambda e: True, 'person', 103)
        self.sessions.note(sid, 'Thanks, now a title', 'person', 104)
        turns = assistant.conversation(self.session)
        self.assertEqual(['user', 'assistant', 'user', 'assistant', 'user'], [t['role'] for t in turns])
        self.assertEqual('(About the card Kitchen tile) Make it bigger', turns[2]['content'])
        self.assertIn('Bigger tile', turns[3]['content'])

    def test_scope_shapes_the_prompt(self):
        section = es.EditSessions().create('p', '', BASELINE, 'u', 1, {'kind': 'section', 'view': 0, 'section': 0, 'label': 'Lights'})
        self.assertIn('views[0].sections[0]', assistant.system_prompt(section, 'Home', BASELINE))
        new = es.EditSessions().create('dashboard-garden', '', es.new_dashboard_baseline('Garden'), 'u', 1,
                                       {'kind': 'new_dashboard', 'title': 'Garden'})
        self.assertIn("NEW dashboard titled 'Garden'", assistant.system_prompt(new, 'Home', new['baseline']))


class ToolSchemasTest(unittest.TestCase):
    def test_no_object_without_properties(self):
        """Gemini refuses an object schema with no properties, at any depth."""
        def visit(schema):
            if isinstance(schema, dict):
                if schema.get('type') == 'object':
                    self.assertTrue(schema.get('properties'), schema)
                for value in schema.values():
                    visit(value)
            elif isinstance(schema, list):
                for value in schema:
                    visit(value)

        for tool in assistant.TOOLS:
            visit(tool['function'].get('parameters'))


class ProvidersTest(unittest.TestCase):
    def test_gemini_and_openrouter_need_only_a_key(self):
        for provider in ('gemini', 'openrouter'):
            config = assistant.AgentConfig(provider, 'k')
            self.assertTrue(config.enabled, provider)
            url, headers, body = assistant.completion_request(config, [], assistant.TOOLS)
            self.assertTrue(url.startswith('https://') and url.endswith('/chat/completions'), url)
            self.assertEqual('Bearer k', headers['Authorization'])
            self.assertTrue(body['model'])
        self.assertIn('generativelanguage.googleapis.com', assistant.completion_request(assistant.AgentConfig('gemini', 'k'), [], [])[0])
        self.assertIn('openrouter.ai', assistant.completion_request(assistant.AgentConfig('openrouter', 'k'), [], [])[0])

    def test_off_without_a_key_or_with_an_unknown_provider(self):
        self.assertFalse(assistant.AgentConfig('gemini', '').enabled)
        self.assertFalse(assistant.AgentConfig('nope', 'k').enabled)
        self.assertFalse(assistant.AgentConfig('openai_compatible', 'k').enabled)  # no URL or model
        custom = assistant.AgentConfig('openai_compatible', 'k', 'llama', 'https://llm.example/v1/')
        self.assertTrue(custom.enabled)
        self.assertEqual('https://llm.example/v1/chat/completions', assistant.completion_request(custom, [], [])[0])

    def test_info_never_carries_the_key(self):
        info = assistant.AgentConfig('openrouter', 'secret-key', 'google/gemini-2.5-pro').info()
        self.assertEqual({'enabled': True, 'provider': 'openrouter', 'provider_label': 'OpenRouter',
                          'model': 'google/gemini-2.5-pro'}, info)
        self.assertNotIn('secret-key', json.dumps(info))
        self.assertFalse(assistant.AgentConfig(None, None).info()['enabled'])

    def test_completion_errors_say_what_to_do(self):
        with self.assertRaisesRegex(assistant.ModelError, 'key was refused'):
            assistant.completion_message(401, {})
        with self.assertRaisesRegex(assistant.ModelError, 'rate limiting'):
            assistant.completion_message(429, {})
        with self.assertRaisesRegex(assistant.ModelError, 'answered 400: bad model'):
            assistant.completion_message(400, {'error': {'message': 'bad model'}})
        with self.assertRaisesRegex(assistant.ModelError, 'no reply'):
            assistant.completion_message(200, {'choices': []})
        self.assertEqual({'content': 'hi'}, assistant.completion_message(200, {'choices': [{'message': {'content': 'hi'}}]}))


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


if __name__ == '__main__':
    unittest.main()
