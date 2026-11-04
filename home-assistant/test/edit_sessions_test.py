"""Edit sessions' rules, driven with plain data; the installed test runs them inside Home Assistant."""
import importlib.util
import json
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

    def test_relay_retries_do_not_duplicate_or_replace_a_draft(self):
        first = self.sessions.submit(self.session['id'], DRAFT, 'Soil', exists, 'person', 110, 'cloud:1')
        retry = self.sessions.submit(self.session['id'], DRAFT, 'Soil', exists, 'person', 111, 'cloud:1')
        self.assertEqual(first['id'], retry['id'])
        self.assertEqual(len(self.session['drafts']), 1)
        # The app labels its render of a relayed draft with the relay's revision.
        self.assertEqual(es.summary(self.session)['drafts'][0]['relay_id'], 'cloud:1')
        with self.assertRaisesRegex(es.SessionError, 'another draft'):
            self.sessions.submit(self.session['id'], BASELINE, 'Other', exists, 'person', 112, 'cloud:1')

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

    def test_discovery_is_limited_to_visible_open_unexpired_sessions(self):
        overview = self.sessions.create(None, 'Overview', BASELINE, 'person', 101)
        hidden = self.sessions.create('private', 'Private', BASELINE, 'person', 102)
        discarded = self.sessions.create('garden', 'Discarded', BASELINE, 'person', 103)
        self.sessions.discard(discarded['id'], 'person', 104)
        self.sessions.note(self.session['id'], 'A note', 'person', 105)
        found = self.sessions.for_dashboards({None, 'garden'}, 106)
        self.assertEqual([s['id'] for s in found], [self.session['id'], overview['id']])
        self.assertEqual(es.summary(found[0])['notes'][0]['text'], 'A note')
        self.assertNotIn(hidden['id'], [s['id'] for s in found])
        self.assertEqual(self.sessions.for_dashboards({None, 'garden'}, 106 + es.SESSION_TTL_SECONDS), [])

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



