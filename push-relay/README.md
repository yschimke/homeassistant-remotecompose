# Terrazzo push relay

Home Assistant's `mobile_app` delivers `notify.mobile_app_<device>` messages to the Terrazzo app
over its websocket while it is connected. When it isn't, HA posts the message to the Terrazzo
integration in that same Home Assistant, which holds it and calls this Cloud Function, `haPush`, to
wake the app. The wake is a high-priority FCM data message with only `{type: "sync", webhook_id}`;
the app connects and takes the held messages itself. No notification content reaches the relay or
FCM. See [docs/architecture/notifications.md](../docs/architecture/notifications.md).

The integration also asks for `{type: "state", webhook_id}` wakes when an entity a widget shows
visibly changes: high priority when a movement starts or ends (a garage door `opening`), normal
priority otherwise. See [docs/architecture/live-widget-updates.md](../docs/architecture/live-widget-updates.md).

It runs in the same Firebase project as the app's `google-services.json`, under its own service
account, so no FCM credential is handed to Home Assistant or the integration. It wakes only the
Terrazzo app (`app_id`), limits each token to 500 wakes a day per instance, and collapses repeated
wakes for one registration.

## Deploy

Each release deploys it: the `push-relay` job in `.github/workflows/release-build.yml` tests the
function, signs in to Google Cloud and runs `firebase deploy --only functions:push-relay` with the
Firebase CLI pinned in `package.json` here. The project is the one in `GOOGLE_SERVICES_JSON`, the same
one the app derives its relay URL from, unless the `FIREBASE_PROJECT_ID` repository variable names
another. Without credentials the job is skipped.

Set it up once:

1. Put the project on the Blaze plan (Cloud Functions needs it).
2. Create a deploy service account with the roles `Cloud Functions Admin`, `Service Account User`
   (on the App Engine default and Compute default service accounts), `Artifact Registry
   Administrator`, `Cloud Build Editor` and `Firebase Admin`
   ([Firebase's deploy permissions](https://firebase.google.com/docs/projects/iam/permissions)).
3. Either let GitHub sign in keylessly (preferred): create a Workload Identity pool and an OIDC
   provider for `token.actions.githubusercontent.com` limited to this repository, allow it to
   impersonate the service account, and set the repository variables
   `FIREBASE_WORKLOAD_IDENTITY_PROVIDER` (`projects/<number>/locations/global/workloadIdentityPools/<pool>/providers/<provider>`)
   and `FIREBASE_DEPLOY_SERVICE_ACCOUNT` (its email);
   or store a key for it as the `FIREBASE_DEPLOY_SERVICE_ACCOUNT_JSON` secret.

The job runs in the `push-relay` GitHub environment (Settings → Environments). Add yourself as a
required reviewer there and each release waits for your approval before deploying; the variables
or key above can live in that environment instead of the repository, so only this job sees them.
To hold the Google side to the same gate, add `&& assertion.environment == 'push-relay'` to the
Workload Identity provider's attribute condition:

```sh
gcloud iam workload-identity-pools providers update-oidc terrazzo-release \
  --location=global --workload-identity-pool=github \
  --attribute-condition="<the existing condition> && assertion.environment == 'push-relay'"
```

A failed wake's FCM error code is in the function's logs (`Wake refused`) and in the 502 answer.

To deploy by hand:

```sh
cd push-relay
npm ci
npx firebase deploy --only functions:push-relay --project <project-id>
```

The app derives the URL from its Firebase project,
`https://us-central1-<project-id>.cloudfunctions.net/haPush`, and hands it to the integration when
it registers. Override it with `-Pharc.pushRelayUrl=<url>` when building the app.

## Test

```sh
cd push-relay/functions
npm ci && npm test
```
