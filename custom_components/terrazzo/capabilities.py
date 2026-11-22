"""What `terrazzo/info` answers, as plain data in and out: no Home Assistant imports, so it is
tested without it (`home-assistant/test/capabilities_test.py`). `info.py` does the asking.

The rules a client relies on:

- `protocol` changes only when a change breaks clients; `min_client` is the oldest client protocol
  this integration still serves, so a client older than it says so instead of misbehaving.
- Each capability is an integer revision, a string, or an object. A revision is raised when the
  commands gain something a client may look for; an object only ever gains optional fields. Neither
  is ever lowered or changed in meaning.
- A capability that is missing is not supported, for this user: the answer is per user, so one
  this user can't use (an administrator's) is left out rather than offered and then refused.
"""

PROTOCOL = 1
MIN_CLIENT = 1

# Capabilities every signed-in user has, each with its commands or API.
EDIT = 1  # terrazzo/edit/* (start, list, get, subscribe, note, submit, discard, render, cloud)
PUSH = 1  # terrazzo/push/{register,take,watch}
DESIGN_SELECTIONS = 1  # terrazzo/design_selections/{get,set}
WEB_ORIGIN = 1  # terrazzo/web_origin
AGENT = 2  # terrazzo/agent/info (2: Home Assistant's conversation agent, with its name)
WALL = "v1"  # the wall display API's path version (wall_api.API)
# An administrator's, left out for everyone else.
EDIT_APPLY = 1  # terrazzo/edit/{apply,undo}
DESIGN_SERVER_OPEN = 1  # terrazzo/design_server/open


def reply(version, *, user_id, is_admin, design_server, mcp_user):
    """The `terrazzo/info` answer for one user.

    [version] is the integration's (its manifest's); [design_server] the card design add-on's
    Supervisor info, or None (`design_server.async_find`); [mcp_user] the id of the user the MCP
    server serves, or None while it is off.
    """
    started = design_server is not None and design_server.get("state") == "started"
    capabilities = {
        "edit": EDIT,
        "push": PUSH,
        "design_selections": DESIGN_SELECTIONS,
        # terrazzo/design_server: whether the add-on runs, as that command answers it.
        "design_server": {
            "available": started,
            "version": design_server.get("version") if started else None,
        },
        "web_origin": WEB_ORIGIN,
        "agent": AGENT,
        "wall": WALL,
    }
    # The MCP server serves one chosen user: it is that user's to use, and an administrator's to
    # turn on or off, so only they are told about it.
    if is_admin or (mcp_user and mcp_user == user_id):
        capabilities["mcp"] = {"enabled": bool(mcp_user)}
    if is_admin:
        capabilities["edit_apply"] = EDIT_APPLY
        capabilities["design_server_open"] = DESIGN_SERVER_OPEN
    return {
        "protocol": PROTOCOL,
        "min_client": MIN_CLIENT,
        "version": str(version) if version is not None else None,
        "capabilities": capabilities,
    }
