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
  `UI_BUILDER_TOKEN`, open the printed edit link, then "Design in UI Builder" — see
  [`card-design-ui-builder.md`](../docs/architecture/card-design-ui-builder.md)

Environment: `DESIGN_LIBRARY` (or the first argument), `PORT` (default 8080), `HOST`, and the
`UI_BUILDER_*` settings above. A container image with the library baked in and UI Builder bundled:
see `Dockerfile`.
