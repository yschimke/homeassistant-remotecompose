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
MAX_MESSAGES = 50
MAX_STATUS = 120
MAX_TEXT = 2000
MAX_DRAFT_BYTES = 512 * 1024
MAX_CLOUD_TOKEN = 512
MAX_CLOUD_SERVER = 200
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


def clean_cloud_server(server):
    """A design server's base URL: HTTPS (plain HTTP only for this machine), with no credentials,
    query or fragment. Home Assistant calls only its fixed relay paths under it."""
    if not isinstance(server, str):
        raise SessionError("The design server must be a URL")
    server = server.strip().rstrip("/")
    scheme, sep, rest = server.partition("://")
    authority = rest.split("/", 1)[0]
    host = authority.split(":", 1)[0]
    if (
        not sep
        or len(server) > MAX_CLOUD_SERVER
        or not host
        or any(char in rest for char in "?#@\\ ")
        or not (scheme == "https" or (scheme == "http" and host in ("localhost", "127.0.0.1")))
    ):
        raise SessionError("The design server must be an https:// URL")
    return server


def _token(value):
    if (
        not isinstance(value, str)
        or not value.strip()
        or len(value) > MAX_CLOUD_TOKEN
        or any(ord(char) <= 32 or ord(char) >= 127 for char in value)
    ):
        raise SessionError("Invalid design server capability")
    return value


def cloud_for(session, user_id):
    """The cloud link as [user_id] may see it: whole for the session's starter, else nothing.
    The owner capability is never in what an agent reads (`summary`)."""
    cloud = session.get("cloud")
    if not cloud or session["created_by"] != user_id:
        return None
    return {"server": cloud["server"], "owner": cloud["owner"]}


DASHBOARD = "dashboard"
SECTION = "section"
MAX_SCOPE_LABEL = 200


def _section_of(config, view, section):
    """Section [section] of view [view] in a dashboard config, or None when there's none."""
    views = config.get("views") if isinstance(config, dict) else None
    if not isinstance(views, list) or not 0 <= view < len(views) or not isinstance(views[view], dict):
        return None
    sections = views[view].get("sections")
    if not isinstance(sections, list) or not 0 <= section < len(sections):
        return None
    return sections[section]


def _without_section(config, view, section):
    """[config] with one section blanked out: what a section-scoped draft must leave as it was."""
    rest = copy.deepcopy(config)
    rest["views"][view]["sections"][section] = None
    return rest


def validate_scope(scope, baseline):
    """A session's scope: the whole dashboard (None or kind "dashboard"), or one section of it
    ({"kind": "section", "view": i, "section": j, "label": ...}) that the dashboard has."""
    if scope is None:
        return None
    if not isinstance(scope, dict) or scope.get("kind") not in (DASHBOARD, SECTION):
        raise SessionError("A scope is a dashboard or one of its sections")
    label = scope.get("label")
    if label is not None and not isinstance(label, str):
        raise SessionError("A scope's label must be text")
    result = {"kind": scope["kind"]}
    if label:
        result["label"] = label.strip()[:MAX_SCOPE_LABEL]
    if scope["kind"] == SECTION:
        view, section = scope.get("view"), scope.get("section")
        if (not isinstance(view, int) or not isinstance(section, int)
                or _section_of(baseline, view, section) is None):
            raise SessionError("This dashboard has no such section")
        result["view"], result["section"] = view, section
    return result


