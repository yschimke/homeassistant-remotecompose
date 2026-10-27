"""State wakes' throttle, lease and budget, driven with plain data (state_wakes.py imports nothing
from HA)."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    'terrazzo_state_wakes',
    Path(__file__).resolve().parents[2] / 'custom_components/terrazzo/state_wakes.py')
sw = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sw)

T0 = 1_000_000.0


class StateWakesTest(unittest.TestCase):
    def setUp(self):
        self.wakes = sw.StateWakes()
        self.wakes.watch('hook', ['light.porch', 'cover.garage', 'sensor.power'], 0, T0)

    def test_only_watched_entities_wake(self):
        self.assertEqual([], self.wakes.changed('light.kitchen', 'off', 'on', T0))
        self.assertIsNone(self.wakes.next_due(T0))

    def test_only_a_visible_edge_wakes(self):
        # Attributes alone, or a number ticking: nothing a glance would catch.
        self.assertEqual([], self.wakes.changed('light.porch', 'on', 'on', T0))
        self.assertEqual([], self.wakes.changed('sensor.power', '120.5', '121', T0))
        self.assertIsNone(self.wakes.next_due(T0))
        # A sensor going unavailable is visible.
        self.assertEqual([('hook', False)], self.wakes.changed('sensor.power', '121', 'unavailable', T0))

    def test_edges(self):
        self.assertEqual(sw.URGENT, sw.edge('cover.garage', 'closed', 'opening'))
        self.assertEqual(sw.URGENT, sw.edge('cover.garage', 'opening', 'open'))
        self.assertEqual(sw.VISIBLE, sw.edge('cover.garage', 'closed', 'open'))
        self.assertEqual(sw.VISIBLE, sw.edge('person.me', 'home', 'not_home'))
        self.assertEqual(sw.QUIET, sw.edge('cover.garage', 'opening', 'opening'))
        self.assertEqual(sw.QUIET, sw.edge('sensor.temp', '20.5', '20.6'))

    def test_the_first_change_wakes_at_once_and_later_ones_fold_into_one(self):
        self.assertEqual([('hook', False)], self.wakes.changed('light.porch', 'off', 'on', T0))
        self.assertEqual([], self.wakes.changed('light.porch', 'on', 'off', T0 + 1))
        self.assertEqual([], self.wakes.changed('light.porch', 'off', 'on', T0 + 2))
        self.assertEqual(T0 + sw.INTERVAL, self.wakes.next_due(T0 + 2))
        self.assertEqual([], self.wakes.due(T0 + sw.INTERVAL - 1))
        self.assertEqual([('hook', False)], self.wakes.due(T0 + sw.INTERVAL))
        self.assertIsNone(self.wakes.next_due(T0 + sw.INTERVAL))

    def test_a_movement_wakes_urgently_and_soon(self):
        self.wakes.changed('light.porch', 'off', 'on', T0)
        self.assertEqual([], self.wakes.changed('cover.garage', 'closed', 'opening', T0 + 1))
        self.assertEqual(T0 + sw.URGENT_INTERVAL, self.wakes.next_due(T0 + 1))
        self.assertEqual([('hook', True)], self.wakes.due(T0 + sw.URGENT_INTERVAL))
        # Its end is urgent too: the door has finished opening.
        self.assertEqual(
            [('hook', True)],
            self.wakes.changed('cover.garage', 'opening', 'open', T0 + 2 * sw.URGENT_INTERVAL))

    def test_a_lease_holds_wakes_until_it_runs_out(self):
        self.wakes.watch('hook', ['light.porch', 'cover.garage'], 60, T0)
        self.assertEqual([], self.wakes.changed('cover.garage', 'closed', 'opening', T0 + 1))
        self.assertEqual(T0 + 60, self.wakes.next_due(T0 + 1))
        # Renewed: the app saw that change itself.
        self.wakes.watch('hook', ['light.porch', 'cover.garage'], 60, T0 + 30)
        self.assertIsNone(self.wakes.next_due(T0 + 30))
        # A change after the last renewal, and no renewal after it: woken when the lease ends.
        self.wakes.changed('cover.garage', 'opening', 'open', T0 + 40)
        self.assertEqual([], self.wakes.due(T0 + 89))
        self.assertEqual([('hook', True)], self.wakes.due(T0 + 90))

    def test_releasing_the_lease_lets_changes_wake_again(self):
        self.wakes.watch('hook', ['light.porch'], 60, T0)
        self.wakes.watch('hook', ['light.porch'], 0, T0 + 5)
        self.assertEqual([('hook', False)], self.wakes.changed('light.porch', 'off', 'on', T0 + 6))

    def test_a_lease_is_capped(self):
        self.assertEqual(T0 + sw.MAX_LEASE, self.wakes.watch('hook', ['sensor.power'], 10_000, T0))

    def test_a_days_wakes_are_capped(self):
        now = T0
        for _ in range(sw.DAILY_WAKES):
            self.assertEqual(1, len(self.wakes.changed('cover.garage', 'closed', 'opening', now)))
            now += sw.URGENT_INTERVAL
        self.assertEqual([], self.wakes.changed('cover.garage', 'opening', 'open', now))
        self.assertEqual(T0 + sw.DAY, self.wakes.next_due(now))
        self.assertEqual([('hook', True)], self.wakes.due(T0 + sw.DAY))

    def test_each_device_watches_its_own_entities(self):
        self.wakes.watch('other', ['sensor.power'], 0, T0)
        self.assertEqual({'light.porch', 'sensor.power', 'cover.garage'}, self.wakes.watched())
        self.assertEqual(
            [('hook', True)], self.wakes.changed('cover.garage', 'closed', 'opening', T0))
        self.wakes.forget('hook')
        self.assertEqual({'sensor.power'}, self.wakes.watched())

    def test_moving_states_are_per_domain(self):
        self.assertTrue(sw.is_moving('cover.garage', 'opening'))
        self.assertTrue(sw.is_moving('lock.front', 'unlocking'))
        self.assertFalse(sw.is_moving('sensor.door', 'opening'))
        self.assertFalse(sw.is_moving('cover.garage', None))


if __name__ == '__main__':
    unittest.main()
