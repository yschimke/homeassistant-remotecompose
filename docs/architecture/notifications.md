# Home Assistant notifications

Terrazzo registers with Home Assistant's `mobile_app` integration as its own device, so HA exposes
`notify.mobile_app_<device>` for it. The registration is the Terrazzo app's (`app_id`
`ee.schimke.harc`). Nothing here goes through the Home Assistant companion app or Nabu Casa's push
relay.

## Delivery

HA picks the path per message (`homeassistant/components/mobile_app/notify.py`):

1. **Websocket.** The registration asks for `app_data.push_websocket_channel`. While the app holds
   its websocket, `HaPushReceiver` subscribes to `mobile_app/push_notification_channel` with
   confirmation on, and HA sends there.
2. **Silent push.** Without a websocket, or when the app doesn't confirm a message within 10 s, HA
   POSTs the message to `app_data.push_url`. With the Terrazzo integration installed, that URL is
   the integration's own endpoint on HA (`/api/terrazzo/push/<webhook_id>`, `push.py`), reached
   over loopback.
   1. **Hold.** The integration checks `push_token` (a per-device secret) and holds the message.
   2. **Wake.** It asks the Terrazzo push relay ([push-relay/](../../push-relay/README.md)) to wake
      the app. The relay sends a high-priority FCM data message containing only
      `{type: "sync", webhook_id}`.
   3. **Collect.** `TerrazzoMessagingService` wakes and connects with the app's own login. It takes
      the held messages with `terrazzo/push/take` and posts them, live card included.

   The app also takes anything held each time it connects, so a lost wake only delays a message.

Every path parses into `HaPushNotification` and posts through `HaNotifications`. A message that
arrives two ways posts once, because it has the same id (`dedupeKey`).

Without the integration, or in a build without Firebase (no `google-services.json`, so no relay
and no token), the registration has no push URL, and notifications arrive only while the app is
connected.

## What leaves Home Assistant

Only the wake. The message's title, text and data stay on the HA machine: HA posts them to itself,
and the app fetches them over its authenticated websocket.

The relay and FCM see the app's FCM token and the registration's webhook id, a random id that
grants nothing without the device's secret. FCM sending needs the Firebase project's
service-account credentials, and those stay inside Google: the relay runs under the project's own
service account. Neither HA nor the integration holds a Firebase credential.

## Registration

1. **On connect.** Each time the app connects, it sends `terrazzo/push/register` with its FCM
   token and the build's relay URL (`BuildConfig.PUSH_RELAY_URL`). The integration first checks
   that the caller owns that `mobile_app` registration. It answers the `push_url` and `push_token`
   to give HA.
2. **Updating HA.** When those differ from what HA holds, the app sends `update_registration` on
   the registration's webhook. That replaces `app_data` without creating a new device. HA requires
   this call encrypted for registrations with `supports_encryption`, and `SecretBox` seals it.
   `SecretBox` is NaCl's XSalsa20-Poly1305, matching PyNaCl, under the registration's secret.
3. **Token changes.** A new FCM token (`onNewToken`) is registered the same way, on a short-lived
   session. The push token stays the same across FCM tokens, so HA's registration only changes
   once.

## Limits

- **Integration.** It holds up to 50 messages per device, for a day, in `.storage/terrazzo.push`.
- **Relay.** It only wakes `ee.schimke.harc`, and allows 500 wakes per token a day per instance.
  Wakes for one registration collapse in FCM.
- **TLS on HA's own port.** If HA serves TLS on its own port, the push URL is HA's configured URL
  instead of loopback.
- **Removing the integration.** That leaves HA posting to a URL that answers 404. HA logs it, and
  delivery is websocket-only until the app re-registers.

## The watch

With the Wear app on a paired watch, the phone's copy is local-only. The watch posts its own copy
from a Data Layer item (`/wear/notification/<id>`, `WearNotifications`), and dismissal and buttons
go both ways. It works the same whichever path delivered the message.
