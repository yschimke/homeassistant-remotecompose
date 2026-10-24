"""Edit sessions: the handoff between a person and an agent changing one dashboard.

A person (from the phone or the panel) or an agent (over MCP) opens a session on a dashboard with
a request; it keeps the config the dashboard had then. The agent submits drafts; it never saves.
A person previews a draft and an administrator applies it with their own session, only while the
dashboard still holds the config the session started from (docs/architecture/dashboard-editing.md).

Free of Home Assistant imports, like `mcp.py`: `edits.py` stores the sessions and reaches the
dashboards; the tests drive this with plain data.
"""
import copy
import json
import secrets

OPEN = "open"
APPLIED = "applied"
DISCARDED = "discarded"

# A session lives a day from its last change; a home has few at once.
SESSION_TTL_SECONDS = 24 * 3600
MAX_SESSIONS = 50
MAX_DRAFTS = 10
MAX_NOTES = 50
MAX_TEXT = 2000
MAX_DRAFT_BYTES = 512 * 1024
_UNKNOWN_SHOWN = 10


class SessionError(Exception):
    """A request the session can't take; the message is for the person or agent who made it."""


class Conflict(SessionError):
    """The dashboard changed since the session started (or since its draft was applied)."""


def referenced_entities(config):
    """Every entity id [config] names in an `entity` key or an `entities` list, at any depth."""
    found = set()

    def visit(node):
        if isinstance(node, dict):
            entity = node.get("entity")
            if isinstance(entity, str) and "." in entity:
                found.add(entity)
            entities = node.get("entities")
            if isinstance(entities, list):
                for row in entities:
                    if isinstance(row, str) and "." in row:
                        found.add(row)
            for value in node.values():
                visit(value)
        elif isinstance(node, list):
            for value in node:
                visit(value)

    visit(config)
    return found


def validate_draft(config, entity_exists):
    """Refuse a draft that isn't a dashboard or names entities this home doesn't have."""
    if not isinstance(config, dict):
        raise SessionError("A draft is a dashboard config: a JSON object with a views list")
    views = config.get("views")
    if not isinstance(views, list) or not all(isinstance(view, dict) for view in views):
        raise SessionError("A draft needs a views list of objects")
    if len(json.dumps(config, default=str)) > MAX_DRAFT_BYTES:
        raise SessionError(f"A draft may be at most {MAX_DRAFT_BYTES // 1024} KB")
    unknown = sorted(entity for entity in referenced_entities(config) if not entity_exists(entity))
    if unknown:
        shown = ", ".join(unknown[:_UNKNOWN_SHOWN])
        more = f" and {len(unknown) - _UNKNOWN_SHOWN} more" if len(unknown) > _UNKNOWN_SHOWN else ""
        raise SessionError(f"The draft names entities this home doesn't have: {shown}{more}")


def _text(value, what, required=False):
    if value is None and not required:
        return ""
    if not isinstance(value, str) or (required and not value.strip()):
        raise SessionError(f"{what} must be text")
    return value.strip()[:MAX_TEXT]


