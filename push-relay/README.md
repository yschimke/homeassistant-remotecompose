# Terrazzo push relay

Home Assistant's `mobile_app` integration delivers `notify.mobile_app_<device>` messages to the
Terrazzo Android app over the app's websocket while it is connected. When it isn't (or doesn't
confirm in time), HA POSTs the message to the registration's `app_data.push_url`. This Cloud
Function, `haPush`, is that URL: it forwards the message to the app's FCM token as a data message.
See [docs/architecture/notifications.md](../docs/architecture/notifications.md).

It runs in the same Firebase project as the app's `google-services.json`, under its own service
account, so no FCM credential is handed to Home Assistant or the HACS integration. It delivers only
to the Terrazzo app (`registration_info.app_id`), limits each token to 300 notifications a day per
instance, and refuses messages over FCM's 4 KB data limit.

## Deploy

Cloud Functions need the project on the Blaze plan.

```sh
cd push-relay
npx firebase-tools@latest deploy --only functions:push-relay --project <project-id>
```

The app derives the URL from its Firebase project:
`https://us-central1-<project-id>.cloudfunctions.net/haPush`. Override it with
`-Pharc.pushRelayUrl=<url>` when building the app.

## Test

```sh
cd push-relay/functions
npm ci && npm test
```
