"""Render the actual HA templates through a long seeded simulation, including user changes."""
from datetime import datetime, timezone
from pathlib import Path
import random
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from jinja2 import Environment, StrictUndefined
import yaml


class SimulationTest(unittest.TestCase):
    def setUp(self):
        self.config = yaml.safe_load(Path(__file__).with_name('configuration.yaml').read_text())
        self.block = self.config['template'][0]
        self.env = Environment(undefined=StrictUndefined)
        self.templates = {s["unique_id"]: self.env.from_string(s["state"]) for s in self.block["sensor"]}
        self.values = {}
        self.target = 22
        self.env.globals.update(
            is_state=lambda entity, value: self.values.get(entity) == value,
            states=lambda entity: self.values.get(entity, 'unknown'),
            state_attr=lambda entity, attr: self.target,
            now=lambda: datetime(2026, 10, 18, 12, 0, 0, tzinfo=timezone.utc))

    def render(self, name):
        entity = 'sensor.' + name
        result = float(self.templates[name].render(
            this=SimpleNamespace(state=self.values.get(entity, 'unknown'))))
        self.values[entity] = str(result)
        return result

    def test_single_bounded_cadence_and_no_actions_or_helper_exposure(self):
        self.assertEqual(self.block['triggers'], [
            {'trigger': 'homeassistant', 'event': 'start'},
            {'trigger': 'time_pattern', 'seconds': '/30'}])
        self.assertNotIn('automation', self.config)
        self.assertNotIn('script', self.config)
        self.assertNotIn('light', self.block)
        self.assertNotIn('switch', self.block)
        for row in self.config['template'][1:]:
            self.assertTrue(set(row) <= {'light', 'switch'})

    def test_random_walk_stays_bounded_and_retains_controls_over_many_days(self):
        bounds = {'demo_temperature': (16, 28, .201), 'demo_humidity': (38, 55, 1),
                  'demo_solar': (600, 2400, 80), 'demo_battery': (45, 95, .101)}
        seen = {key: set() for key in bounds}
        with patch('jinja2.filters.random', random.Random(42)):
            for tick in range(6000):
                # Public interactions change switches/setpoint, including both bounds.
                self.values['light.demo_lamp'] = 'on' if tick % 2 else 'off'
                self.values['switch.demo_coffee'] = 'on' if tick % 3 else 'off'
                self.values['input_boolean.demo_heating_state'] = 'on' if tick % 5 else 'off'
                self.target = 16 if tick % 7 else 28
                for name, (low, high, step) in bounds.items():
                    before = float(self.values.get('sensor.' + name, 'nan'))
                    value = self.render(name)
                    self.assertTrue(low <= value <= high, (name, value))
                    if before == before:
                        self.assertLessEqual(abs(value - before), step, name)
                    seen[name].add(value)
                power = self.render('demo_power')
                base = 160 + (12 if tick % 2 else 0) + (850 if tick % 3 else 0) + (600 if tick % 5 else 0)
                self.assertTrue(base - 20 <= power <= base + 20)
                self.assertEqual(self.values['switch.demo_coffee'], 'on' if tick % 3 else 'off')
        self.assertTrue(all(len(values) > 3 for values in seen.values()))

    def test_heating_and_appliance_values_follow_user_interactions(self):
        self.values['sensor.demo_temperature'] = '21'
        self.values['input_boolean.demo_heating_state'] = 'on'
        with patch('jinja2.filters.random.choice', return_value=0):
            self.target = 25
            self.assertEqual(self.render('demo_temperature'), 21.1)
            self.target = 16
            self.assertEqual(self.render('demo_temperature'), 21.0)
            off = self.render('demo_power')
            self.values['switch.demo_coffee'] = 'on'
            self.assertEqual(self.render('demo_power') - off, 850)
            self.values['light.demo_lamp'] = 'on'
            self.assertEqual(self.render('demo_power') - off, 862)
        motion = self.env.from_string(self.block['binary_sensor'][0]['state'])
        self.env.globals['now'] = lambda: datetime(2026, 10, 18, 12, 1, 30)
        self.assertEqual(motion.render(this=SimpleNamespace(state='on')).strip(), 'True')
        self.assertEqual(motion.render(this=SimpleNamespace(state='off')).strip(), 'False')
