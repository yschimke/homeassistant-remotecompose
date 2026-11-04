"""Relaying a cloud agent's drafts into Home Assistant without an app open.

A person who asks a cloud agent from the app opens a request on the design server and links its
owner capability to their edit session (`edit_sessions.EditSessions.link_cloud`). Home Assistant
then long-polls that request itself and submits each new draft as the person who started the
session, with the same checks as a draft the app relays (`edits.submit`). The app still relays
too while it is open; the shared `cloud:<revision>` relay id makes the second submission a no-op.

Free of Home Assistant imports, like `edit_sessions.py`: `edits.py` supplies the HTTP calls and
the session; the tests drive [relay] with plain functions.
"""
import asyncio
import logging

_LOGGER = logging.getLogger(__name__)

# The design server holds a state request up to 25 s.
STATE_PATH = "/api/agent/session"
DRAFT_PATH = "/api/studio/dashboard-session"
MAX_PAUSE_SECONDS = 300


class Ended(Exception):
    """The design server no longer has the request (it expired or was revoked)."""


def relay_id(revision):
    """The id both the app and Home Assistant give the relay's [revision]."""
    return f"cloud:{revision}"


async def relay(link, get_json, submit, relayed, ended, sleep=asyncio.sleep):
    """Relay drafts for one session until it closes or the design server ends the request.

    [link]() is the session's cloud link now (`server`, `owner`, `revision`), or None once the
    session closed or was unlinked. [get_json](url, owner) answers the parsed JSON, raises [Ended]
    for a request the server no longer has, and any other exception for a failure worth retrying.
    [submit](config, summary, relay_id) hands a draft to the session; [relayed](revision) records
    that a revision is done with; [ended]() forgets the link.
    """
    since = None
    pause = 1
    while True:
        cloud = link()
        if cloud is None:
            return
        server, owner = cloud["server"], cloud["owner"]
        try:
            state = await get_json(
                server + STATE_PATH + (f"?since={since}" if since is not None else ""), owner
            )
            since = state.get("version", since)
            revision = state.get("revision", 0)
            if isinstance(revision, int) and revision > cloud.get("revision", 0):
                draft = await get_json(server + DRAFT_PATH, owner)
                await _hand_over(draft, cloud, submit, relayed)
            pause = 1
        except Ended:
            ended()
            return
        except asyncio.CancelledError:
            raise
        except Exception as failure:  # noqa: BLE001 - a network blip or a server deploy
            _LOGGER.debug("Terrazzo cloud relay: %s; retrying in %s s", failure, pause)
            await sleep(pause)
            pause = min(pause * 2, MAX_PAUSE_SECONDS)


async def _hand_over(draft, cloud, submit, relayed):
    revision = draft.get("revision")
    config = draft.get("dashboard")
    if not isinstance(revision, int) or revision <= cloud.get("revision", 0):
        return
    if isinstance(config, dict):
        try:
            await submit(config, draft.get("summary") or "", relay_id(revision))
        except ValueError as refused:
            # Home Assistant's own checks refused it (an unknown entity, say): the app shows the
            # agent's next draft; this one is not retried.
            _LOGGER.info("Terrazzo cloud relay: draft %s refused: %s", revision, refused)
    relayed(revision)
