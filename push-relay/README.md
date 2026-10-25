# Terrazzo push relay

Home Assistant's `mobile_app` delivers `notify.mobile_app_<device>` messages to the Terrazzo app
over its websocket while it is connected. When it isn't, HA posts the message to the Terrazzo
integration in that same Home Assistant, which holds it and calls this Cloud Function, `haPush`, to
wake the app. The wake is a high-priority FCM data message with only `{type: "sync", webhook_id}`;
the app connects and takes the held messages itself. No notification content reaches the relay or
FCM. See [docs/architecture/notifications.md](../docs/architecture/notifications.md).

It runs in the same Firebase project as the app's `google-services.json`, under its own service
account, so no FCM credential is handed to Home Assistant or the integration. It wakes only the
Terrazzo app (`app_id`), limits each token to 500 wakes a day per instance, and collapses repeated
wakes for one registration.

## Deploy

Cloud Functions need the project on the Blaze plan.

```sh
cd push-relay
npx firebase-tools@latest deploy --only functions:push-relay --project <project-id>
```

The app derives the URL from its Firebase project,
`https://us-central1-<project-id>.cloudfunctions.net/haPush`, and hands it to the integration when
it registers. Override it with `-Pharc.pushRelayUrl=<url>` when building the app.

## Test

```sh
cd push-relay/functions
npm ci && npm test
```