def check_in_scope(scope, baseline, config):
    """Refuse a section-scoped draft that changes anything outside its section."""
    if not scope or scope.get("kind") != SECTION:
        return
    view, section = scope["view"], scope["section"]
    if _section_of(config, view, section) is None or (
        _without_section(config, view, section) != _without_section(baseline, view, section)
    ):
        raise SessionError(
            f"This session is about {scope.get('label') or 'one section'}: change only that "
            f"section (views[{view}].sections[{section}]) and keep the rest of the dashboard as it is"
        )


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

    def create(self, url_path, request, baseline, user_id, now, scope=None):
        scope = validate_scope(scope, baseline)
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
            # The conversation: notes (the person's) and messages (the agent's) share one sequence,
            # so an agent waiting in await_feedback can say which notes it has already read.
            "seq": 0,
            "messages": [],
            "agent": None,
            "scope": scope,
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

    def for_dashboard(self, url_path, now, include_closed=False):
        """The open sessions on [url_path], newest first; with [include_closed] also the applied and
        discarded ones still kept (a day after their last change): the recent ones."""
        self.prune(now)
        return sorted(
            (s for s in self._sessions.values()
             if s["url_path"] == url_path and (include_closed or s["status"] == OPEN)),
            key=lambda s: s["updated_at"],
            reverse=True,
        )

    def for_dashboards(self, visible_paths, now):
        """Open sessions on visible dashboards, across the home, newest first."""
        self.prune(now)
        return sorted(
            (s for s in self._sessions.values()
             if s["url_path"] in visible_paths and s["status"] == OPEN),
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

    def submit(self, session_id, config, summary, entity_exists, user_id, now, relay_id=None):
        """Add a draft to an open session; the newest [MAX_DRAFTS] are kept."""
        session = self._open(session_id, now)
        validate_draft(config, entity_exists)
        check_in_scope(session.get("scope"), session["baseline"], config)
        summary = _text(summary, "The summary")
        if relay_id is not None:
            if not isinstance(relay_id, str) or not relay_id or len(relay_id) > 100:
                raise SessionError("Invalid relay id")
            previous = next((d for d in session["drafts"]
                             if d.get("relay_id") == relay_id and d["by"] == user_id), None)
            if previous is not None:
                if previous["config"] != config or previous["summary"] != summary:
                    raise SessionError("This relay id already names another draft")
                return previous
        draft = {
            "id": secrets.token_urlsafe(6),
            "config": copy.deepcopy(config),
            "summary": summary,
            "by": user_id,
            "at": now,
        }
        if relay_id is not None:
            draft["relay_id"] = relay_id
        session["drafts"] = (session["drafts"] + [draft])[-MAX_DRAFTS:]
        self._touch(session, now)
        return draft

    @staticmethod
    def _next_seq(session):
        session["seq"] = session.get("seq", 0) + 1
        return session["seq"]

    def note(self, session_id, text, user_id, now, target=None):
        """A note for the agent ("request changes"), optionally about one card ([target]); an
        agent waiting in await_feedback gets it at once."""
        session = self._open(session_id, now)
        note = {"text": _text(text, "The note", required=True), "by": user_id, "at": now}
        if target is not None:
            target = _text(target, "The card")[:200]
            if target:
                note["target"] = target
        note["seq"] = self._next_seq(session)
        session["notes"] = (session["notes"] + [note])[-MAX_NOTES:]
        self._touch(session, now)
        return note

    def agent_message(self, session_id, text, user_id, now):
        """Something the agent says to the person: a question, or what it changed."""
        session = self._open(session_id, now)
        message = {"text": _text(text, "The message", required=True), "by": user_id, "at": now}
        message["seq"] = self._next_seq(session)
        session["messages"] = (session.get("messages", []) + [message])[-MAX_MESSAGES:]
        self._touch(session, now)
        return message

    def agent_seen(self, session_id, status, user_id, now):
        """The agent is active, and what it's doing in a few words; the person sees it live."""
        session = self.get(session_id, now)
        previous = session.get("agent") or {}
        session["agent"] = {
            "by": user_id,
            "seen_at": now,
            "status": _text(status, "The status")[:MAX_STATUS] if status is not None
            else previous.get("status", ""),
        }
        self._touch(session, now)
        return session

    @staticmethod
    def feedback(session, since):
        """The person's notes after [since] (a cursor from an earlier call), and the cursor now."""
        notes = [n for n in session["notes"] if n.get("seq", 0) > since]
        return notes, session.get("seq", 0)

    def discard(self, session_id, user_id, now):
        session = self._open(session_id, now)
        session["status"] = DISCARDED
        session["discarded"] = {"by": user_id, "at": now}
        self._touch(session, now)
        return session

    def link_cloud(self, session_id, server, owner, user_id, now):
        """Keep the design server request a cloud agent works on ([server] and its owner
        capability [owner]) with the session, so any of its starter's clients can pick it up and
        Home Assistant can relay its drafts. Only the person who started the session may."""
        session = self._open(session_id, now)
        if session["created_by"] != user_id:
            raise SessionError("Only the person who started this session can link a cloud agent")
        session["cloud"] = {
            "server": clean_cloud_server(server),
            "owner": _token(owner),
            # The newest relay revision already in the session's drafts.
            "revision": (session.get("cloud") or {}).get("revision", 0),
        }
        self._touch(session, now)
        return session

    def cloud_relayed(self, session_id, revision, now):
        """The relay's [revision] is in the session now (or was refused): don't fetch it again."""
        session = self.get(session_id, now)
        cloud = session.get("cloud")
        if cloud is not None and revision > cloud.get("revision", 0):
            cloud["revision"] = revision
        return session

    def unlink_cloud(self, session_id, now):
        """The design server ended the request: forget its capability."""
        session = self.get(session_id, now)
        session.pop("cloud", None)
        return session

    def cloud_linked(self, now):
        """Open sessions with a cloud agent's request linked."""
        self.prune(now)
        return [s for s in self._sessions.values() if s["status"] == OPEN and s.get("cloud")]

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
        "messages": session.get("messages", []),
        "agent": session.get("agent"),
        "seq": session.get("seq", 0),
        "applied": session["applied"],
        "scope": session.get("scope"),
        "drafts": [
            {key: draft[key] for key in ("id", "summary", "by", "at")}
            | ({"relay_id": draft["relay_id"]} if draft.get("relay_id") else {})
            | ({"config": draft["config"]} if with_configs else {})
            for draft in session["drafts"]
        ],
    }
    if with_configs:
        result["baseline"] = session["baseline"]
    return result