class EditSessions:
    """The sessions of one home, as plain data (`to_data`) for Home Assistant's storage."""

    def __init__(self, data=None):
        self._sessions = {session["id"]: session for session in (data or [])}

    def to_data(self):
        return list(self._sessions.values())

    def prune(self, now):
        """Forget expired sessions, then the oldest beyond [MAX_SESSIONS]."""
        for session_id in [s["id"] for s in self._sessions.values() if s["expires_at"] <= now]:
            del self._sessions[session_id]
        if len(self._sessions) > MAX_SESSIONS:
            oldest = sorted(self._sessions.values(), key=lambda s: s["updated_at"])
            for session in oldest[: len(self._sessions) - MAX_SESSIONS]:
                del self._sessions[session["id"]]

    def create(self, url_path, request, baseline, user_id, now):
        session = {
            "id": secrets.token_urlsafe(9),
            "url_path": url_path,
            "request": _text(request, "The request"),
            "created_by": user_id,
            "created_at": now,
            "updated_at": now,
            "expires_at": now + SESSION_TTL_SECONDS,
            "status": OPEN,
            "baseline": copy.deepcopy(baseline),
            "drafts": [],
            "notes": [],
            "applied": None,
        }
        self._sessions[session["id"]] = session
        self.prune(now)  # After adding: the oldest goes, never the new one.
        return session

    def get(self, session_id, now):
        self.prune(now)
        session = self._sessions.get(session_id) if isinstance(session_id, str) else None
        if session is None:
            raise SessionError(f"No edit session {session_id}; it may have expired")
        return session

    def for_dashboard(self, url_path, now):
        """The open sessions on [url_path], newest first."""
        self.prune(now)
        return sorted(
            (s for s in self._sessions.values() if s["url_path"] == url_path and s["status"] == OPEN),
            key=lambda s: s["updated_at"],
            reverse=True,
        )

    def _touch(self, session, now):
        session["updated_at"] = now
        session["expires_at"] = now + SESSION_TTL_SECONDS

    def _open(self, session_id, now):
        session = self.get(session_id, now)
        if session["status"] != OPEN:
            raise SessionError(f"Edit session {session_id} is {session['status']}")
        return session

    def submit(self, session_id, config, summary, entity_exists, user_id, now):
        """Add a draft to an open session; the newest [MAX_DRAFTS] are kept."""
        session = self._open(session_id, now)
        validate_draft(config, entity_exists)
        draft = {
            "id": secrets.token_urlsafe(6),
            "config": copy.deepcopy(config),
            "summary": _text(summary, "The summary"),
            "by": user_id,
            "at": now,
        }
        session["drafts"] = (session["drafts"] + [draft])[-MAX_DRAFTS:]
        self._touch(session, now)
        return draft

    def note(self, session_id, text, user_id, now):
        """A note for the agent ("request changes"), read on its next call."""
        session = self._open(session_id, now)
        note = {"text": _text(text, "The note", required=True), "by": user_id, "at": now}
        session["notes"] = (session["notes"] + [note])[-MAX_NOTES:]
        self._touch(session, now)
        return note

    def discard(self, session_id, user_id, now):
        session = self._open(session_id, now)
        session["status"] = DISCARDED
        session["discarded"] = {"by": user_id, "at": now}
        self._touch(session, now)
        return session

    def draft(self, session, draft_id):
        found = next((d for d in session["drafts"] if d["id"] == draft_id), None)
        if found is None:
            raise SessionError(f"Edit session {session['id']} has no draft {draft_id}")
        return found

    def check_apply(self, session_id, draft_id, current, now):
        """The config to save for [draft_id], if the dashboard still holds the session's baseline."""
        session = self._open(session_id, now)
        draft = self.draft(session, draft_id)
        if current != session["baseline"]:
            raise Conflict(
                "This dashboard changed in Home Assistant since the edit session started. "
                "Start a new session so the agent works from the current dashboard."
            )
        return draft["config"]

    def applied(self, session_id, draft_id, user_id, now):
        session = self.get(session_id, now)
        session["status"] = APPLIED
        session["applied"] = {"draft_id": draft_id, "by": user_id, "at": now}
        self._touch(session, now)
        return session

    def check_undo(self, session_id, current, now):
        """The baseline to restore, if the dashboard still holds the draft that was applied."""
        session = self.get(session_id, now)
        if session["status"] != APPLIED:
            raise SessionError(f"Edit session {session_id} has no applied draft to undo")
        if current != self.draft(session, session["applied"]["draft_id"])["config"]:
            raise Conflict("This dashboard changed in Home Assistant since the draft was applied.")
        return session["baseline"]

    def undone(self, session_id, now):
        """Back to open, as before the draft was applied: the drafts and notes stay."""
        session = self.get(session_id, now)
        session["status"] = OPEN
        session["applied"] = None
        self._touch(session, now)
        return session


def summary(session, with_configs=False):
    """A session as callers see it; configs only when asked, since dashboards can be large."""
    result = {
        "session_id": session["id"],
        "url_path": session["url_path"],
        "request": session["request"],
        "status": session["status"],
        "created_at": session["created_at"],
        "updated_at": session["updated_at"],
        "expires_at": session["expires_at"],
        "notes": session["notes"],
        "applied": session["applied"],
        "drafts": [
            {key: draft[key] for key in ("id", "summary", "by", "at")}
            | ({"config": draft["config"]} if with_configs else {})
            for draft in session["drafts"]
        ],
    }
    if with_configs:
        result["baseline"] = session["baseline"]
    return result


def agent_prompt(session, mcp_url, home_name):
    """What a person hands their agent: where to connect and which session, never a token or the
    dashboard itself (the agent reads both over its own signed-in MCP connection)."""
    return "\n".join(
        [
            f"Edit a Home Assistant dashboard in '{home_name}' for harc (Terrazzo).",
            "",
            f"Connect to the Terrazzo MCP server at {mcp_url} (sign in with Home Assistant when "
            "asked), then:",
            f"1. get_edit_session with session_id {session['id']}: the request, the dashboard as "
            "it is now, and any notes.",
            "2. get_card_guide for the card types harc draws and how to size them; find_entities "
            "for the home's entities.",
            "3. submit_draft with the complete new dashboard config and a one-line summary. You "
            "can submit again after notes; the person previews each draft and applies it.",
            "",
            f"Request: {session['request'] or '(none given; improve the dashboard)'}",
        ]
    )
