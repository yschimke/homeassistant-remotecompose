"""Held notifications for the Terrazzo app's silent push (docs/architecture/notifications.md).

Home Assistant's mobile_app sends a registration's notifications to its `push_url` while the app
holds no websocket. For a registration the Terrazzo app set up through this integration, that URL is
this integration's: the message is kept here, and the app is woken with an FCM message that carries
no content, only that there is something to fetch. The app then takes the held messages over its
own authenticated websocket. Nothing of the message leaves Home Assistant.

Plain data and no Home Assistant imports, so it is tested on its own (home-assistant/test).
"""
import hmac
import secrets

# How many messages a device holds, and for how long, before the oldest go.
MAX_HELD = 50
HOLD_SECONDS = 24 * 60 * 60


class PushQueue:
    """Per mobile_app registration (by webhook id): its FCM token, the relay that wakes it, the
    secret HA presents as `push_token`, and the messages waiting for it."""

    def __init__(self, data=None):
        self.devices = {}
        for webhook_id, device in ((data or {}).get("devices") or {}).items():
            self.devices[webhook_id] = {
                "user_id": device["user_id"],
                "fcm_token": device["fcm_token"],
                "relay_url": device["relay_url"],
                "secret": device["secret"],
                "held": list(device.get("held") or []),
            }

    def to_data(self):
        return {"devices": self.devices}

    def register(self, webhook_id, user_id, fcm_token, relay_url):
        """Records the device's FCM token and relay; returns the secret HA is to present. The secret
        stays the same across new tokens, so HA's registration only changes when it must."""
        device = self.devices.get(webhook_id)
        if device is None or device["user_id"] != user_id:
            device = {"user_id": user_id, "secret": secrets.token_urlsafe(32), "held": []}
            self.devices[webhook_id] = device
        device["fcm_token"] = fcm_token
        device["relay_url"] = relay_url
        return device["secret"]

    def accepts(self, webhook_id, secret):
        """Whether [secret] is the device's: a POST to the push URL comes from HA, unauthenticated."""
        device = self.devices.get(webhook_id)
        return (
            device is not None
            and isinstance(secret, str)
            and hmac.compare_digest(device["secret"], secret)
        )

    def hold(self, webhook_id, message, now):
        """Keeps HA's [message] (its `message`, `title` and `data`) for the device; returns the
        device's wake target (FCM token and relay URL)."""
        device = self.devices[webhook_id]
        held = [m for m in device["held"] if now - m["at"] < HOLD_SECONDS]
        held.append({"at": now, "message": _content(message)})
        device["held"] = held[-MAX_HELD:]
        return device["fcm_token"], device["relay_url"]

    def take(self, webhook_id, user_id, now):
        """The device's held messages, oldest first, for its own user only; they are gone after."""
        device = self.devices.get(webhook_id)
        if device is None or device["user_id"] != user_id:
            return []
        held = [m["message"] for m in device["held"] if now - m["at"] < HOLD_SECONDS]
        device["held"] = []
        return held

    def forget(self, webhook_id):
        self.devices.pop(webhook_id, None)


def _content(message):
    """What the app needs of HA's push body: never the push token or registration info."""
    return {key: message[key] for key in ("message", "title", "data") if key in message}
