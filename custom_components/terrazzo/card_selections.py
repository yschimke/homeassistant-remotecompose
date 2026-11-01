"""Per-user card design choices shared by a person's Terrazzo devices."""
import re

MAX_CARDS = 1000
MAX_DESIGN_ID = 256
FINGERPRINT = re.compile(r"[0-9a-f]{64}\Z")


class CardSelections:
    """A user's choices are independent of every other user's choices on this home."""

    def __init__(self, saved=None):
        self._users = {}
        for user, choices in (saved if isinstance(saved, dict) else {}).items():
            if isinstance(user, str) and isinstance(choices, dict):
                valid = {key: value for key, value in choices.items() if self.valid(key, value)}
                if valid:
                    self._users[user] = dict(list(valid.items())[:MAX_CARDS])

    @staticmethod
    def valid(fingerprint, design_id):
        return (isinstance(fingerprint, str) and FINGERPRINT.fullmatch(fingerprint) is not None
                and isinstance(design_id, str) and 0 < len(design_id) <= MAX_DESIGN_ID
                and not any(ord(char) < 32 for char in design_id))

    def for_user(self, user):
        return dict(self._users.get(user, {}))

    def set(self, user, fingerprint, design_id):
        if not isinstance(fingerprint, str) or not FINGERPRINT.fullmatch(fingerprint):
            raise ValueError("invalid card fingerprint")
        if design_id is not None and not self.valid(fingerprint, design_id):
            raise ValueError("invalid design id")
        choices = self.for_user(user)
        if design_id is None:
            choices.pop(fingerprint, None)
        else:
            if fingerprint not in choices and len(choices) >= MAX_CARDS:
                raise ValueError("too many card selections")
            choices[fingerprint] = design_id
        if choices:
            self._users[user] = choices
        else:
            self._users.pop(user, None)

    def to_data(self):
        return {user: dict(choices) for user, choices in self._users.items()}
