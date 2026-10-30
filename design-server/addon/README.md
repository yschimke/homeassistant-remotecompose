# Terrazzo Card Designs (Home Assistant add-on)

The card design server and UI Builder that run at ha.coo.ee, private to one Home Assistant. It is
reached only through Home Assistant, so it is reachable wherever Home Assistant is, Nabu Casa's
remote URL included, and needs no GitHub sign-in.

## Install

1. Settings → Add-ons → Add-on store → ⋮ → Repositories: add
   `https://github.com/yschimke/homeassistant-remotecompose`.
2. Install **Terrazzo Card Designs** and start it. The Supervisor builds it on the box from the
   published `ghcr.io/yschimke/ha-card-design-server` image (amd64 and arm64).
3. Install the Terrazzo integration (HACS) too: it tells the app the add-on is there
   (`terrazzo/design_server`) and passes the app's calls on to it (`/api/terrazzo/designs`).

The **Card designs** panel (administrators only) opens the design server's pages and UI Builder.
Its library follows ha.coo.ee's (`LIBRARY_UPSTREAM`), checked hourly; the image's library is only
where it starts.

## How the app uses it

The integration answers the app's `terrazzo/design_server` and passes its calls to the design
server's API (`/api/terrazzo/designs/...`: the library, resolving a card, Card Studio and the
person's own designs, never UI Builder or a page) with the app's own token and the person's
username. To open a design in a browser, the app asks `terrazzo/design_server/open` for a one-time
link (administrators only, good for a minute): it creates an ingress session for that user through
the Supervisor, sets its cookie as Home Assistant's frontend does, and redirects to the page under
the add-on's ingress URL. Home Assistant's panel can't open a page below the add-on's root, which
is why the link exists.

## How it fits in one ingress port

Home Assistant ingress serves the add-on under a per-install prefix (`/api/hassio_ingress/<token>`)
that it strips before forwarding, naming it in `X-Ingress-Path` and the person in
`X-Remote-User-Name`. Neither server knows about prefixes, so:

- `nginx.conf` splits paths between the design server (`:8080`) and UI Builder (`:8724`) as
  ha.coo.ee's Caddy does, prefixes redirects, cookie paths and the URLs in HTML and CSS, and puts
  `ingress.js` and the editor's base path at the top of every page.
- `ingress.js` prefixes what scripts reach at run time: `fetch`, XHR, WebSockets, workers, URLs set
  on elements, forms, `history` and other navigations (through the Navigation API). It turns
  service workers off: one would register on Home Assistant's own origin.

Who may in:

- **Ingress** (the Supervisor, `172.30.32.2`): everything. The panel is admin-only, so UI Builder
  is used as its operator, whose token nginx adds; no browser sees it.
- **Anything else on Home Assistant's network** (Core, for the integration's proxy): only with a
  Home Assistant access token Core accepts (`auth_request` to Core's `/api/`), and only the design
  server's API, never UI Builder.

The design server reads who is signed in from `X-Remote-User-Name` (`TRUSTED_USER_HEADER`), so
the person's own designs, Card Studio and publishing work as on ha.coo.ee, keyed by their Home
Assistant username.

## Testing it without Home Assistant

A Docker network on `172.30.32.0/23` stands in for Home Assistant's: an nginx at `172.30.32.2` that
strips `/api/hassio_ingress/test` and sets the ingress headers, and the add-on at `172.30.33.10`
started with `CORE_URL` pointing at a Home Assistant (or a stub) that checks tokens. Build the add-on
on a design server image with your local `design-server/build/install/design-server/lib`, open
`http://localhost:<port>/api/hassio_ingress/test/` and check that no request leaves the prefix.
`home-assistant/test/installed-designs.test.mjs` with `DESIGNS_ADDON=1` checks the integration's
side against it, with Home Assistant's `SUPERVISOR` pointed at a stub that lists the add-on.
