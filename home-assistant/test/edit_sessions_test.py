"""Edit sessions' rules, driven with plain data; the installed test runs them inside Home Assistant."""
import importlib.util
from pathlib import Path
import unittest

# edit_sessions.py imports nothing from Home Assistant, so it loads on its own outside the package.
spec = importlib.util.spec_from_file_location(
    'terrazzo_edit_sessions',
    Path(__file__).resolve().parents[2] / 'custom_components/terrazzo/edit_sessions.py')
es = importlib.util.module_from_spec(spec)
spec.loader.exec_module(es)

KNOWN = {'light.kitchen', 'sensor.soil', 'sensor.rain'}
exists = KNOWN.__contains__

BASELINE = {'title': 'Garden', 'views': [{'type': 'sections', 'sections': [
    {'type': 'grid', 'cards': [{'type': 'tile', 'entity': 'light.kitchen'}]}]}]}
DRAFT = {'title': 'Garden', 'views': [{'type': 'sections', 'sections': [
    {'type': 'grid', 'cards': [
        {'type': 'heading', 'heading': 'Soil'},
        {'type': 'gauge', 'entity': 'sensor.soil'},
        {'type': 'history-graph', 'entities': ['sensor.rain', {'entity': 'sensor.soil'}]}]}]}]}


class EditSessionsTest(unittest.TestCase):
    def setUp(self):
        self.sessions = es.EditSessions()
        self.session = self.sessions.create('garden', 'Add soil moisture', BASELINE, 'person', 100)

    def test_a_session_keeps_its_own_copy_of_the_baseline(self):
        baseline = {'views': []}
        session = self.sessions.create(None, None, baseline, 'agent', 100)
        baseline['views'].append({})
        self.assertEqual(session['baseline'], {'views': []})
        self.assertEqual(session['status'], es.OPEN)
        self.assertEqual(session['request'], '')

    def test_drafts_are_validated_against_the_home(self):
        draft = self.sessions.submit(self.session['id'], DRAFT, 'Soil section', exists, 'agent', 110)
        self.assertEqual(draft['summary'], 'Soil section')
        self.assertEqual(self.sessions.get(self.session['id'], 110)['drafts'][-1]['config'], DRAFT)
        invented = {'views': [{'cards': [{'type': 'tile', 'entity': 'sensor.made_up'}]}]}
        with self.assertRaisesRegex(es.SessionError, 'sensor.made_up'):
            self.sessions.submit(self.session['id'], invented, '', exists, 'agent', 111)
        for bad in ([], {'views': 'no'}, {'views': ['no']}, {'title': 'no views'}):
            with self.assertRaises(es.SessionError):
                self.sessions.submit(self.session['id'], bad, '', exists, 'agent', 112)

    def test_referenced_entities_reads_entity_and_entities_at_any_depth(self):
        self.assertEqual(es.referenced_entities(DRAFT), {'sensor.soil', 'sensor.rain'})

    def test_only_the_newest_drafts_are_kept(self):
        for i in range(es.MAX_DRAFTS + 3):
            self.sessions.submit(self.session['id'], BASELINE, f'd{i}', exists, 'agent', 100 + i)
        drafts = self.sessions.get(self.session['id'], 200)['drafts']
        self.assertEqual(len(drafts), es.MAX_DRAFTS)
        self.assertEqual(drafts[-1]['summary'], f'd{es.MAX_DRAFTS + 2}')

    def test_apply_needs_the_dashboard_unchanged_and_undo_needs_the_draft_still_there(self):
        draft = self.sessions.submit(self.session['id'], DRAFT, '', exists, 'agent', 110)
        changed = {'views': [{'cards': []}]}
        with self.assertRaises(es.Conflict):
            self.sessions.check_apply(self.session['id'], draft['id'], changed, 120)
        with self.assertRaises(es.SessionError):
            self.sessions.check_apply(self.session['id'], 'nope', BASELINE, 120)
        self.assertEqual(self.sessions.check_apply(self.session['id'], draft['id'], BASELINE, 120), DRAFT)
        self.sessions.applied(self.session['id'], draft['id'], 'admin', 121)

        # Applied: no more drafts or a second apply, and undo only while the draft is in place.
        with self.assertRaises(es.SessionError):
            self.sessions.submit(self.session['id'], DRAFT, '', exists, 'agent', 122)
        with self.assertRaises(es.Conflict):
            self.sessions.check_undo(self.session['id'], changed, 123)
        self.assertEqual(self.sessions.check_undo(self.session['id'], DRAFT, 123), BASELINE)
        reopened = self.sessions.undone(self.session['id'], 124)
        self.assertEqual(reopened['status'], es.OPEN)
        self.assertIsNone(reopened['applied'])
        with self.assertRaises(es.SessionError):
            self.sessions.check_undo(self.session['id'], BASELINE, 125)

    def test_notes_and_discard(self):
        self.sessions.note(self.session['id'], '  Make the gauges bigger ', 'person', 110)
        self.assertEqual(self.sessions.get(self.session['id'], 110)['notes'][0]['text'], 'Make the gauges bigger')
        with self.assertRaises(es.SessionError):
            self.sessions.note(self.session['id'], '   ', 'person', 111)
        self.sessions.discard(self.session['id'], 'person', 112)
        with self.assertRaises(es.SessionError):
            self.sessions.note(self.session['id'], 'too late', 'person', 113)
        self.assertEqual(self.sessions.for_dashboard('garden', 114), [])

    def test_sessions_expire_a_day_after_their_last_change(self):
        later = 100 + es.SESSION_TTL_SECONDS - 1
        self.sessions.note(self.session['id'], 'still here', 'person', later)
        self.assertEqual(self.sessions.get(self.session['id'], later + 10)['status'], es.OPEN)
        with self.assertRaisesRegex(es.SessionError, 'expired'):
            self.sessions.get(self.session['id'], later + es.SESSION_TTL_SECONDS + 1)

    def test_storage_round_trip_and_cap(self):
        restored = es.EditSessions(self.sessions.to_data())
        self.assertEqual(restored.get(self.session['id'], 101)['request'], 'Add soil moisture')
        for i in range(es.MAX_SESSIONS + 5):
            restored.create('garden', f'r{i}', BASELINE, 'person', 200 + i)
        self.assertEqual(len(restored.to_data()), es.MAX_SESSIONS)

    def test_summary_leaves_configs_out_unless_asked(self):
        self.sessions.submit(self.session['id'], DRAFT, 'Soil', exists, 'agent', 110)
        session = self.sessions.get(self.session['id'], 110)
        brief = es.summary(session)
        self.assertNotIn('baseline', brief)
        self.assertNotIn('config', brief['drafts'][0])
        full = es.summary(session, with_configs=True)
        self.assertEqual(full['baseline'], BASELINE)
        self.assertEqual(full['drafts'][0]['config'], DRAFT)

    def test_the_prompt_names_the_session_and_endpoint_but_carries_no_token_or_config(self):
        prompt = es.agent_prompt(self.session, 'https://ha.example/api/terrazzo/mcp', 'Home')
        self.assertIn(self.session['id'], prompt)
        self.assertIn('https://ha.example/api/terrazzo/mcp', prompt)
        self.assertIn('Add soil moisture', prompt)
        self.assertNotIn('light.kitchen', prompt)
        self.assertNotIn('Bearer', prompt)


if __name__ == '__main__':
    unittest.main()
