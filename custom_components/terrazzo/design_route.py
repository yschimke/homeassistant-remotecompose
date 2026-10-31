"""Which add-on is the private card design server, and what the app may ask it through Home Assistant.

Plain data in and out (nothing from HA), so the rules are tested without it; `design_server.py`
does the I/O. The add-on is design-server/addon in this repository.
"""

# Installed from a repository the Supervisor names it `<repository hash>_card_designs`; installed
# as a local add-on, `local_card_designs`.
ADDON_SLUG = "card_designs"
# The add-on's ingress port, where its nginx splits the design server from UI Builder.
ADDON_PORT = 8099
# Where the app reaches the design server, signed in with its own Home Assistant token.
DESIGNS_URL = "/api/terrazzo/designs"

# The design server's app API: the library, resolving a card's design, Card Studio's drafts and the
# person's own designs. Never UI Builder and never a page: those are for a browser, through the
# admin-only ingress panel.
APP_PATHS = ("designs/v1/", "api/cards/resolve", "api/studio/", "api/my/")

# Where a one-time link (`terrazzo/design_server/open`) signs a browser in to the add-on's ingress.
OPEN_URL = "/api/terrazzo/designs-open"
# The pages such a link may open: a UI Builder design, a Card Studio handoff, the person's designs.
PAGE_PATHS = ("ui-builder/", "studio/", "my")

# Request headers the app's call carries on to the design server. The rest are Home Assistant's,
# or are set below.
FORWARDED_REQUEST = ("accept", "authorization", "content-type", "if-none-match", "if-modified-since")
# Response headers the app gets back.
FORWARDED_RESPONSE = (
    "cache-control",
    "content-type",
    "etag",
    "last-modified",
    "location",
)


def find_addon(addons):
    """The card design add-on among the Supervisor's `GET /addons` list, or None. Started ones
    first, since one copy may be installed from a repository and another locally."""
    candidates = [
        addon
        for addon in addons
        if addon.get("slug") == ADDON_SLUG or str(addon.get("slug", "")).endswith("_" + ADDON_SLUG)
    ]
    candidates.sort(key=lambda addon: addon.get("state") != "started")
    return candidates[0] if candidates else None


def app_path(path):
    """[path] (after DESIGNS_URL) when the app may ask for it, else None."""
    return _under(path, APP_PATHS)


def page_path(path):
    """[path] (a design server page, as its API names one: `/ui-builder/<id>`) when a one-time
    link may open it, else None."""
    return _under(path, PAGE_PATHS)


def _under(path, prefixes):
    """[path] without its leading slash when it is one of [prefixes] or under one. Dot segments,
    backslashes, percent-escapes (none of these paths has one), queries and fragments are refused,
    so a path can't climb out of the prefixes however it is decoded."""
    path = path.lstrip("/")
    segments = path.split("/")
    if any(c in path for c in "\\%?#") or any(segment in (".", "..") for segment in segments):
        return None
    if any(path == prefix.rstrip("/") or path.startswith(prefix) for prefix in prefixes):
        return path
    return None


def upstream_headers(headers, username, host):
    """The headers a call to the design server carries: the app's own (its token among them, which
    the add-on checks with Core), who the person is, and where the design server is reached, so its
    redirects and links come back under DESIGNS_URL. Whatever the app sent for those is replaced."""
    out = {name: value for name, value in headers.items() if name.lower() in FORWARDED_REQUEST}
    out["X-Ingress-Path"] = DESIGNS_URL
    out["X-Forwarded-Host"] = host
    if username:
        out["X-Remote-User-Name"] = username
    return out


def downstream_headers(headers):
    """The design server's response headers the app sees."""
    return {name: value for name, value in headers.items() if name.lower() in FORWARDED_RESPONSE}
