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

Environment: `DESIGN_LIBRARY` (or the first argument), `PORT` (default 8080), `HOST`. A container
image with the library baked in: see `Dockerfile`.
