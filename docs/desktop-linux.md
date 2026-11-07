# Terrazzo desktop on Linux

Every release attaches a `.deb` for Linux x64. On Arch Linux and its derivatives (CachyOS,
EndeavourOS, Manjaro) install the `terrazzo-bin` package instead: it repackages that `.deb` with a
menu entry, icon and `terrazzo` command, and pacman updates it with the rest of the system.

## pacman repository (recommended)

Each published release adds its package to the `terrazzo` repository, which is hosted as assets on
this repository's [`pacman-repo` release](https://github.com/yschimke/homeassistant-remotecompose/releases/tag/pacman-repo).
Add it to `/etc/pacman.conf` once:

```ini
[terrazzo]
SigLevel = Optional TrustAll
Server = https://github.com/yschimke/homeassistant-remotecompose/releases/download/pacman-repo
```

Then:

```bash
sudo pacman -Sy terrazzo-bin
```

From then on, `sudo pacman -Syu` installs each new release.

The packages are not GPG-signed yet, so `SigLevel = Optional TrustAll` is needed. What you get is
whatever this repository's release workflow uploaded, fetched over HTTPS from GitHub. That's the
same trust as downloading the `.deb` by hand.

## AUR

`terrazzo-bin` is also set up to publish to the AUR for `paru`/`yay` users. It is not live yet:
AUR account registration was paused when this was set up. Once it is,
[`arch-packages.yml`](../.github/workflows/arch-packages.yml) says how to switch it on.

## Build it yourself

```bash
cd packaging/aur/terrazzo-bin
makepkg -si
```

This builds the version named in the checked-in PKGBUILD. CI updates `pkgver` for each release
but doesn't commit it.

## Other distributions

Install the `.deb` from the [latest release](https://github.com/yschimke/homeassistant-remotecompose/releases/latest)
on Debian or Ubuntu (`sudo apt install ./terrazzo-desktop-*-linux-x64.deb`). It doesn't update
itself.
