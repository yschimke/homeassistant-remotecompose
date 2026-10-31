"""Which web origin a Terrazzo sign-in comes from, and HA's HTTP config with that origin allowed.

Plain data and no Home Assistant imports, so it is tested on its own (home-assistant/test).
"""
import hashlib
from urllib.parse import urlsplit

CORS_ORIGINS = "cors_allowed_origins"
# A stored HTTP config's bookkeeping, which HA sets itself and a new config must not carry.
META_KEYS = ("created_at", "error", "error_message")


def origin_of(client_id):
    """The HTTPS origin of an OAuth `client_id`, or None for anything else: a long-lived token has
    no client id, and a plain-HTTP page couldn't use a camera image from an HTTPS HA anyway."""
    if not isinstance(client_id, str):
        return None
    try:
        parts = urlsplit(client_id)
        port = parts.port
    except ValueError:
        return None
    if parts.scheme != "https" or not parts.hostname or parts.username or parts.password:
        return None
    host = f"[{parts.hostname}]" if ":" in parts.hostname else parts.hostname
    return f"https://{host}" + (f":{port}" if port not in (None, 443) else "")


def allows(config, origin):
    return origin in (config or {}).get(CORS_ORIGINS, [])


def with_origin(config, origin):
    """[config], a stored HTTP config, without its bookkeeping and with [origin] allowed."""
    clean = {key: value for key, value in config.items() if key not in META_KEYS}
    origins = list(clean.get(CORS_ORIGINS) or [])
    if origin not in origins:
        origins.append(origin)
    clean[CORS_ORIGINS] = origins
    return clean


def issue_id(origin):
    """One repair per origin; an id can't hold the URL itself."""
    return "web_origin_" + hashlib.sha256(origin.encode()).hexdigest()[:12]
