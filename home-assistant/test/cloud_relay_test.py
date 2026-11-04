"""Home Assistant's cloud relay loop, driven with plain functions instead of HTTP and a home."""
import asyncio
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    'terrazzo_cloud_relay',
    Path(__file__).resolve().parents[2] / 'custom_components/terrazzo/cloud_relay.py')
relay = importlib.util.module_from_spec(spec)
spec.loader.exec_module(relay)

SERVER = 'https://ha.coo.ee'
DASHBOARD = {'views': [{'title': 'Garage'}]}


class Fake:
    """A design server answering a scripted sequence of states, and a session that records drafts."""

    def __init__(self, states, drafts=None):
        self.states = list(states)
        self.drafts = drafts or {}
        self.cloud = {'server': SERVER, 'owner': 'cap', 'revision': 0}
        self.submitted = []
        self.urls = []
        self.unlinked = False
        self.pauses = []

    def link(self):
        return None if self.unlinked else self.cloud

    async def get_json(self, url, owner):
        assert owner == 'cap'
        self.urls.append(url)
        if url.endswith(relay.DRAFT_PATH):
            return self.drafts[self.cloud_revision_wanted]
        if not self.states:
            raise relay.Ended()
        state = self.states.pop(0)
        if isinstance(state, Exception):
            raise state
        self.cloud_revision_wanted = state['revision']
        return state

    async def submit(self, config, summary, relay_id):
        if summary == 'refuse':
            raise ValueError('unknown entity')
        self.submitted.append((config, summary, relay_id))

    def relayed(self, revision):
        self.cloud['revision'] = max(self.cloud['revision'], revision)

    def ended(self):
        self.unlinked = True

    async def sleep(self, seconds):
        self.pauses.append(seconds)

    def run(self):
        asyncio.run(relay.relay(self.link, self.get_json, self.submit, self.relayed, self.ended,
                                sleep=self.sleep))


class CloudRelayTest(unittest.TestCase):
    def test_each_new_revision_is_submitted_once_with_its_relay_id(self):
        fake = Fake(
            [{'version': 1, 'revision': 0}, {'version': 2, 'revision': 1},
             {'version': 3, 'revision': 1}, {'version': 4, 'revision': 2}],
            {1: {'revision': 1, 'dashboard': DASHBOARD, 'summary': 'One'},
             2: {'revision': 2, 'dashboard': DASHBOARD, 'summary': 'Two'}})
        fake.run()
        self.assertEqual([s[2] for s in fake.submitted], ['cloud:1', 'cloud:2'])
        self.assertEqual(fake.cloud['revision'], 2)
        # Long-polls after the first answer quote its version.
        self.assertEqual(fake.urls[0], SERVER + relay.STATE_PATH)
        self.assertIn(SERVER + relay.STATE_PATH + '?since=1', fake.urls)
        # The design server ended the request: the link is forgotten.
        self.assertTrue(fake.unlinked)

    def test_a_revision_the_app_already_relayed_is_not_fetched(self):
        fake = Fake([{'version': 1, 'revision': 1}])
        fake.cloud['revision'] = 1
        fake.run()
        self.assertEqual(fake.submitted, [])
        self.assertNotIn(SERVER + relay.DRAFT_PATH, fake.urls)

    def test_a_refused_draft_is_skipped_not_retried(self):
        fake = Fake([{'version': 1, 'revision': 1}, {'version': 2, 'revision': 1}],
                    {1: {'revision': 1, 'dashboard': DASHBOARD, 'summary': 'refuse'}})
        fake.run()
        self.assertEqual(fake.submitted, [])
        self.assertEqual(fake.cloud['revision'], 1)
        self.assertEqual(fake.urls.count(SERVER + relay.DRAFT_PATH), 1)

    def test_failures_back_off_and_recover(self):
        fake = Fake([ConnectionError('down'), ConnectionError('down'),
                     {'version': 1, 'revision': 1}],
                    {1: {'revision': 1, 'dashboard': DASHBOARD, 'summary': 'One'}})
        fake.run()
        self.assertEqual(fake.pauses, [1, 2])
        self.assertEqual([s[2] for s in fake.submitted], ['cloud:1'])

    def test_a_closed_session_stops_the_relay(self):
        fake = Fake([{'version': 1, 'revision': 0}] * 3)
        fake.unlinked = True
        fake.run()
        self.assertEqual(fake.urls, [])


if __name__ == '__main__':
    unittest.main()