def waiting_notes(session):
    """The person's notes since the newest draft: what the agent hasn't acted on yet, including
    any left before it joined."""
    last_draft = session["drafts"][-1]["at"] if session["drafts"] else None
    return [n for n in session["notes"] if last_draft is None or n["at"] > last_draft]


def next_step(session):
    """What an agent reading [session] does now: act on notes that are waiting (a person can
    comment before any agent joins), else say hello and wait for them."""
    if session["status"] != OPEN:
        return f"This session was {session['status']}: nothing to do."
    seq = session.get("seq", 0)
    if waiting_notes(session):
        return (
            "The person has already left notes (notes) since the last draft: act on them now and "
            f"submit_draft, then await_feedback with since {seq}."
        )
    if session["drafts"]:
        return f"await_feedback with since {seq} for what the person thinks of the draft."
    return (
        "Say hello with send_message, then await_feedback with since "
        f"{seq} for the person's comments on the preview."
    )


TERRAZZO_APP_ID = "ee.schimke.harc"


def notice_services(registrations, user_id, slugify):
    """The `notify` services of [user_id]'s Terrazzo devices among mobile_app [registrations]
    (each a config entry's data): where to tell a person their agent has a draft ready."""
    return sorted(
        {
            f"mobile_app_{slugify(data['device_name'])}"
            for data in registrations
            if data.get("app_id") == TERRAZZO_APP_ID
            and data.get("user_id") == user_id
            and data.get("device_name")
        }
    )


def draft_notice(session, draft):
    """What the person's phone says when their agent's draft is ready and they aren't watching."""
    return {
        "title": "Your agent has a draft",
        "message": draft.get("summary") or session.get("request") or "A dashboard draft is ready",
        # One notice per session: a newer draft replaces it.
        "data": {"tag": f"terrazzo-edit-{session['id']}"},
    }


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
            "it is now, and any notes. The person may have commented before you joined: act on "
            "those notes first (its next says so).",
            "2. get_card_guide for the card types harc draws and how to size them; find_entities "
            "for the home's entities.",
            "3. submit_draft with the complete new dashboard config and a one-line summary; "
            "set_status while you work and send_message to ask the person something.",
            "4. await_feedback in a loop (it waits up to 25 s), passing back its cursor: the "
            "person watches each draft in the app and comments, sometimes on one card. Act on "
            "each note and submit again until the session is applied or discarded.",
            "",
            *_scope_lines(session.get("scope")),
            f"Request: {session['request'] or '(none yet: say hello, then follow the notes)'}",
        ]
    )


def _scope_lines(scope):
    if not scope or scope.get("kind") != SECTION:
        return []
    return [
        f"Scope: only {scope.get('label') or 'one section'} "
        f"(views[{scope['view']}].sections[{scope['section']}]); keep the rest of the dashboard "
        "exactly as it is, or the draft is refused.",
    ]