class ConversationTest(unittest.TestCase):
    def test_notes_left_before_an_agent_joins_are_its_first_work(self):
        sessions = es.EditSessions()
        sid = sessions.create(None, 'Work on it with me', BASELINE, 'person', 0)['id']
        self.assertRegex(es.next_step(sessions.get(sid, 0)), '^Say hello')
        # The person comments on a card before any agent has read the session.
        sessions.note(sid, 'Bigger camera', 'person', 1, target='Front door (card 2)')
        session = sessions.get(sid, 1)
        self.assertEqual([n['text'] for n in es.waiting_notes(session)], ['Bigger camera'])
        self.assertIn('already left notes', es.next_step(session))
        # A draft answers them; a note after it is waiting again.
        sessions.submit(sid, DRAFT, 'Bigger camera', exists, 'agent', 2)
        self.assertRegex(es.next_step(sessions.get(sid, 2)), '^await_feedback')
        sessions.note(sid, 'Now the doorbell', 'person', 3)
        self.assertIn('already left notes', es.next_step(sessions.get(sid, 3)))

    def test_notes_and_messages_share_a_cursor_and_notes_name_cards(self):
        sessions = es.EditSessions()
        session = sessions.create(None, 'Garden', BASELINE, 'person', 0)
        sid = session['id']
        sessions.note(sid, 'Bigger camera', 'person', 1, target='Front door (card 2)')
        sessions.agent_message(sid, 'Full width?', 'agent', 2)
        sessions.note(sid, 'Yes', 'person', 3)
        notes, cursor = es.EditSessions.feedback(sessions.get(sid, 3), 0)
        self.assertEqual([n['text'] for n in notes], ['Bigger camera', 'Yes'])
        self.assertEqual(notes[0]['target'], 'Front door (card 2)')
        self.assertNotIn('target', notes[1])
        self.assertEqual(cursor, 3)
        # After the agent read up to the first note, only the newer one is feedback.
        self.assertEqual([n['text'] for n in es.EditSessions.feedback(sessions.get(sid, 3), 1)[0]], ['Yes'])
        result = es.summary(sessions.get(sid, 3))
        self.assertEqual([m['text'] for m in result['messages']], ['Full width?'])
        self.assertEqual(result['seq'], 3)

    def test_presence_keeps_its_status_until_changed(self):
        sessions = es.EditSessions()
        sid = sessions.create(None, 'Garden', BASELINE, 'person', 0)['id']
        sessions.agent_seen(sid, 'Drafting', 'agent', 5)
        sessions.agent_seen(sid, None, 'agent', 9)
        agent = es.summary(sessions.get(sid, 9))['agent']
        self.assertEqual((agent['status'], agent['seen_at']), ('Drafting', 9))

    def test_sessions_saved_before_the_conversation_still_load(self):
        old = es.EditSessions().create(None, 'Garden', BASELINE, 'person', 0)
        for key in ('seq', 'messages', 'agent'):
            old.pop(key)
        old['notes'] = [{'text': 'Old note', 'by': 'person', 'at': 0}]
        sessions = es.EditSessions([old])
        notes, cursor = es.EditSessions.feedback(sessions.get(old['id'], 1), 0)
        self.assertEqual((notes, cursor), ([], 0))
        sessions.note(old['id'], 'New', 'person', 1)
        self.assertEqual(es.EditSessions.feedback(sessions.get(old['id'], 1), 0)[0][0]['text'], 'New')
        self.assertEqual(es.summary(sessions.get(old['id'], 1))['messages'], [])

    def test_a_section_session_takes_changes_to_its_section_only(self):
        two = {'title': 'Garden', 'views': [{'type': 'sections', 'sections': [
            {'type': 'grid', 'cards': [{'type': 'tile', 'entity': 'light.kitchen'}]},
            {'type': 'grid', 'cards': [{'type': 'tile', 'entity': 'sensor.rain'}]}]}]}
        sessions = es.EditSessions()
        session = sessions.create('garden', '', two, 'person', 100,
                                  {'kind': 'section', 'view': 0, 'section': 1, 'label': 'Rain'})
        self.assertEqual(es.summary(session)['scope'],
                         {'kind': 'section', 'view': 0, 'section': 1, 'label': 'Rain'})
        inside = es.copy.deepcopy(two)
        inside['views'][0]['sections'][1]['cards'].append({'type': 'gauge', 'entity': 'sensor.soil'})
        sessions.submit(session['id'], inside, 'Soil beside rain', exists, 'agent', 110)
        outside = es.copy.deepcopy(two)
        outside['views'][0]['sections'][0]['cards'] = []
        with self.assertRaisesRegex(es.SessionError, 'only that section'):
            sessions.submit(session['id'], outside, 'Emptied the lights', exists, 'agent', 111)
        with self.assertRaisesRegex(es.SessionError, 'no such section'):
            sessions.create('garden', '', two, 'person', 100, {'kind': 'section', 'view': 0, 'section': 5})
        self.assertIn('Scope: only Rain', es.agent_prompt(session, 'http://ha/mcp', 'Home'))

    def test_recent_sessions_include_applied_and_discarded_ones(self):
        sessions = es.EditSessions()
        session = sessions.create('garden', 'Soil', BASELINE, 'person', 100)
        sessions.discard(session['id'], 'person', 105)
        self.assertEqual(sessions.for_dashboard('garden', 106), [])
        self.assertEqual([s['id'] for s in sessions.for_dashboard('garden', 106, include_closed=True)],
                         [session['id']])


