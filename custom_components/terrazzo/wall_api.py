"""The wall display API's rules, as plain data in and out: no Home Assistant imports, so they are
tested without it (`home-assistant/test/wall_api_test.py`). `wall.py` does the I/O.

A wall display (an ESPHome panel, an e-paper frame, a kiosk tablet) asks four things, and each
answer is shaped so the device parses as little as possible:

- which dashboards there are ([dashboard_list]);
- one dashboard as a primitive grid: views, sections, cards with grid boxes and ids ([layout]);
- each card's design (`.rc`, content-addressed by sha256) and named values, then only the values
  that changed, by cursor ([ValueStore]);
- what a tap does: the device sends back the `ha` host action the document fired, and the server
  picks which of the card's own actions runs ([resolve_tap], [service_call]).

Design: `docs/architecture/wall-display-api.md`.
"""
import hashlib
import json
import re

API = "/api/terrazzo/wall/v1"
# Lovelace's sections grid: 12 columns, a row is 56 px plus an 8 px gap.
GRID_COLUMNS = 12
ROW_HEIGHT = 56
ROW_GAP = 8
# How long a long poll waits for a change by default, and at most.
DEFAULT_WAIT = 25
MAX_WAIT = 55
# The url_path Lovelace's default dashboard is reached at.
DEFAULT_DASHBOARD = "lovelace"
# Card ids: `v<view>.c<card>` (a masonry or panel view) or `v<view>.s<section>.c<card>`.
CARD_ID = re.compile(r"^v(\d{1,4})(?:\.s(\d{1,4}))?\.c(\d{1,4})$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
# A player profile's name, as the design server knows them (`kobento-mcu`, `widgets-v6`).
PROFILE_NAME = re.compile(r"^[a-z0-9][a-z0-9-]{0,39}$")

# Cards whose size HA's sections view gives as a default (`getGridOptions`), in grid units.
# Anything else is full width; its height is estimated and marked `auto`.
DEFAULT_SIZES = {
    "tile": (6, 1),
    "button": (3, 2),
    "entity": (6, 2),
    "sensor": (6, 2),
    "gauge": (6, 4),
    "light": (6, 4),
    "thermostat": (6, 6),
    "humidifier": (6, 6),
    "clock": (6, 2),
    "heading": (12, 1),
}


class WallError(Exception):
    """A request the API answers with [status] and [message]."""

    def __init__(self, status, message):
        super().__init__(message)
        self.status = status
        self.message = message


def _text(value):
    return value if isinstance(value, str) and value else None


def has_cards(config):
    """Whether [config], a dashboard's loaded Lovelace config, has saved views a wall can lay out.
    A strategy dashboard (Overview until someone takes control of it) has none: [_views] refuses it."""
    return isinstance(config, dict) and not ("strategy" in config and not config.get("views"))


def dashboard_list(visible, default_url_path=""):
    """[visible] (url_path, None for Overview → Lovelace's dashboard config) as the device lists
    them, leaving out the ones without saved cards before they get here. [default_url_path] is the
    integration's chosen wall dashboard (`terrazzo: dashboard:`); when it isn't listed, the first
    dashboard is the default, so a device always has one to open."""
    chosen = default_url_path or DEFAULT_DASHBOARD
    out = []
    for url_path, config in visible.items():
        path = url_path or DEFAULT_DASHBOARD
        config = config if isinstance(config, dict) else {}
        out.append(
            {
                "url_path": path,
                "title": _text(config.get("title")) or ("Overview" if url_path is None else path),
                "icon": _text(config.get("icon")),
                "mode": _text(config.get("mode")) or "storage",
                "default": path == chosen,
                "layout": f"{API}/dashboards/{path}",
            }
        )
    out.sort(key=lambda d: (not d["default"], d["url_path"] != DEFAULT_DASHBOARD, d["title"]))
    if out and not out[0]["default"]:
        out[0]["default"] = True
    return out


def config_etag(config):
    """A short, stable tag for a dashboard's config: the device refetches the layout when it moves."""
    raw = json.dumps(config, sort_keys=True, separators=(",", ":"), default=str)
    return hashlib.sha256(raw.encode()).hexdigest()[:16]


def card_id(view, card, section=None):
    return f"v{view}.c{card}" if section is None else f"v{view}.s{section}.c{card}"


# Terrazzo's own Lovelace card wraps the card it draws in `card:`; a wall display draws that card.
WRAPPER_CARD = "custom:remote-compose-card"


def unwrap(card):
    """[card], or the card a Remote Compose card wraps."""
    while isinstance(card, dict) and card.get("type") == WRAPPER_CARD and isinstance(card.get("card"), dict):
        card = card["card"]
    return card


CONDITIONAL_CARD = "conditional"
_CONDITION_KEYS = {"condition", "entity", "entity_id", "state", "state_not", "above", "below"}


def _condition_entities(conditions):
    """Every entity id the [conditions] list reads, nested and/or/not included."""
    out = []
    for condition in conditions if isinstance(conditions, list) else []:
        if not isinstance(condition, dict):
            continue
        entity = _text(condition.get("entity")) or _text(condition.get("entity_id"))
        if entity is not None and entity not in out:
            out.append(entity)
        # A numeric threshold may be another entity's state (`above: input_number.limit`).
        for key in ("above", "below"):
            raw = condition.get(key)
            if isinstance(raw, str) and _finite(raw) is None and raw not in out:
                out.append(raw)
        for nested in _condition_entities(condition.get("conditions")):
            if nested not in out:
                out.append(nested)
    return out


def watched_entities(card):
    """The entity ids whose change can change what [card] shows: [entity_ids], and for a
    `conditional` its conditions' too, with its inner card's."""
    if isinstance(card, dict) and isinstance(card.get("cards"), list):
        out = []
        for child in card["cards"]:
            for entity in watched_entities(unwrap(child)):
                if entity not in out:
                    out.append(entity)
        return out
    if not isinstance(card, dict) or card.get("type") != CONDITIONAL_CARD:
        return entity_ids(card)
    out = _condition_entities(card.get("conditions"))
    for entity in watched_entities(card.get("card")):
        if entity not in out:
            out.append(entity)
    return out


def conditions_met(conditions, state_of):
    """A port of the app's `dashboardConditions`: True, False, or None when a condition needs
    Home Assistant's own evaluator (never a reason to show the card). [state_of] maps an entity
    id to its state string, or None when missing or unreadable."""
    if not isinstance(conditions, list):
        return None
    results = []
    for condition in conditions:
        if not isinstance(condition, dict):
            return None
        results.append(_condition_met(condition, state_of))
    if False in results:
        return False
    return None if None in results else True


def _condition_met(condition, state_of):
    kind = _text(condition.get("condition"))
    if kind is None:
        kind = "numeric_state" if "above" in condition or "below" in condition else "state"
    if kind in ("and", "or", "not"):
        if any(key not in ("condition", "conditions") for key in condition):
            return None
        children = condition.get("conditions")
        if not isinstance(children, list):
            return None
        if kind != "or":
            result = conditions_met(children, state_of)
            return result if kind == "and" or result is None else not result
        results = []
        for child in children:
            if not isinstance(child, dict):
                return None
            results.append(_condition_met(child, state_of))
        if True in results:
            return True
        return None if None in results else False
    if any(key not in _CONDITION_KEYS for key in condition):
        return None
    entity = _text(condition.get("entity")) or _text(condition.get("entity_id"))
    if entity is None:
        return None
    state = state_of(entity)
    if state is None:
        return False

    def matches(expected):
        if isinstance(expected, list):
            if not all(isinstance(item, (str, int, float)) for item in expected):
                return None
            return any(state == str(item) for item in expected)
        if isinstance(expected, (str, int, float)) and not isinstance(expected, bool):
            return state == str(expected)
        return None

    if kind == "state":
        if "state" not in condition and "state_not" not in condition:
            return None
        equal = unequal = True
        if "state" in condition:
            equal = matches(condition["state"])
            if equal is None:
                return None
        if "state_not" in condition:
            unequal = matches(condition["state_not"])
            if unequal is None:
                return None
            unequal = not unequal
        return equal and unequal
    if kind == "numeric_state":
        if "above" not in condition and "below" not in condition:
            return None
        value = _finite(state)
        if value is None:
            return False

        def threshold(key):
            raw = condition.get(key)
            number = _finite(raw) if isinstance(raw, (int, float, str)) else None
            if number is None and isinstance(raw, str):
                number = _finite(state_of(raw))
            return number

        above = below = None
        if "above" in condition:
            above = threshold("above")
            if above is None:
                return False
        if "below" in condition:
            below = threshold("below")
            if below is None:
                return False
        return (above is None or value > above) and (below is None or value < below)
    return None


def _finite(text):
    try:
        number = float(text)
    except (TypeError, ValueError):
        return None
    return number if number == number and number not in (float("inf"), float("-inf")) else None


def shown_card(card, state_of):
    """The card a wall display draws for [card]: itself, or through `conditional` wrappers the
    inner card whose conditions hold; None when they don't (the card is hidden). A grid or stack
    shows its shown children, and is hidden with none. Raises WallError(422) for a condition only
    Home Assistant can evaluate; a child with one is left out."""
    while isinstance(card, dict) and card.get("type") == CONDITIONAL_CARD:
        met = conditions_met(card.get("conditions"), state_of)
        if met is None:
            raise WallError(422, "This conditional card uses a condition a wall display can't evaluate.")
        inner = card.get("card")
        if not met or not isinstance(inner, dict):
            return None
        card = unwrap(inner)
    if isinstance(card, dict) and isinstance(card.get("cards"), list):
        children = []
        for child in card["cards"]:
            try:
                shown = shown_card(unwrap(child), state_of) if isinstance(child, dict) else None
            except WallError:
                shown = None
            if shown is not None:
                children.append(shown)
        if not children:
            return None
        if children != card["cards"]:
            card = {**card, "cards": children}
    return card


def find_card(config, cid):
    """The card config [cid] names in a dashboard's [config]: a card, or a screen's vertical stack
    ([screen_card]). Raises WallError(404)."""
    screen = screen_card(config, cid)
    if screen is not None:
        return screen
    match = CARD_ID.match(cid or "")
    if not match:
        raise WallError(404, f"Not a card id: {cid}")
    view_index, section_index, card_index = (
        int(match.group(1)),
        None if match.group(2) is None else int(match.group(2)),
        int(match.group(3)),
    )
    try:
        view = _views(config)[view_index]
        holder = view if section_index is None else view["sections"][section_index]
        card = holder["cards"][card_index]
    except (IndexError, KeyError, TypeError):
        raise WallError(404, f"No card {cid}") from None
    if not isinstance(card, dict):
        raise WallError(404, f"No card {cid}")
    return unwrap(card)


def _views(config):
    if not isinstance(config, dict):
        return []
    if not has_cards(config):
        raise WallError(
            422,
            "This dashboard is generated by a strategy; take control of it in Home Assistant "
            "(Edit dashboard → ⋮ → Take control) to show it on a wall display.",
        )
    views = config.get("views")
    return views if isinstance(views, list) else []


# ---------------------------------------------------------------------------------------------
# Slots: which entities a card binds, in order. A port of `CardDesignSlots.slotEntries`, the same
# rule embed/ha-card.js follows, so a template's `<domain>.s<N>` names the same entity everywhere.
# ---------------------------------------------------------------------------------------------


def slot_entries(card):
    """The entities [card] binds, in slot order: (entity_id, the config it came from, or None).
    None when the card binds none."""
    if not isinstance(card, dict):
        return None
    entity = _text(card.get("entity"))
    if entity is not None:
        return [(entity, card)]
    if isinstance(card.get("cards"), list):
        out = []
        for child in card["cards"]:
            if isinstance(child, dict) and _text(child.get("type")) is not None:
                out.extend(slot_entries(child) or [])
        return out
    if isinstance(card.get("elements"), list):
        return [
            (el["entity"], el)
            for el in card["elements"]
            if isinstance(el, dict) and _text(el.get("entity")) is not None
        ]
    if not isinstance(card.get("entities"), list):
        return None
    out = []
    for row in card["entities"]:
        if isinstance(row, dict):
            if _text(row.get("entity")) is not None:
                out.append((row["entity"], row))
        elif _text(row) is not None:
            out.append((row, None))
    return out


def entity_ids(card):
    """The distinct entity ids [card] shows: what its values depend on."""
    seen = []
    for entity, _ in slot_entries(card) or []:
        if entity not in seen:
            seen.append(entity)
    return seen


# Shared values: what every design may read besides its own slots, `HA:<entity_id>.<value>`
# (`HA:sun.sun.elevation`). A port of `HaSharedValues`: its domain and the entities it reads, which
# every resolve sends beside the card's own so those values are the home's, not their daytime stubs.
SHARED_PREFIX = "HA:"
SHARED_ENTITIES = ("sun.sun",)


def reads_shared(values):
    """Whether a card's resolved [values] carry a shared value: its design reads one, so a change
    to a shared entity changes the card."""
    return any(isinstance(name, str) and name.startswith(SHARED_PREFIX) for name in values)


def domain_of(entity_id):
    return entity_id.split(".", 1)[0] if "." in entity_id else ""


def slot_id(domain, index):
    return f"{domain}.s{index}"


# ---------------------------------------------------------------------------------------------
# Layout: a dashboard as a primitive grid.
# ---------------------------------------------------------------------------------------------


def player(density, font_scale, width=None, height=None, profile=None):
    """The small player a `/values` request is for, `(density, font_scale, width, height, profile)`,
    or None for a full one.

    A device that can't evaluate a design's density and font-size expressions (an ESP32 panel)
    passes `density=` (and `font_scale=`, default 1): the design server then makes each design for
    that display, with its sizes as numbers, under its own sha. `width=` and `height=` (dp, both or
    neither) also make each design's canvas the whole screen, for a device showing one card at a
    time. `profile=` names what the player can play (a name the design server knows, such as
    `kobento-mcu`, its default); a name that isn't one is ignored. A value that isn't a positive
    number in range is ignored, so a typo gets the full design, not an error."""

    def number(value, high):
        try:
            v = float(value)
        except (TypeError, ValueError):
            return None
        return v if 0 < v <= high else None

    d, f = number(density, 16), number(font_scale, 16)
    w, h = number(width, 4096), number(height, 4096)
    if d is None and f is None:
        return None
    size = (int(w), int(h)) if w is not None and h is not None else (None, None)
    name = profile if isinstance(profile, str) and PROFILE_NAME.match(profile) else None
    return (d or 1.0, f or 1.0) + size + (name,)


def _int(value, default):
    return value if isinstance(value, int) and not isinstance(value, bool) and value > 0 else default


def card_size(card):
    """(columns, rows, auto) for [card] on the 12-column grid. `auto` means rows is an estimate
    and the document's own height decides."""
    card_type = _text(card.get("type")) or ""
    default_w, default_h = DEFAULT_SIZES.get(card_type, (GRID_COLUMNS, None))
    options = card.get("grid_options") if isinstance(card.get("grid_options"), dict) else {}
    columns = options.get("columns")
    w = GRID_COLUMNS if columns == "full" else _int(columns, default_w)
    w = max(1, min(GRID_COLUMNS, w))
    rows = options.get("rows")
    if isinstance(rows, int) and not isinstance(rows, bool) and rows > 0:
        return w, rows, False
    if default_h is not None:
        return w, default_h, False
    # An estimate. A grid or stack from its children's; else a title row and a row per entity, or
    # two rows.
    estimate = _container_rows(card)
    if estimate is not None:
        return w, estimate, True
    count = len(slot_entries(card) or [])
    return w, max(2, count + (1 if _text(card.get("title")) else 0)), True


def _container_rows(card):
    """The rows a grid or stack's children take, or None for another card."""
    children = [c for c in card.get("cards") or [] if isinstance(c, dict)] if isinstance(card.get("cards"), list) else None
    if not children:
        return None
    heights = [card_size(unwrap(c))[1] for c in children]
    title = 1 if _text(card.get("title")) else 0
    kind = card.get("type")
    if kind == "horizontal-stack":
        return title + max(heights)
    if kind == "grid":
        columns = _int(card.get("columns"), 3)
        rows = [max(heights[i : i + columns]) for i in range(0, len(heights), columns)]
        return title + sum(rows)
    if kind == "vertical-stack":
        return title + sum(heights)
    return None


def pack(sizes, columns=GRID_COLUMNS):
    """Shelf-pack (w, h) boxes left to right, wrapping when a box doesn't fit: their (x, y)."""
    out = []
    x = y = shelf = 0
    for w, h in sizes:
        if x + w > columns:
            x, y, shelf = 0, y + shelf, 0
        out.append((x, y))
        x += w
        shelf = max(shelf, h)
    return out


def _cards_json(url_path, view_index, section_index, cards):
    """[cards] (a view's or section's, as configured) packed on the grid; each id names the card's
    index in [cards] as configured, so a skipped entry never moves another card's id."""
    indexed = [
        (i, unwrap(c)) for i, c in enumerate(cards if isinstance(cards, list) else []) if isinstance(c, dict)
    ]
    sizes = [card_size(c) for _, c in indexed]
    out = []
    for (index, card), (w, h, auto), (x, y) in zip(indexed, sizes, pack([(w, h) for w, h, _ in sizes])):
        cid = card_id(view_index, index, section_index)
        out.append(
            {
                "id": cid,
                "type": _text(card.get("type")) or "",
                "x": x,
                "y": y,
                "w": w,
                "h": h,
                "auto_height": auto,
                "entities": entity_ids(card),
                "card": f"{API}/cards/{url_path}/{cid}",
            }
        )
    return out


def layout(config, url_path):
    """[config] (a dashboard's Lovelace config) as a primitive grid for a wall display."""
    path = url_path or DEFAULT_DASHBOARD
    views = []
    for vi, view in enumerate(_views(config)):
        if not isinstance(view, dict):
            continue
        view_type = _text(view.get("type")) or ("sections" if "sections" in view else "masonry")
        sections = []
        if isinstance(view.get("sections"), list):
            for si, section in enumerate(view["sections"]):
                if not isinstance(section, dict):
                    continue
                span = _int(section.get("column_span"), 1)
                sections.append(
                    {
                        "title": _section_title(section),
                        "column_span": span,
                        "cards": _cards_json(path, vi, si, section.get("cards")),
                    }
                )
        if isinstance(view.get("cards"), list) and view["cards"]:
            # A masonry or panel view: one section, cards full width in order (panel: the first).
            cards = view["cards"][:1] if view_type == "panel" else view["cards"]
            section = {"title": None, "column_span": 1, "cards": _cards_json(path, vi, None, cards)}
            for card in section["cards"]:
                if view_type != "sections":
                    card["x"], card["w"] = 0, GRID_COLUMNS
            y = 0
            for card in section["cards"]:
                if view_type != "sections":
                    card["y"] = y
                    y += card["h"]
            sections.insert(0, section)
        views.append(
            {
                "index": vi,
                "path": _text(view.get("path")) or str(vi),
                "title": _text(view.get("title")) or f"View {vi + 1}",
                "icon": _text(view.get("icon")),
                "type": view_type,
                "max_columns": _int(view.get("max_columns"), 4),
                "sections": sections,
            }
        )
    return {
        "dashboard": path,
        "title": _text(config.get("title")) if isinstance(config, dict) else None,
        "etag": config_etag(config),
        "grid": {"columns": GRID_COLUMNS, "row_height": ROW_HEIGHT, "gap": ROW_GAP},
        "views": views,
        "values": f"{API}/values/{path}",
        "actions": f"{API}/actions/{path}",
    }


def _section_title(section):
    """A section's heading: its own title, or a leading heading card's."""
    if _text(section.get("title")):
        return section["title"]
    cards = section.get("cards") if isinstance(section.get("cards"), list) else []
    first = cards[0] if cards and isinstance(cards[0], dict) else {}
    return _text(first.get("heading")) if first.get("type") == "heading" else None


# ---------------------------------------------------------------------------------------------
# Screens: a panel that shows a view a screen at a time. Each screen is a run of the view's cards,
# in order, drawn as one document: a vertical stack of its shelves (the cards side by side on one
# row of a section, a horizontal stack when there are several).
# ---------------------------------------------------------------------------------------------

# Whether a screen stacks several cards (else each card with entities is a screen of its own), for
# a player too slow to draw a stack of them in time. A screen is filled by its rows;
# SCREEN_ENTITIES only bounds a screen of small cards.
STACK_SCREENS = True
SCREEN_ENTITIES = 12

# `v<view>.p<first>-<last>`, then `x<index>` for each card between them it leaves out, then
# `c<columns>` when its view's sections stand side by side in that many columns.
SCREEN_ID = re.compile(r"^v(\d{1,4})\.p(\d{1,4})-(\d{1,4})((?:x\d{1,4})*)(?:c(\d{1,2}))?$")

# Home Assistant's sections view: a section column is at least this wide, with this gap between
# columns (`--column-min-width`, `--column-gap`), and a view holds at most its `max_columns`.
SECTION_MIN_WIDTH = 320
SECTION_GAP = 32


def section_columns(view, width):
    """How many section columns view [view] (from the layout) has on a screen [width] dp wide, as
    Home Assistant's sections view lays them out: 1 for another view, or no width."""
    if view.get("type") != "sections" or not width:
        return 1
    fit = (int(width) + SECTION_GAP) // (SECTION_MIN_WIDTH + SECTION_GAP)
    return max(1, min(_int(view.get("max_columns"), 4), fit))


def _flow_sections(view, columns):
    """[view]'s sections (from the layout) with each spanning k > 1 of [columns] holding its
    full-width cards k to a row: a wide section's picture beside its tiles, where Home Assistant
    would stack them across a screen meant for more columns. Headings still go across, and cards
    already narrower than the section keep their size. Changes the cards' places in place."""
    for section in view["sections"]:
        k = min(columns, section.get("column_span", 1))
        if k < 2 or not section["cards"]:
            continue
        sizes = [
            (card["w"] if card["w"] < GRID_COLUMNS or card["type"] == "heading" else GRID_COLUMNS // k, card["h"])
            for card in section["cards"]
        ]
        for card, (w, _), (x, y) in zip(section["cards"], sizes, pack(sizes)):
            card["x"], card["y"], card["w"] = x, y, w


def _bands(sections, columns):
    """[sections]' indexes side by side: rows of up to [columns] sections, in order. A section
    spanning more than one column is a band of its own, across the screen."""
    bands, band = [], []
    for si, section in enumerate(sections):
        if not section["cards"]:
            continue
        if columns > 1 and section.get("column_span", 1) > 1:
            if band:
                bands.append(band)
            bands.append([si])
            band = []
            continue
        band.append(si)
        if len(band) == columns:
            bands.append(band)
            band = []
    if band:
        bands.append(band)
    return bands


def _section_shelves(cards, members, skip):
    """The shelves (card indexes on one row) of a section's cards [members] (indexes into
    [cards]) but those in [skip], each with its top row, the rows taken by the shelves above it:
    [(top, height, [index, ...])]."""
    out, top = [], 0
    for shelf in _shelves([cards[i] for i in members]):
        shelf = [members[k] for k in shelf if cards[members[k]]["id"] not in skip]
        if not shelf:
            continue
        height = max(cards[i]["h"] for i in shelf)
        out.append((top, height, shelf))
        top += height
    return out


def _slabs(cards, sections, band, skip):
    """A band's sections, side by side, cut into slabs: the rows between two places a screen may
    end, where no card in any column runs across. Each is ([index, ...] in column order, rows)."""
    columns = [
        _section_shelves(cards, [i for i, card in enumerate(cards) if card["section"] == si], skip)
        for si in band
    ]
    total = max((top + h for col in columns for top, h, _ in col), default=0)
    cuts = [
        r
        for r in range(total + 1)
        if all(not (top < r < top + h) for col in columns for top, h, _ in col)
    ]
    out = []
    for lo, hi in zip(cuts, cuts[1:]):
        members = [i for col in columns for top, h, shelf in col if lo <= top < hi for i in shelf]
        if members:
            out.append((members, hi - lo))
    return out


def _shelves(cards):
    """[cards] (a view's, in order, from the layout) grouped into shelves: runs of indexes in one
    section on one row."""
    out = []
    last = None
    for i, card in enumerate(cards):
        key = (card["id"].rsplit(".c", 1)[0], card["y"])
        if out and key == last:
            out[-1].append(i)
        else:
            out.append([i])
        last = key
    return out


def screen_rows(height):
    """How many grid rows fit a screen [height] dp tall."""
    return max(1, (int(height) + ROW_GAP) // (ROW_HEIGHT + ROW_GAP))


# How far down a step goes, as a share of the screen: the rest of the screen shows again at its
# top, so moving through a view reads as one page. Steps land between rows of cards.
SCROLL_STEP = 0.8
# A step shorter than this share of the screen isn't worth it: the next screen starts clean.
SCROLL_MIN = 0.5


def _windows(cards, run, rows, heights=None, budget=None):
    """[run]'s shelves (each a list of card indexes, a run with no card set apart between) as
    screens: each as many shelves as fit [rows] (and SCREEN_ENTITIES, or [budget]), the next
    starting about SCROLL_STEP of a screen further down, at a shelf, so the last of one screen is
    the top of the next. A heading starts a screen with its cards rather than ending one. A shelf's
    rows are its tallest card's, or [heights] (one each) when it holds cards side by side in
    columns."""
    budget = SCREEN_ENTITIES if budget is None else budget

    def h(shelf):
        if heights is not None:
            return heights[run.index(shelf)]
        return max(cards[i]["h"] for i in shelf)

    def n(shelf):
        return sum(len(cards[i]["entities"]) for i in shelf)

    def heading(shelf):
        return all(cards[i]["type"] == "heading" for i in shelf)

    def fill(start):
        """(end, tops): the screen from shelf [start] is run[start:end]; tops[k] is how far down
        run[start + k] sits."""
        used, entities, end, tops = 0, 0, start, []
        while end < len(run):
            if end > start and (used + h(run[end]) > rows or entities + n(run[end]) > budget):
                break
            tops.append(used)
            used += h(run[end])
            entities += n(run[end])
            end += 1
        # A heading doesn't end a screen with more to come: it starts the next.
        while end - start > 1 and end < len(run) and heading(run[end - 1]):
            end -= 1
            tops.pop()
        return end, tops

    pages, start = [], 0
    while start < len(run):
        end, tops = fill(start)
        pages.append(run[start:end])
        if end >= len(run):
            break
        # The shelf nearest a step down that still lets the next screen show something new. When
        # the rows fall so that a step would be under half a screen (a tall card), a clean page.
        nxt = end
        for k in range(start + 1, end):
            if SCROLL_MIN * rows <= tops[k - start] <= SCROLL_STEP * rows:
                nxt = k
        while nxt < end and fill(nxt)[0] <= end:
            nxt += 1
        if nxt - 1 > start and heading(run[nxt - 1]) and fill(nxt - 1)[0] > end:
            nxt -= 1  # its heading with it
        start = nxt
    # A run's last screen doesn't end on a heading: what it heads is set apart, or there's none.
    if pages:
        while len(pages[-1]) > 1 and heading(pages[-1][-1]):
            pages[-1] = pages[-1][:-1]
    return [[i for shelf in page for i in shelf] for page in pages]


def screens(layout_json, height, apart=frozenset(), skip=frozenset(), width=None):
    """[layout_json] with each view's `screens`: its cards packed, in order, into screens of
    [height] dp that overlap ([_windows]): each step down is about SCROLL_STEP of a screen, to the
    nearest row of cards. A shelf taller than the screen has one to itself; a heading never ends
    a screen with more to come. Card ids in [apart] don't draw inside a stack: each is a screen of
    its own, and the screens around it stop and start at it. Those in [skip] have no design at
    all and are left out (a screen's id names what it leaves out). A screen of headings alone has
    nothing to show, and is left out too.

    A screen [width] dp wide holds a sections view's sections side by side, in as many columns as
    Home Assistant would give it ([section_columns]): a section is laid out for a column, as its
    cards were made for, not stretched across the screen. Its screens then step down rows no card
    runs across in any column, and each holds a column's worth of entities more."""
    rows = screen_rows(height)
    for view in layout_json["views"]:
        columns = section_columns(view, width)
        if columns > 1 and STACK_SCREENS and not any(
            card["id"] in apart for section in view["sections"] for card in section["cards"]
        ):
            _column_screens(view, rows, columns, skip)
            continue
        cards = [card for section in view["sections"] for card in section["cards"]]
        if not STACK_SCREENS:
            # A card a screen; a heading or text with no entities has nothing to show alone.
            view["screens"] = [
                {"id": f"v{view['index']}.p{i}-{i}", "cards": [card["id"]]}
                for i, card in enumerate(cards)
                if card["entities"] and card["id"] not in skip
            ]
            continue
        pages, run = [], []
        for shelf in _shelves(cards):
            shelf = [i for i in shelf if cards[i]["id"] not in skip]
            if not shelf:
                continue
            if any(cards[i]["id"] in apart for i in shelf):
                pages += _windows(cards, run, rows)
                run = []
                # Each card left on the shelf alone: side by side they'd skip the one between.
                pages += [[i] for i in shelf]
                continue
            run.append(shelf)
        pages += _windows(cards, run, rows)
        pages = [p for p in pages if any(cards[i]["type"] != "heading" for i in p)]
        view["screens"] = [
            {
                "id": f"v{view['index']}.p{page[0]}-{page[-1]}"
                + "".join(f"x{i}" for i in range(page[0], page[-1]) if i not in page),
                "cards": [cards[i]["id"] for i in page],
            }
            for page in pages
        ]
    return layout_json


def _column_screens(view, rows, columns, skip):
    """[view]'s screens with its sections in [columns] side by side: bands of sections, cut into
    slabs no card runs across ([_slabs]), windowed as shelves are ([_windows])."""
    _flow_sections(view, columns)
    cards = [
        dict(card, section=si) for si, section in enumerate(view["sections"]) for card in section["cards"]
    ]
    run, heights = [], []
    for band in _bands(view["sections"], columns):
        for members, h in _slabs(cards, view["sections"], band, skip):
            run.append(sorted(members))
            heights.append(h)
    pages = _windows(cards, run, rows, heights, SCREEN_ENTITIES * columns) if run else []
    pages = [sorted(p) for p in pages if any(cards[i]["type"] != "heading" for i in p)]
    view["screens"] = [
        {
            "id": f"v{view['index']}.p{page[0]}-{page[-1]}"
            + "".join(f"x{i}" for i in range(page[0], page[-1]) if i not in page)
            + f"c{columns}",
            "cards": [cards[i]["id"] for i in page],
        }
        for page in pages
    ]


def screen_range(cid):
    """(view, first, last) of screen id [cid], or None."""
    match = SCREEN_ID.match(cid or "")
    return tuple(int(g) for g in match.groups()[:3]) if match else None


def screen_left_out(cid):
    """The indexes screen id [cid] leaves out of its run."""
    match = SCREEN_ID.match(cid or "")
    return {int(i) for i in match.group(4).split("x")[1:]} if match else set()


def screen_size(cid):
    """How many cards screen [cid] holds; 0 for a card id."""
    found = screen_range(cid)
    if not found:
        return 0
    return len([i for i in range(found[1], found[2] + 1) if i not in screen_left_out(cid)])


def screen_cards(layout_json, view, first, last, left_out=frozenset()):
    """[(index, card id)] of view [view]'s cards [first]..[last], in order, but [left_out]."""
    found = [v for v in layout_json["views"] if v["index"] == view]
    cards = [card["id"] for section in found[0]["sections"] for card in section["cards"]] if found else []
    return [(i, c) for i, c in list(enumerate(cards))[first : last + 1] if i not in left_out]


# What stands in a column with nothing on a screen, so the others keep their width: a heading
# with no text draws nothing.
COLUMN_SPACER = {"type": "heading", "heading": ""}


def _stack(config, picked):
    """[picked] (cards from the layout, in order) as their shelves: one card as it is, else a
    vertical stack of shelves, a shelf of several a horizontal stack."""
    rows = []
    for shelf in _shelves(picked):
        configs = [find_card(config, picked[i]["id"]) for i in shelf]
        rows.append(configs[0] if len(configs) == 1 else {"type": "horizontal-stack", "cards": configs})
    return rows[0] if len(rows) == 1 else {"type": "vertical-stack", "cards": rows}


def screen_card(config, cid):
    """The vertical stack screen [cid] (`v<view>.p<first>-<last>`, less any `x<index>`, then any
    `c<columns>`) draws, or None when [cid] isn't a screen id. Raises WallError(404) for one that
    names no cards. With columns, each band of sections side by side is a horizontal stack of
    their columns."""
    match = SCREEN_ID.match(cid or "")
    if not match:
        return None
    view, first, last = screen_range(cid)
    left_out = screen_left_out(cid)
    columns = int(match.group(5) or 1)
    found = [v for v in layout(config, None)["views"] if v["index"] == view]
    cards = [card for section in found[0]["sections"] for card in section["cards"]] if found else []
    if (
        not cards
        or first > last
        or last >= len(cards)
        or any(not first < i < last for i in left_out)
    ):
        raise WallError(404, f"No screen {cid}")
    if columns > 1:
        _flow_sections(found[0], columns)  # the same places the screens were packed with
    picked = [card for i, card in enumerate(cards[first : last + 1], first) if i not in left_out]
    if len(picked) == 1:
        return find_card(config, picked[0]["id"])  # A card on its own: no stack to fit in.
    if columns == 1:
        stack = _stack(config, picked)
        return stack if stack["type"] == "vertical-stack" else {"type": "vertical-stack", "cards": [stack]}
    sections = found[0]["sections"]
    owner = {card["id"]: si for si, section in enumerate(sections) for card in section["cards"]}
    rows = []
    for band in _bands(sections, columns):
        in_band = [[card for card in picked if owner[card["id"]] == si] for si in band]
        if not any(in_band):
            continue
        if len(band) == 1 and sections[band[0]].get("column_span", 1) > 1:
            # A section spanning columns: across the screen.
            stack = _stack(config, in_band[0])
            rows.extend(stack["cards"] if stack["type"] == "vertical-stack" else [stack])
            continue
        # Every column of the band, so each keeps its share of the width, empty ones too (as Home
        # Assistant leaves a short last row of sections its column's width).
        cells = [_stack(config, col) if col else dict(COLUMN_SPACER) for col in in_band]
        cells += [dict(COLUMN_SPACER)] * (columns - len(band))
        rows.append({"type": "horizontal-stack", "cards": cells})
    return rows[0] if len(rows) == 1 and rows[0]["type"] == "vertical-stack" else {"type": "vertical-stack", "cards": rows}


# What a small player's design can draw of a card: only what its template carries. A heading's
# icon and badges, and a markdown card's inline emphasis and links, keep a card off its template
# (the converter draws them by hand); a panel drops them so the card, and a screen holding it, has a
# design. A heading's icon isn't drawn by the converter at all.
_PANEL_KEYS = {
    "heading": {"type", "heading", "title", "heading_style"},
    "markdown": {"type", "title", "content"},
    # The panel fetches the picture at the size it's drawn, cropped to it (/pictures fit=cover):
    # the card's own aspect ratio and camera view don't change what it shows. Its actions are
    # read from the card itself (/actions), never from what the design was made from.
    "picture-entity": {"type", "entity", "name", "icon", "show_name", "show_state"},
}
_INLINE_MARKUP = (
    (re.compile(r"!\[[^\]]*\]\([^)]*\)"), ""),
    (re.compile(r"\[([^\]]*)\]\([^)]*\)"), r"\1"),
    (re.compile(r"\*\*(.+?)\*\*"), r"\1"),
    (re.compile(r"__(.+?)__"), r"\1"),
    (re.compile(r"(?<![*\w])\*(?!\s)([^*\n]+?)(?<!\s)\*(?!\*)"), r"\1"),
    (re.compile(r"(?<![_\w])_(?!\s)([^_\n]+?)(?<!\s)_(?![_\w])"), r"\1"),
    (re.compile(r"`([^`\n]+)`"), r"\1"),
)


# A picture's shape when its card names none (HA's, and the converter's): width over height.
PICTURE_ASPECT = 16 / 9
# How far past its share of the screen a picture may go before it's drawn narrower.
PICTURE_SLACK = 1.15


def picture_max(value):
    """The share of the screen (0-1] one picture may take, from a `picture_max=` percentage, or
    None for no limit."""
    try:
        v = float(value)
    except (TypeError, ValueError):
        return None
    return v / 100 if 0 < v <= 100 else None


def cap_pictures(card, width, max_area):
    """[card] with each picture that would take more than [max_area] (dp²) of the screen, drawn
    [width] dp wide (a stack's children share it, a grid's columns split it), drawn narrower: put
    in a grid of as many columns as it takes, as its first. Its slots are unchanged."""
    if not isinstance(card, dict) or width <= 0:
        return card
    kind = card.get("type")
    if kind == "picture-entity":
        columns = 1
        while columns < 8 and (width / columns) ** 2 / PICTURE_ASPECT > max_area * PICTURE_SLACK:
            columns += 1
        return card if columns == 1 else {"type": "grid", "columns": columns, "square": False, "cards": [card]}
    out = dict(card)
    if isinstance(card.get("card"), dict):
        out["card"] = cap_pictures(card["card"], width, max_area)
    if isinstance(card.get("cards"), list):
        if kind == "horizontal-stack":
            share = width / max(1, len(card["cards"]))
        elif kind == "grid":
            share = width / max(1, _int(card.get("columns"), 3))
        else:
            share = width
        out["cards"] = [cap_pictures(c, share, max_area) for c in card["cards"]]
    return out


def panel_card(card, player=None, max_share=None):
    """[card] as a small player draws it ([_PANEL_KEYS], markdown without inline markup), its
    children too, and with [player]'s screen (`(density, font_scale, width, height)`) its pictures
    each take at most [max_share] of it ([cap_pictures]). Its slots are unchanged."""
    if player is not None and max_share is not None and player[2] is not None:
        return cap_pictures(panel_card(card), player[2], max_share * player[2] * player[3])
    if not isinstance(card, dict):
        return card
    kind = card.get("type")
    out = dict(card)
    if kind == "picture-entity" and card.get("camera_image") not in (None, card.get("entity")):
        pass  # Another entity's picture: no design draws that, so the card stays as it is.
    elif kind in _PANEL_KEYS:
        out = {k: v for k, v in card.items() if k in _PANEL_KEYS[kind]}
        if kind == "markdown" and isinstance(out.get("content"), str):
            content = out["content"]
            for pattern, replacement in _INLINE_MARKUP:
                content = pattern.sub(replacement, content)
            out["content"] = content
    if isinstance(card.get("cards"), list):
        out["cards"] = [panel_card(c) for c in card["cards"]]
    if isinstance(card.get("card"), dict):
        out["card"] = panel_card(card["card"])
    return out


def view_card_ids(layout_json, view=None):
    """Every card id in a [layout] (or just view [view]'s)."""
    return [
        card["id"]
        for v in layout_json["views"]
        if view is None or v["index"] == view
        for section in v["sections"]
        for card in section["cards"]
    ]


# ---------------------------------------------------------------------------------------------
# Actions: a template only picks which of the card's own actions runs (as embed/ha-card.js's
# resolveTap and the app's CardTemplatePlayback.resolveTap).
# ---------------------------------------------------------------------------------------------

HA_ACTION_TYPE = "ee.schimke.ha.rc.components.HaAction."
TOGGLE_DEFAULT = {"light", "switch", "input_boolean", "fan", "cover", "media_player", "lock"}
SLOT_ENTITY = re.compile(r"^[a-z0-9_]+\.s(\d+)$")
NONE = {"kind": "None"}


def decode_template_action(value):
    """An `ha` host action's JSON payload (kotlinx polymorphic `HaAction`), or None."""
    if not isinstance(value, str):
        return None
    try:
        action = json.loads(value)
    except ValueError:
        return None
    if not isinstance(action, dict) or not isinstance(action.get("type"), str):
        return None
    if not action["type"].startswith(HA_ACTION_TYPE):
        return None
    return {**action, "kind": action["type"][len(HA_ACTION_TYPE):]}


def parse_lovelace_action(cfg, default_entity):
    """A Lovelace `tap_action`; None when there is none, NONE when it is `none` or broken."""
    if not isinstance(cfg, dict):
        return None
    kind = cfg.get("action")
    if kind in ("toggle", "more-info"):
        entity = _text(cfg.get("entity")) or default_entity
        if not entity:
            return NONE
        return {"kind": "Toggle" if kind == "toggle" else "MoreInfo", "entity_id": entity}
    if kind == "navigate":
        path = _text(cfg.get("navigation_path"))
        return {"kind": "Navigate", "path": path} if path else NONE
    if kind == "url":
        url = _text(cfg.get("url_path"))
        return {"kind": "Url", "url": url} if url else NONE
    if kind in ("call-service", "perform-action"):
        svc = _text(cfg.get("perform_action")) or _text(cfg.get("service"))
        if not svc or "." not in svc:
            return NONE
        data = cfg.get("data") if isinstance(cfg.get("data"), dict) else None
        if data is None:
            data = cfg.get("service_data") if isinstance(cfg.get("service_data"), dict) else {}
        target = cfg.get("target") if isinstance(cfg.get("target"), dict) else None
        domain, service = svc.split(".", 1)
        return {
            "kind": "CallService",
            "domain": domain,
            "service": service,
            "data": dict(data),
            "target": dict(target) if target else None,
            "entity_id": (_text(target.get("entity_id")) if target else None)
            or _text(data.get("entity_id"))
            or default_entity,
        }
    return NONE


def default_tap_action(entity):
    if not entity:
        return NONE
    kind = "Toggle" if domain_of(entity) in TOGGLE_DEFAULT else "MoreInfo"
    return {"kind": kind, "entity_id": entity}


def resolve_tap(card, value=None):
    """The card-local action a tap runs. [value] is the `ha` host action's string the document
    fired, or None for a tap on the card itself. Only a slot it names is taken from it: never its
    kind, service or data."""
    entries = slot_entries(card) or []
    first = entries[0][0] if entries else None

    def card_action():
        return parse_lovelace_action(card.get("tap_action"), first) or default_tap_action(first)

    template = decode_template_action(value)
    slot = template.get("entityId") if template else None
    match = SLOT_ENTITY.match(slot) if isinstance(slot, str) else None
    if not match:
        return card_action()
    index = int(match.group(1))
    if index >= len(entries) or slot_id(domain_of(entries[index][0]), index) != slot:
        return card_action()
    real, config = entries[index]
    configured = parse_lovelace_action(config.get("tap_action"), real) if config else None
    return configured or default_tap_action(real)


def service_call(action):
    """(domain, service, data) for a resolved [action], or None when the device handles it
    itself (more-info, navigate, url) or it does nothing."""
    kind = action.get("kind")
    if kind == "Toggle":
        return "homeassistant", "toggle", {"entity_id": action["entity_id"]}
    if kind == "CallService":
        data = dict(action.get("data") or {})
        target = action.get("target")
        if target:
            data.update(target)
        elif action.get("entity_id") and "entity_id" not in data:
            data["entity_id"] = action["entity_id"]
        return action["domain"], action["service"], data
    return None


# ---------------------------------------------------------------------------------------------
# Values: each card's design and named values, and what changed after a cursor.
# ---------------------------------------------------------------------------------------------


class ValueStore:
    """Every card's latest design and values for one (dashboard, look), each value stamped with
    the cursor it changed at, so a poll answers only what is newer than the cursor it sends.

    A cursor is opaque to the device: it sends back what it got. A store made after a restart or a
    dashboard edit has a new [epoch]; a cursor from another epoch is answered in full.
    """

    def __init__(self, epoch):
        self.epoch = epoch
        self.seq = 0
        # card id → {"design": sha, "rc": url, "exact": bool, "at": seq of the design,
        #            "values": {name: (value, seq)}, "error": str or None}
        self.cards = {}
        # view index → (its screen ids, the seq they were set at)
        self.screens = {}

    def cursor(self):
        return f"{self.epoch}-{self.seq}"

    def is_current(self, cursor):
        """Whether [cursor] came from this store (this epoch)."""
        return isinstance(cursor, str) and cursor.rpartition("-")[0] == str(self.epoch)

    def since(self, cursor):
        """The seq [cursor] names in this epoch; 0 (everything) for another epoch or nonsense."""
        if not isinstance(cursor, str) or "-" not in cursor:
            return 0
        epoch, _, seq = cursor.rpartition("-")
        if epoch != str(self.epoch) or not seq.isdigit():
            return 0
        return min(int(seq), self.seq)

    def update(self, cid, design, rc, exact, values):
        """Record [cid]'s resolved design and values. True when anything changed."""
        entry = self.cards.get(cid)
        if (
            entry is not None
            and entry["design"] == design
            and entry["error"] is None
            and not entry.get("hidden")
        ):
            changed = {
                name: value
                for name, value in values.items()
                if name not in entry["values"] or not _same(entry["values"][name][0], value)
            }
            if not changed:
                return False
            self.seq += 1
            for name, value in changed.items():
                entry["values"][name] = (value, self.seq)
            return True
        self.seq += 1
        self.cards[cid] = {
            "design": design,
            "rc": rc,
            "exact": exact,
            "at": self.seq,
            "values": {name: (value, self.seq) for name, value in values.items()},
            "error": None,
        }
        return True

    def set_screens(self, view, ids):
        """Record view [view]'s screen ids. True when they changed."""
        old = self.screens.get(view)
        if old is not None and old[0] == list(ids):
            return False
        self.seq += 1
        self.screens[view] = (list(ids), self.seq)
        return True

    def screens_since(self, view, since):
        """View [view]'s screen ids when they changed after [since], else None."""
        found = self.screens.get(view)
        return list(found[0]) if found is not None and found[1] > since else None

    def hide(self, cid):
        """Record that [cid]'s `conditional` wrapper hides it now. True when that's news."""
        entry = self.cards.get(cid)
        if entry is not None and entry.get("hidden"):
            return False
        self.seq += 1
        self.cards[cid] = {
            "design": None,
            "rc": None,
            "exact": False,
            "at": self.seq,
            "values": {},
            "error": None,
            "hidden": True,
        }
        return True

    def fail(self, cid, message):
        entry = self.cards.get(cid)
        if entry is not None and entry["error"] == message:
            return False
        self.seq += 1
        self.cards[cid] = {
            "design": None,
            "rc": None,
            "exact": False,
            "at": self.seq,
            "values": {},
            "error": message,
        }
        return True

    def changes(self, card_ids, since):
        """{card id: its change} for [card_ids] with anything newer than [since]. A card whose
        design is newer comes whole (`design`, `rc`, every value); else only its newer values."""
        out = {}
        for cid in card_ids:
            entry = self.cards.get(cid)
            if entry is None:
                continue
            if entry["at"] > since:
                if entry["error"] is not None:
                    out[cid] = {"error": entry["error"]}
                    continue
                if entry.get("hidden"):
                    out[cid] = {"hidden": True}
                    continue
                out[cid] = {
                    "design": entry["design"],
                    "rc": entry["rc"],
                    "exact": entry["exact"],
                    "values": {name: v for name, (v, _) in entry["values"].items()},
                }
                continue
            newer = {name: v for name, (v, at) in entry["values"].items() if at > since}
            if newer:
                out[cid] = {"values": newer}
        return out


def _same(a, b):
    # 1 and 1.0, True and 1 differ to a player's typed slots.
    return type(a) is type(b) and a == b


def clamp_wait(raw):
    """A `wait` query value as seconds to hold a poll: 0 answers at once."""
    try:
        wait = int(raw)
    except (TypeError, ValueError):
        return DEFAULT_WAIT
    return max(0, min(MAX_WAIT, wait))


# ---------------------------------------------------------------------------------------------
# Pictures: a template names a slot's `entity_picture` (`rc-slot://<slot>/entity_picture`), never
# a URL. The server finds that slot's real entity in the card's own config (as
# `CardDesignSlots.resolvePictureUrl`), fetches its picture and scales it for the device.
# ---------------------------------------------------------------------------------------------

SLOT_NAME = re.compile(r"^[a-z0-9_]+\.s(\d{1,4})$")
PICTURE_FORMATS = {"jpeg": "image/jpeg", "png": "image/png"}
PICTURE_FITS = ("contain", "cover")
# The largest box a device may ask for, per side.
MAX_PICTURE_SIDE = 2048


def picture_entity(card, slot):
    """The entity whose `entity_picture` [slot] (`camera.s0`) names in [card], or None: the slot is
    found by position, and must be of that entity's domain."""
    match = SLOT_NAME.match(slot or "")
    if not match:
        return None
    entries = slot_entries(card) or []
    index = int(match.group(1))
    if index >= len(entries) or slot_id(domain_of(entries[index][0]), index) != slot:
        return None
    return entries[index][0]


def picture_url(url_path, cid, slot, entity_picture):
    """Where a device fetches [slot]'s picture. `v` changes with the picture's own URL, so a device
    refetches only when it moved (a camera's still moves with its rotating token)."""
    version = hashlib.sha256(entity_picture.encode()).hexdigest()[:12]
    return f"{API}/pictures/{url_path or DEFAULT_DASHBOARD}/{cid}/{slot}?v={version}"


def picture_values(url_path, cid, card, pictures):
    """`<slot>.picture_url` for each of [card]'s slots whose entity has a picture. [pictures] is
    entity id → `entity_picture`, for the entities the user may read. These are for the device,
    not the document: a player that has a picture loader fetches the slot's picture from here."""
    out = {}
    for index, (entity, _) in enumerate(slot_entries(card) or []):
        picture = pictures.get(entity)
        if isinstance(picture, str) and picture:
            slot = slot_id(domain_of(entity), index)
            out[f"{slot}.picture_url"] = picture_url(url_path, cid, slot, picture)
    return out


def picture_source(entity_picture):
    """("local", path) for a picture Home Assistant serves itself (`/api/camera_proxy/…`,
    `/local/…`), ("remote", url) for an http(s) one, or None."""
    if not isinstance(entity_picture, str) or not entity_picture:
        return None
    if entity_picture.startswith("/") and not entity_picture.startswith("//"):
        return "local", entity_picture
    if re.match(r"^https?://", entity_picture, re.IGNORECASE):
        return "remote", entity_picture
    return None


def picture_query(query):
    """(w, h, fit, format) from a picture request's query: `w`/`h` the box in pixels (either may be
    left out), `fit` contain (default: all of it, inside the box) or cover (the box filled,
    cropped), `format` jpeg or png (default: as it came when nothing is resized, else jpeg).
    Raises WallError(400)."""

    def side(name):
        raw = query.get(name)
        if raw in (None, ""):
            return None
        if not str(raw).isdigit() or not 1 <= int(raw) <= MAX_PICTURE_SIDE:
            raise WallError(400, f"{name} must be 1 to {MAX_PICTURE_SIDE}")
        return int(raw)

    fit = query.get("fit") or "contain"
    if fit not in PICTURE_FITS:
        raise WallError(400, "fit must be contain or cover")
    fmt = query.get("format") or None
    if fmt == "jpg":
        fmt = "jpeg"
    if fmt is not None and fmt not in PICTURE_FORMATS:
        raise WallError(400, "format must be jpeg or png")
    return side("w"), side("h"), fit, fmt


def picture_box(src_w, src_h, w, h, fit):
    """(scaled width, scaled height, crop) to draw a [src_w]×[src_h] picture in a [w]×[h] box.
    A missing side follows the picture's aspect ratio; a picture is never scaled up. crop is a
    (left, top, right, bottom) box in the scaled picture, or None."""
    if w is None and h is None:
        return src_w, src_h, None
    if w is None or h is None:
        scale = (w / src_w) if w is not None else (h / src_h)
        scale = min(1.0, scale)
        return max(1, round(src_w * scale)), max(1, round(src_h * scale)), None
    if fit == "contain":
        scale = min(1.0, w / src_w, h / src_h)
        return max(1, round(src_w * scale)), max(1, round(src_h * scale)), None
    scale = min(1.0, max(w / src_w, h / src_h))
    scaled_w, scaled_h = max(1, round(src_w * scale)), max(1, round(src_h * scale))
    crop_w, crop_h = min(w, scaled_w), min(h, scaled_h)
    left, top = (scaled_w - crop_w) // 2, (scaled_h - crop_h) // 2
    return scaled_w, scaled_h, (left, top, left + crop_w, top + crop_h)


# ---------------------------------------------------------------------------------------------
# Fonts: the faces designs name (`RemoteFontFamily.Named`), from the design server's manifest.
# ---------------------------------------------------------------------------------------------

FONT_FILE = re.compile(r"^([0-9a-f]{64})\.(ttf|otf|woff2?)$")


def font_manifest(manifest):
    """The design server's `fonts.json` for a device: each face also says where to fetch it, by
    its sha256 (`url`), so its bytes are immutable and cached by hash like designs."""
    families = []
    for family in manifest.get("families", []) if isinstance(manifest, dict) else []:
        if not isinstance(family, dict) or not _text(family.get("name")):
            continue
        fonts = []
        for face in family.get("fonts", []):
            if not isinstance(face, dict):
                continue
            sha, file = face.get("sha256"), _text(face.get("file"))
            if not isinstance(sha, str) or not SHA256.match(sha) or not file or "." not in file:
                continue
            ext = file.rsplit(".", 1)[1].lower()
            fonts.append({**face, "url": f"{API}/fonts/{sha}.{ext}"})
        if fonts:
            families.append({**family, "fonts": fonts})
    return {"families": families}


def font_file(manifest, sha):
    """The design server's file name for the face whose sha256 is [sha], or None."""
    for family in manifest.get("families", []) if isinstance(manifest, dict) else []:
        for face in family.get("fonts", []) if isinstance(family, dict) else []:
            if isinstance(face, dict) and face.get("sha256") == sha and _text(face.get("file")):
                file = face["file"]
                if "/" not in file and "\\" not in file and not file.startswith("."):
                    return file
    return None


def blob_url(sha):
    return f"{API}/blobs/{sha}.rc"


def parse_card_ids(raw, known):
    """The `cards` query value (comma separated, or empty for every card) against [known]."""
    if not raw:
        return list(known)
    wanted = [c for c in raw.split(",") if c]
    return [c for c in wanted if c in known]
