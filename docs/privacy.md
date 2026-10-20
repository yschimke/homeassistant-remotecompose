---
title: Terrazzo — Privacy Policy
permalink: /privacy/
---

# Terrazzo — Privacy Policy

_Last updated: 2026-10-20_

Terrazzo (`ee.schimke.harc`) is built and maintained by Yuri Schimke
(`yuri@schimke.ee`). Source code:
<https://github.com/yschimke/homeassistant-remotecompose>.

## Data we collect

Terrazzo connects to a Home Assistant server **you provide**: your
dashboards, entity state, and authentication credentials are stored on
your device and sent only to the Home Assistant URL you configure. None
of that data is sent to us or to any third party.

The developer runs one service the app talks to, the **card design
server** (`https://ha.coo.ee/designs/v1` on Android, `https://ha.coo.ee`
in the desktop and web app, unless you change or clear it in Settings).
From it we keep only anonymous, aggregate counts:

1. **Which designs are downloaded** (always, while a design server is
   set). When the app checks for or downloads card designs it sends a
   fixed label naming the app release, e.g.
   `Terrazzo/0.1.48 (android; surface=phone)` — every install of a
   release sends the same one. The server counts, per day, how many
   checks and downloads there were for each design (card type, variant,
   card style) and each app release. The desktop and web app put the
   same kind of label (`Terrazzo/0.1.51 (web; surface=web)`) in the
   download address, because a web page can't set its own label.
2. **Which designs draw your cards** (only if you turn on
   *Settings → Share anonymous card usage*; **off by default**). Once a
   day the app sends, for the previous day, which designs drew its
   cards — card type, design variant, card style, where it drew them
   (phone dashboard, home-screen widget, desktop or web app), and whether
   the design came from the server, the app itself, or neither — with a
   rough count per design (1, 2–5, 6–20 or 21+ cards). On Android the
   setting covers the dashboard and the home-screen widgets; the desktop
   and web app each have their own setting, kept on that computer or in
   that browser. The Wear OS and Android TV apps send nothing.

What is **not** collected: your IP address (the design server doesn't
record it), any device or install identifier, your Home Assistant
address, entity ids, names or states, dashboard contents, or anything
you type. Counts are only ever looked at in aggregate, and a count with
fewer than five requests or reports behind it is never shown.

**Retention.** Daily counts are kept for about 13 months, then deleted.
Counts waiting on your device to be sent are deleted once sent, and
deleted at once if you turn sharing off.

**Turning it off.** Usage sharing is off unless you turn it on, and you
can turn it off again in Settings at any time. On Android, clearing the
design server address in Settings stops all contact with the design
server (the app then uses only its own designs). In the desktop and web
app an empty address falls back to `https://ha.coo.ee` for designs, but
usage is never sent without an address set.

## How Terrazzo communicates

- **Home Assistant server (you configure).** The app makes HTTP/WebSocket
  requests to the URL you set. Anything you can see in Home Assistant
  is what the app reads.
- **Authentication.** Terrazzo uses IndieAuth / OAuth2 against your
  Home Assistant server. The redirect scheme `rcha://` is handled
  in-app; tokens stay on your device.
- **Card design server (developer-operated, optional).** Described
  above: design downloads and, if you opt in, anonymous card usage.
- **No third-party services.** The app does not contact analytics,
  advertising, or crash-reporting providers.

## Permissions

The app requests the Android permissions it needs for the features
listed above (network, foreground service for live state, etc.). None
of these permissions are used to send data to the developer.

## Children

The app is not directed at children under 13 and does not knowingly
collect data from them.

## Contact

- Questions: `yuri@schimke.ee`
- Issues: <https://github.com/yschimke/homeassistant-remotecompose/issues>

## Changes

This policy may be updated from time to time. Material changes will be
reflected by an updated "Last updated" date above and will be visible
in the file's
[git history](https://github.com/yschimke/homeassistant-remotecompose/commits/main/docs/privacy.md).