class CloudLinkTest(unittest.TestCase):
    def setUp(self):
        self.sessions = es.EditSessions()
        self.session = self.sessions.create('garden', 'Add soil', BASELINE, 'person', 100)

    def test_the_starter_links_a_cloud_request_and_only_they_see_its_capability(self):
        self.sessions.link_cloud(self.session['id'], 'https://ha.coo.ee/', 'owner-cap', 'person', 101)
        self.assertEqual(es.cloud_for(self.session, 'person'),
                         {'server': 'https://ha.coo.ee', 'owner': 'owner-cap'})
        self.assertIsNone(es.cloud_for(self.session, 'someone else'))
        # What an agent reads never carries it.
        self.assertNotIn('owner-cap', json.dumps(es.summary(self.session, with_configs=True)))
        self.assertEqual([s['id'] for s in self.sessions.cloud_linked(102)], [self.session['id']])

    def test_someone_else_cannot_link_a_session(self):
        with self.assertRaises(es.SessionError):
            self.sessions.link_cloud(self.session['id'], 'https://ha.coo.ee', 'cap', 'other', 101)

    def test_only_a_plain_https_url_is_a_design_server(self):
        for bad in ['http://ha.coo.ee', 'https://ha.coo.ee#x', 'https://u:p@ha.coo.ee',
                    'https://ha.coo.ee?x=1', 'ftp://ha.coo.ee', 'ha.coo.ee', 'https://', 7]:
            with self.subTest(bad=bad), self.assertRaises(es.SessionError):
                es.clean_cloud_server(bad)
        self.assertEqual(es.clean_cloud_server('https://ha.coo.ee:8443'), 'https://ha.coo.ee:8443')
        self.assertEqual(es.clean_cloud_server('http://localhost:8080'), 'http://localhost:8080')
        self.assertEqual(es.clean_cloud_server('https://example.com/studio/'),
                         'https://example.com/studio')
        with self.assertRaises(es.SessionError):
            self.sessions.link_cloud(self.session['id'], 'https://ha.coo.ee', 'a b', 'person', 101)

    def test_relayed_revisions_only_move_forward_and_relinking_keeps_them(self):
        self.sessions.link_cloud(self.session['id'], 'https://ha.coo.ee', 'cap', 'person', 101)
        self.sessions.cloud_relayed(self.session['id'], 3, 102)
        self.sessions.cloud_relayed(self.session['id'], 2, 103)
        self.assertEqual(self.session['cloud']['revision'], 3)
        self.sessions.link_cloud(self.session['id'], 'https://ha.coo.ee', 'cap', 'person', 104)
        self.assertEqual(self.session['cloud']['revision'], 3)

    def test_a_closed_or_unlinked_session_is_not_relayed(self):
        self.sessions.link_cloud(self.session['id'], 'https://ha.coo.ee', 'cap', 'person', 101)
        self.sessions.unlink_cloud(self.session['id'], 102)
        self.assertEqual(self.sessions.cloud_linked(103), [])
        self.sessions.link_cloud(self.session['id'], 'https://ha.coo.ee', 'cap', 'person', 104)
        self.sessions.discard(self.session['id'], 'person', 105)
        self.assertEqual(self.sessions.cloud_linked(106), [])


class DraftNoticeTest(unittest.TestCase):
    def test_only_the_starters_terrazzo_devices_hear_of_a_draft(self):
        slug = lambda name: name.lower().replace(' ', '_')
        registrations = [
            {'app_id': 'ee.schimke.harc', 'user_id': 'person', 'device_name': 'Pixel 9'},
            {'app_id': 'ee.schimke.harc', 'user_id': 'person', 'device_name': 'Tablet'},
            {'app_id': 'io.homeassistant.companion.android', 'user_id': 'person', 'device_name': 'Phone'},
            {'app_id': 'ee.schimke.harc', 'user_id': 'someone else', 'device_name': 'Theirs'},
            {'app_id': 'ee.schimke.harc', 'user_id': 'person'},
        ]
        self.assertEqual(es.notice_services(registrations, 'person', slug),
                         ['mobile_app_pixel_9', 'mobile_app_tablet'])

    def test_the_notice_says_what_changed_and_replaces_the_last_one(self):
        session = es.EditSessions().create(None, 'Garden', BASELINE, 'person', 0)
        notice = es.draft_notice(session, {'summary': 'Soil moisture section'})
        self.assertEqual(notice['message'], 'Soil moisture section')
        self.assertEqual(notice['data']['tag'], f"terrazzo-edit-{session['id']}")
        self.assertEqual(es.draft_notice(session, {'summary': ''})['message'], 'Garden')


if __name__ == '__main__':
    unittest.main()
