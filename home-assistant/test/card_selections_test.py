"""Per-user selections, independent of a Home Assistant runtime."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    "terrazzo_card_selections",
    Path(__file__).resolve().parents[2] / "custom_components/terrazzo/card_selections.py",
)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class CardSelectionsTest(unittest.TestCase):
    def test_users_are_isolated_and_cannot_mutate_stored_choices(self):
        choices = module.CardSelections()
        card = "a" * 64
        choices.set("alice", card, "user:garage")
        choices.set("bob", card, "user:other")
        choices.for_user("alice")[card] = "tampered"
        self.assertEqual({card: "user:garage"}, choices.for_user("alice"))
        self.assertEqual({card: "user:other"}, choices.for_user("bob"))
        choices.set("alice", card, None)
        self.assertEqual({}, choices.for_user("alice"))
        self.assertEqual({card: "user:other"}, choices.for_user("bob"))
        self.assertEqual({"bob": {card: "user:other"}}, module.CardSelections(choices.to_data()).to_data())

    def test_invalid_inputs_and_corrupt_persisted_entries_are_ignored(self):
        card = "1" * 64
        choices = module.CardSelections({"alice": {card: "user:door", "bad": "x", "2" * 64: ""}})
        self.assertEqual({card: "user:door"}, choices.for_user("alice"))
        self.assertEqual({}, module.CardSelections([]).to_data())
        for fingerprint, design in (("bad", "id"), (card, ""), (card, "x\n"), (card, "x" * 257)):
            with self.assertRaises(ValueError):
                choices.set("alice", fingerprint, design)
        self.assertEqual({card: "user:door"}, choices.for_user("alice"))

    def test_card_limit_allows_replacing_and_removing_existing_choices(self):
        choices = module.CardSelections()
        for number in range(module.MAX_CARDS):
            choices.set("alice", f"{number:064x}", "user:door")
        with self.assertRaises(ValueError):
            choices.set("alice", f"{module.MAX_CARDS:064x}", "user:door")
        choices.set("alice", f"{0:064x}", "user:other")
        choices.set("alice", f"{1:064x}", None)
        choices.set("alice", f"{module.MAX_CARDS:064x}", "user:door")
        self.assertEqual(module.MAX_CARDS, len(choices.for_user("alice")))


if __name__ == "__main__":
    unittest.main()
