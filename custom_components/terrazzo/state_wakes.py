"""When to wake the Terrazzo app because an entity its widgets show changed
(docs/architecture/live-widget-updates.md).

The app tells the integration which entities its launcher and Wear widgets read
(`terrazzo/push/watch`). A change to one of them wakes the app through the push relay, with the same
content-free FCM message as a held notification, so it can redraw them. Wakes are throttled per
device: the first change after a quiet spell wakes at once, later ones are folded into one wake per
[INTERVAL], and a day's wakes are capped below the relay's own limit, which notifications share.

Only an edge a person would notice wakes it: an entity's state flipping (`off` to `on`, `home` to
`away`). An attribute-only change, or a number ticking (a power sensor), never wakes it; the app
reads those whenever it next wakes or connects. An entity starting or finishing a movement (a garage door `opening`, a lock `unlocking`) wakes
sooner and asks for a high-priority message: the app then stays connected while the movement lasts
and renews a lease here, and nothing wakes it while the lease holds. A change during a lease the app
fails to renew (its process was frozen or killed) is woken for when the lease runs out.

Plain data and no Home Assistant imports, so it is tested on its own (home-assistant/test).
"""

# The quickest routine wakes follow one another; changes in between are folded into the next.
INTERVAL = 5 * 60
# The quickest wakes follow one another when a movement starts or ends.
URGENT_INTERVAL = 10
# State wakes per device a day: the relay allows 500 per token, and notifications need some.
DAILY_WAKES = 400
DAY = 24 * 60 * 60
# The longest lease the app may take: it renews well inside this while it is connected.
MAX_LEASE = 180
# The most entities one device watches.
MAX_ENTITIES = 500

# States an entity passes through while it moves: the app stays connected to follow them.
MOVING_STATES = {
    "cover": {"opening", "closing"},
    "valve": {"opening", "closing"},
    "lock": {"locking", "unlocking", "opening"},
    "alarm_control_panel": {"arming", "disarming", "pending"},
}


def is_moving(entity_id, state):
    """Whether [state] is one an entity of [entity_id]'s domain passes through while it moves."""
    domain = entity_id.split(".", 1)[0]
    return state in MOVING_STATES.get(domain, ())


def _is_number(state):
    try:
        float(state)
    except (TypeError, ValueError):
        return False
    return True


# What an edge asks for: nothing, a routine wake, or an urgent one.
QUIET, VISIBLE, URGENT = None, "visible", "urgent"


def edge(entity_id, old_state, new_state):
    """What [entity_id] going from [old_state] to [new_state] asks for: URGENT when a movement
    starts or ends (`closed` to `opening`, `opening` to `open`), VISIBLE for another flip of its
    state, QUIET for none (the same state, its attributes changed) or a number ticking."""
    if old_state == new_state:
        return QUIET
    if is_moving(entity_id, old_state) or is_moving(entity_id, new_state):
        return URGENT
    if _is_number(old_state) and _is_number(new_state):
        return QUIET
    return VISIBLE


class _Device:
    def __init__(self, entities):
        self.entities = set(entities)
        self.lease_until = 0.0
        # A change waiting for a wake, and whether one of them is a movement.
        self.pending = False
        self.urgent = False
        self.last_wake = None
        self.day_start = 0.0
        self.day_count = 0


class StateWakes:
    """Per mobile_app registration (by webhook id): what it watches, its lease and its wakes."""

    def __init__(self):
        self.devices = {}

    def watched(self):
        """Every entity some device watches."""
        return set().union(*(d.entities for d in self.devices.values())) if self.devices else set()

    def entities(self, webhook_id):
        device = self.devices.get(webhook_id)
        return sorted(device.entities) if device else []

    def watch(self, webhook_id, entity_ids, lease_seconds, now):
        """Records the entities [webhook_id]'s widgets read, and a lease of [lease_seconds] (none
        for 0) while the app is connected and follows them itself."""
        device = self.devices.get(webhook_id)
        if device is None:
            device = self.devices[webhook_id] = _Device(entity_ids)
        else:
            device.entities = set(entity_ids)
        if lease_seconds > 0:
            device.lease_until = now + min(lease_seconds, MAX_LEASE)
            # The app is connected and sees every state: nothing earlier is left to wake for.
            device.pending = device.urgent = False
        else:
            device.lease_until = 0.0
        return device.lease_until

    def restore(self, webhook_id, entity_ids):
        """The entities [webhook_id] watched before Home Assistant restarted, without a lease."""
        if entity_ids:
            self.devices[webhook_id] = _Device(entity_ids)

    def forget(self, webhook_id):
        self.devices.pop(webhook_id, None)

    def changed(self, entity_id, old_state, new_state, now):
        """[entity_id] changed: the webhook ids to wake now, each with whether it is urgent. The
        rest wait for [due]. Only a visible [edge] counts."""
        kind = edge(entity_id, old_state, new_state)
        if kind is QUIET:
            return []
        urgent = kind == URGENT
        wakes = []
        for webhook_id, device in self.devices.items():
            if entity_id not in device.entities:
                continue
            device.pending = True
            device.urgent = device.urgent or urgent
            if self._ready(device, now):
                wakes.append((webhook_id, self._wake(device, now)))
        return wakes

    def due(self, now):
        """The webhook ids whose folded changes are due a wake now, each with whether it is urgent."""
        return [
            (webhook_id, self._wake(device, now))
            for webhook_id, device in self.devices.items()
            if device.pending and self._ready(device, now)
        ]

    def next_due(self, now):
        """When [due] next has something, or None while nothing waits."""
        times = [self._due_at(d, now) for d in self.devices.values() if d.pending]
        times = [t for t in times if t is not None]
        return min(times) if times else None

    def _due_at(self, device, now):
        if now - device.day_start >= DAY:
            budget_at = now
        elif device.day_count >= DAILY_WAKES:
            budget_at = device.day_start + DAY
        else:
            budget_at = now
        gap = URGENT_INTERVAL if device.urgent else INTERVAL
        throttle_at = now if device.last_wake is None else device.last_wake + gap
        return max(device.lease_until, budget_at, throttle_at)

    def _ready(self, device, now):
        return self._due_at(device, now) <= now

    def _wake(self, device, now):
        if now - device.day_start >= DAY:
            device.day_start, device.day_count = now, 0
        urgent = device.urgent
        device.pending = device.urgent = False
        device.last_wake = now
        device.day_count += 1
        return urgent
