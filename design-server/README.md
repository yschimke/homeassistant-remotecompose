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
- Card embed: `/embed/v1/ha-card.js` (a `<ha-card>` for any web page, signed in to the viewer's own
  Home Assistant), its player `/embed/v1/rc-player.js` and a demo at `/embed/v1/demo.html` — see
  [`card-embedding.md`](../docs/architecture/card-embedding.md)

The `/designs` browser defaults to the first available mode of the selected theme. Console's
fixed palette is available in both light and dark host modes: `mode=light`, `mode=dark` and
`mode=all` each return the same console designs once, in the browser and `/api/designs`.
Their keys, blobs and previews stay unchanged. Other themes retain mode-specific filtering;
unsupported combinations link to available modes while preserving the other filters.

- Card schema: a design's page, `/api/designs/<id>/schema`; any card's JSON at `/schema` or
  `POST /api/schema`
- Card resolve: `POST /api/cards/resolve` with a card's config (optionally `states`, `cardStyle`
  and `dark`) answers with the library design that draws it, its `.rc` and the values to push, for
  players with no card converters (UI Builder's canvas, the embed). Any origin may call it. The
  server can't run the converters, so when several shapes fit it picks the first (`exact: false`)
- Design source: `/api/designs/<id>/source.uid`, a UI Builder document to fork a library design
  from — the card rebuilt from `ha-cards` components in the design's uber theme, its entities named
  by slot so a fork binds what the template does. Close to the library design, not identical: the
  library's templates are captured from the app's converters, which have no editable source
- UI Builder: set `UI_BUILDER_BINARY` (the bundled `compose-preview-server`) or `UI_BUILDER_URL` +
  `UI_BUILDER_TOKEN`, open the printed edit link (or sign in with GitHub, below), then "Fork in UI
  Builder" on a design (it starts from the design's source) or "Design in UI Builder" on a pasted
  card — see [`card-design-ui-builder.md`](../docs/architecture/card-design-ui-builder.md)

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
| `UI_BUILDER_ARGS` | Optional: more `compose-preview-server serve` flags for the bundled builder. The image defaults load `/app/catalogs.json` with `remote-m3,ha-cards`; retain those flags when overriding this value. |

You need two OAuth Apps because a GitHub OAuth App has one callback host. A GitHub App with both
callback URLs also works.

### Deploying at `ha.coo.ee`

`ha.coo.ee` is served from the same box as `preview.coo.ee`, by compose-preview-server's
deploy (`deploy/image/docker-compose.yml`, the `ha-designs` service, and the `{$HA_DOMAIN}` block
of its `Caddyfile`). `.github/workflows/design-server.yml` publishes this image as
`ghcr.io/yschimke/ha-card-design-server` on every push to `main`. One hostname, split by path:

- `/`, `/designs*`, `/site/*`, `/schema`, `/embed/*`, `/studio/*`, `/api/designs*`, `/api/schema`,
  `/api/studio*` and the library at `/designs/v1` (the app's card design server setting) go to
  this server.
- The card designer (`/ui-builder/*`, `/api/ui-builder/*`, `/auth/*`, `/mcp`) and the catalog
  (`/homeassistant-remotecompose/*` and the routes its pages load) go to the preview server, whose
  builder profile white-labels them as the Home Assistant card designer.

There it runs without its bundled UI Builder (`UI_BUILDER_BINARY` empty): `UI_BUILDER_URL` and
`UI_BUILDER_TOKEN` point it at the preview box's builder instead.

Run on its own, with the builder bundled:

```sh
docker run -d --name ha-designs -p 8080:8080 -p 8724:8724 -v ha-designs:/data \
  -e PUBLIC_URL=https://ha.coo.ee -e UI_BUILDER_BROWSER_URL=https://builder.ha.coo.ee \
  -e GITHUB_CLIENT_ID=… -e GITHUB_CLIENT_SECRET=… -e SESSION_SECRET=… \
  -e UI_BUILDER_GITHUB_CLIENT_ID=… -e UI_BUILDER_GITHUB_CLIENT_SECRET=… \
  -e UI_BUILDER_COOKIE_SECRET=… -e UI_BUILDER_TOKEN=… \
  ghcr.io/yschimke/ha-card-design-server
```

Run that way, the bundled builder is its own instance rather than `preview.coo.ee`'s. That box restricts who is a
member, and a guest there can only read a design shared with it, while anyone designing a card
here has to edit. Its state lives in the `/data` volume.
