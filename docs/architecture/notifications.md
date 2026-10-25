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
2. **Push relay.** Without a websocket, or when the app doesn't confirm a websocket message in
   time, HA POSTs the message to `app_data.push_url` with `push_token`. In Terrazzo that URL is the
   `haPush` Cloud Function ([push-relay/](../../push-relay/README.md)). The function forwards the
   message as an FCM data message to `push_token`, the install's FCM token, and
   `TerrazzoMessagingService` posts it.

Both paths parse into `HaPushNotification` and post through `HaNotifications`. A message that
arrives both ways posts once: it has the same id (`dedupeKey`) either way.

A build without Firebase (no `google-services.json`) has neither the relay URL nor a token. It
registers the websocket channel only, and notifications arrive only while the app is connected.

## Why a relay, not the HACS integration

FCM's send API needs the Firebase project's service-account credentials. Handing those to the
`terrazzo` HACS integration would put them on every user's Home Assistant, and anyone holding them
could push to every Terrazzo install. HA core already supports a per-app relay through `push_url`.
So the credential stays in Google's infrastructure: the relay runs under the project's own
service account. The integration isn't needed, and delivery works on any Home Assistant.

## The token

- **At sign-in.** `LoginController` registers with `PushRelay.target()`: the build's relay URL
  plus the current FCM token, or none.
- **On a token change.** `TerrazzoMessagingService.onNewToken`, or a registration that holds no
  token or an older one (checked as `HaPushReceiver` connects), sends `update_registration` on the
  registration's webhook. That replaces `app_data` without creating a new device. HA requires
  this call encrypted for registrations with `supports_encryption`. `SecretBox` seals it with
  NaCl's XSalsa20-Poly1305, matching PyNaCl, under the registration's secret.
- **When FCM drops the token.** The relay answers HA with `410`, and HA logs a message asking
  for the app to be opened.

## Privacy and limits

- **Privacy.** A relayed message passes through FCM as plain data, as it would for the companion
  apps. Websocket delivery stays on the user's network.
- **What the relay accepts.** The relay delivers only for `ee.schimke.harc` and refuses data over
  FCM's 4 KB limit.
- **Rate limit.** It allows each token 300 messages a day per function instance. This is best
  effort, not a quota.
- **No live card.** A relayed message has no session, so it has no live card or entity state. The
  watch copy gets the standard fields, and the toggle and buttons still run on the phone.

## The watch

With the Wear app on a paired watch, the phone's copy is local-only. The watch posts its own copy
from a Data Layer item (`/wear/notification/<id>`, `WearNotifications`). Dismissal and buttons go
both ways. That works the same whichever path delivered the message.
