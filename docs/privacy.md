---
title: Terrazzo — Privacy Policy
permalink: /privacy/
---

# Terrazzo — Privacy Policy

_Last updated: 2026-04-27_

Terrazzo (`ee.schimke.harc`) is built and maintained by Yuri Schimke
(`yuri@schimke.ee`). Source code:
<https://github.com/yschimke/homeassistant-remotecompose>.

## Data we collect

**The developer collects nothing.** Terrazzo connects to a Home
Assistant server **you provide**: your dashboards, entity state, and
authentication credentials are stored on your device and sent only to
the Home Assistant URL you configure. None of that data is sent to us
or to any third party operated by us.

## How Terrazzo communicates

- **Home Assistant server (you configure).** The app makes HTTP/WebSocket
  requests to the URL you set. Anything you can see in Home Assistant
  is what the app reads.
- **Authentication.** Terrazzo uses IndieAuth / OAuth2 against your
  Home Assistant server. The redirect scheme `rcha://` is handled
  in-app; tokens stay on your device.
- **No third-party services.** The app does not contact any servers
  operated by the developer or by analytics, advertising, or
  crash-reporting providers.

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
