# Card design server

Hosts a card design library for the app and a browser of its templates. Design and protocol:
[`docs/architecture/card-design-server.md`](../docs/architecture/card-design-server.md#the-server-design-server).

```sh
./gradlew :card-designs:generateCardDesigns :design-server:installDist
design-server/build/install/design-server/bin/design-server card-designs/build/card-designs
```

- Browser: `http://localhost:8080/`
- App setting ("card design server"): `http://<host>:8080/designs/v1`
- JSON: `/api/designs`, `/api/designs/<id>`

- Card schema: a design's page, `/api/designs/<id>/schema`; any card's JSON at `/schema` or
  `POST /api/schema`
- UI Builder: set `UI_BUILDER_BINARY` (the bundled `compose-preview-server`) or `UI_BUILDER_URL` +
  `UI_BUILDER_TOKEN`, open the printed edit link (or sign in with GitHub, below), then "Design in
  UI Builder" — see [`card-design-ui-builder.md`](../docs/architecture/card-design-ui-builder.md)

Environment: `DESIGN_LIBRARY` (or the first argument), `PORT` (default 8080), `HOST`, and the
`UI_BUILDER_*` settings above. A container image with the library baked in and UI Builder bundled:
see `Dockerfile`.

## Signing in with GitHub

Without GitHub settings, editing is one shared operator link, which is fine on your own machine. A
public server signs people in with GitHub instead. Each design someone seeds is created by the
server and shared with their GitHub account as editor, and with nobody else. Designs start private
in the builder.

| Variable | For |
|---|---|
| `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET` | The design server's GitHub OAuth App. Callback: `<PUBLIC_URL>/auth/github/callback` |
| `PUBLIC_URL` | Where browsers reach the design server, e.g. `https://ha.coo.ee` |
| `SESSION_SECRET` | Signs the session cookie: a long random string |
| `GITHUB_USERS` | Optional: comma-separated logins allowed to design (default: anyone) |
| `UI_BUILDER_GITHUB_CLIENT_ID`, `UI_BUILDER_GITHUB_CLIENT_SECRET` | The bundled UI Builder's own OAuth App. Callback: `<UI_BUILDER_BROWSER_URL>/auth/github/callback` |
| `UI_BUILDER_COOKIE_SECRET` | Signs UI Builder's session cookie |
| `UI_BUILDER_BROWSER_URL` | Where browsers reach UI Builder, e.g. `https://builder.ha.coo.ee` |
| `UI_BUILDER_TOKEN` | The operator token the server creates and shares designs with. Set it, keep it secret |
| `UI_BUILDER_ARGS` | Optional: more `compose-preview-server serve` flags for the bundled builder |

You need two OAuth Apps because a GitHub OAuth App has one callback host. A GitHub App with both
callback URLs also works.

### Deploying at `ha.coo.ee`

It runs on the same box as `preview.coo.ee`, behind the same TLS proxy, as two names for one
container:

- `ha.coo.ee` → port 8080: the template browser, the schema page, sign-in, and the library the app
  reads (`https://ha.coo.ee/designs/v1` is the app's card design server setting).
- `builder.ha.coo.ee` → port 8724: the bundled UI Builder, where people edit the designs shared
  with them.

```sh
docker run -d --name ha-designs -p 8080:8080 -p 8724:8724 -v ha-designs:/data \
  -e PUBLIC_URL=https://ha.coo.ee -e UI_BUILDER_BROWSER_URL=https://builder.ha.coo.ee \
  -e GITHUB_CLIENT_ID=… -e GITHUB_CLIENT_SECRET=… -e SESSION_SECRET=… \
  -e UI_BUILDER_GITHUB_CLIENT_ID=… -e UI_BUILDER_GITHUB_CLIENT_SECRET=… \
  -e UI_BUILDER_COOKIE_SECRET=… -e UI_BUILDER_TOKEN=… \
  card-design-server
```

The builder is its own instance rather than `preview.coo.ee`'s. That box restricts who is a
member, and a guest there can only read a design shared with it, while anyone designing a card
here has to edit. Its state lives in the `/data` volume.
