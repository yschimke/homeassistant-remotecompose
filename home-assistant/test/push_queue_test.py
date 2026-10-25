"""Held notifications' rules, driven with plain data (push_queue.py imports nothing from HA)."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    'terrazzo_push_queue',
    Path(__file__).resolve().parents[2] / 'custom_components/terrazzo/push_queue.py')
pq = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pq)

TOKEN = 'f' * 40
RELAY = 'https://relay.test/haPush'
HA_BODY = {
    'message': 'Washer done', 'title': 'Laundry', 'data': {'tag': 'washer'},
    'push_token': 'the-secret', 'registration_info': {'app_id': 'ee.schimke.harc', 'webhook_id': 'hook'},
}


class PushQueueTest(unittest.TestCase):
    def setUp(self):
        self.queue = pq.PushQueue()
        self.secret = self.queue.register('hook', 'person', TOKEN, RELAY)

    def test_only_the_devices_secret_is_accepted(self):
        self.assertTrue(self.queue.accepts('hook', self.secret))
        self.assertFalse(self.queue.accepts('hook', 'guess'))
        self.assertFalse(self.queue.accepts('hook', None))
        self.assertFalse(self.queue.accepts('other', self.secret))

    def test_a_new_token_keeps_the_secret_so_ha_needs_no_update(self):
        self.assertEqual(self.secret, self.queue.register('hook', 'person', 'e' * 40, RELAY))
        self.assertEqual('e' * 40, self.queue.hold('hook', HA_BODY, 0)[0])

    def test_another_user_registering_the_webhook_gets_a_fresh_device(self):
        self.queue.hold('hook', HA_BODY, 0)
        self.assertNotEqual(self.secret, self.queue.register('hook', 'intruder', TOKEN, RELAY))
        self.assertEqual([], self.queue.take('hook', 'intruder', 1))

    def test_held_messages_keep_only_the_content_and_are_taken_once(self):
        self.assertEqual((TOKEN, RELAY), self.queue.hold('hook', HA_BODY, 0))
        self.queue.hold('hook', {'message': 'Second'}, 1)
        self.assertEqual(
            [{'message': 'Washer done', 'title': 'Laundry', 'data': {'tag': 'washer'}},
             {'message': 'Second'}],
            self.queue.take('hook', 'person', 2))
        self.assertEqual([], self.queue.take('hook', 'person', 3))

    def test_only_the_devices_user_takes_its_messages(self):
        self.queue.hold('hook', HA_BODY, 0)
        self.assertEqual([], self.queue.take('hook', 'someone-else', 1))
        self.assertEqual(1, len(self.queue.take('hook', 'person', 1)))

    def test_old_and_excess_messages_go(self):
        for i in range(pq.MAX_HELD + 5):
            self.queue.hold('hook', {'message': str(i)}, i)
        taken = self.queue.take('hook', 'person', pq.MAX_HELD + 5)
        self.assertEqual(pq.MAX_HELD, len(taken))
        self.assertEqual('5', taken[0]['message'])
        self.queue.hold('hook', {'message': 'stale'}, 0)
        self.assertEqual([], self.queue.take('hook', 'person', pq.HOLD_SECONDS + 1))

    def test_it_survives_a_restart(self):
        self.queue.hold('hook', HA_BODY, 0)
        restored = pq.PushQueue(self.queue.to_data())
        self.assertTrue(restored.accepts('hook', self.secret))
        self.assertEqual('Washer done', restored.take('hook', 'person', 1)[0]['message'])


if __name__ == '__main__':
    unittest.main()
