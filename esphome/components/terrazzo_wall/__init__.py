"""Terrazzo wall display: a Home Assistant dashboard's cards, played on the board's screen.

Talks to the Terrazzo integration's wall display API (`/api/terrazzo/wall/v1/`, see
docs/architecture/wall-display-api.md in this repository) and draws each card's RemoteCompose
design with the Kobento MCU player, which drives the panel itself (no ESPHome `display:`).

    terrazzo_wall:
      url: http://homeassistant.local:8123
      token: !secret wall_token
      kobento:
        path: /config/kobento/kobento-mcu
        board: m5stack_tab5
"""

from pathlib import Path
import re
import zlib

from esphome import automation
import esphome.codegen as cg
from esphome.components import button, esp32, number, select, switch, text_sensor
import esphome.config_validation as cv
from esphome.const import CONF_ID, CONF_PATH, CONF_RAW_DATA_ID, CONF_URL
from esphome.core import CORE, ID, HexInt
from esphome.core.entity_helpers import setup_entity

CODEOWNERS = []
DEPENDENCIES = ["network"]
AUTO_LOAD = ["json", "select", "text_sensor", "button", "switch", "number", "camera"]

CONF_BOARD = "board"
CONF_CARD = "card"
CONF_DARK = "dark"
CONF_DASHBOARD = "dashboard"
CONF_ENGINE = "engine"
CONF_FIT = "fit"
CONF_GLYPHS = "glyphs"
CONF_EMBED = "embed"
CONF_FACE = "face"
CONF_SIZES = "sizes"
CONF_BITS = "bits"
CONF_CHARS = "chars"
CONF_SERVER = "server"
CONF_KOBENTO = "kobento"
CONF_SPLASH = "splash"
CONF_STYLE = "style"
CONF_TIMEZONE = "timezone"
CONF_SCREEN_TIMEOUT = "screen_timeout"
CONF_SCREENS = "screens"
CONF_LAYOUT_ANIMATIONS = "layout_animations"
CONF_NAVIGATION = "navigation"
CONF_SCROLL_BAR = "scroll_bar"
CONF_PICTURE_MAX = "picture_max"
CONF_HOME_ASSISTANT = "home_assistant"
CONF_DASHBOARD_SELECT = "dashboard"
CONF_PLACE = "place"
CONF_NEXT_SCREEN = "next_screen"
CONF_PREVIOUS_SCREEN = "previous_screen"
CONF_NEXT_VIEW = "next_view"
CONF_PREVIOUS_VIEW = "previous_view"
CONF_SCREEN = "screen"
CONF_SCREEN_CAPTURE = "screen_capture"
CONF_QUALITY = "quality"
CONF_IDLE_RETURN = "idle_return"
CONF_SNAPSHOT_PORT = "snapshot_port"
CONF_TOKEN = "token"
CONF_VIEW = "view"
CONF_WAIT = "wait"

# The Kobento library's shared ESP-IDF components; the board adds its own (`board` + its BSP).
KOBENTO_COMPONENTS = ("rccore", "thorvg", "kobentovg", "rcstage")
# Board sdkconfig lines ESPHome sets itself: the chip and its revision, CPU clock, flash,
# partitions, console and log, and the radio (`wifi:`, `esp32_hosted:`, `esp32_ble:`).
MANAGED_SDKCONFIG = (
    "CONFIG_IDF_TARGET",
    "CONFIG_ESP32P4_SELECTS_REV",
    "CONFIG_ESP32P4_REV_MIN",
    "CONFIG_ESP_DEFAULT_CPU_FREQ_",
    "CONFIG_ESPTOOLPY_",
    "CONFIG_PARTITION_TABLE_",
    "CONFIG_ESP_CONSOLE_",
    "CONFIG_LOG_",
    "CONFIG_ESP_HOSTED_",
    "CONFIG_ESP_WIFI_REMOTE_",
    "CONFIG_SLAVE_IDF_TARGET_",
    "CONFIG_BT_",
)

terrazzo_wall_ns = cg.esphome_ns.namespace("terrazzo_wall")
TerrazzoWall = terrazzo_wall_ns.class_("TerrazzoWall", cg.Component)
NextCardAction = terrazzo_wall_ns.class_("NextCardAction", automation.Action)
PreviousCardAction = terrazzo_wall_ns.class_("PreviousCardAction", automation.Action)
WakeAction = terrazzo_wall_ns.class_("WakeAction", automation.Action)
SleepAction = terrazzo_wall_ns.class_("SleepAction", automation.Action)
BenchAction = terrazzo_wall_ns.class_("BenchAction", automation.Action)
NextDashboardAction = terrazzo_wall_ns.class_("NextDashboardAction", automation.Action)
PreviousDashboardAction = terrazzo_wall_ns.class_("PreviousDashboardAction", automation.Action)
NextViewAction = terrazzo_wall_ns.class_("NextViewAction", automation.Action)
PreviousViewAction = terrazzo_wall_ns.class_("PreviousViewAction", automation.Action)
DashboardSelect = terrazzo_wall_ns.class_("DashboardSelect", select.Select)
WallButton = terrazzo_wall_ns.class_("WallButton", button.Button)
ScreenSwitch = terrazzo_wall_ns.class_("ScreenSwitch", switch.Switch)
IdleReturnNumber = terrazzo_wall_ns.class_("IdleReturnNumber", number.Number)
ScreenCamera = terrazzo_wall_ns.class_(
    "ScreenCamera", cg.esphome_ns.namespace("camera").class_("Camera", cg.EntityBase, cg.Component)
)
# WallButton::Kind, in order.
_BUTTONS = {
    CONF_NEXT_SCREEN: ("Next screen", "mdi:chevron-right", 0),
    CONF_PREVIOUS_SCREEN: ("Previous screen", "mdi:chevron-left", 1),
    CONF_NEXT_VIEW: ("Next view", "mdi:page-next-outline", 2),
    CONF_PREVIOUS_VIEW: ("Previous view", "mdi:page-previous-outline", 3),
}

# The panel in Home Assistant: the dashboard on show as a select (its options the dashboards
# this user can see; choosing one goes there), where it is as text, and buttons to page. Each
# is an ordinary ESPHome entity: rename one, or hide it with `internal: true`.
HOME_ASSISTANT_SCHEMA = cv.Schema(
    {
        cv.Optional(CONF_DASHBOARD_SELECT, default={"name": "Dashboard"}): select.select_schema(
            DashboardSelect, icon="mdi:view-dashboard"
        ),
        cv.Optional(CONF_PLACE, default={"name": "Place"}): text_sensor.text_sensor_schema(
            icon="mdi:map-marker-outline"
        ),
        # The backlight: on wakes the screen, off sleeps it; it follows the screen timeout.
        cv.Optional(CONF_SCREEN, default={"name": "Screen"}): switch.switch_schema(
            ScreenSwitch, icon="mdi:monitor", default_restore_mode="DISABLED"
        ),
        # Minutes without a touch or a control from here before the panel goes back to the
        # dashboard last chosen from Home Assistant (first view and screen); 0 never. Kept
        # across reboots; `idle_return:` is its first value.
        cv.Optional(CONF_IDLE_RETURN, default={"name": "Return after idle"}): number.number_schema(
            IdleReturnNumber, icon="mdi:home-clock-outline", unit_of_measurement="min",
            entity_category="config",
        ),
        # The screen as a camera: the frame on show, as a JPEG, whenever Home Assistant asks.
        cv.Optional(CONF_SCREEN_CAPTURE, default={"name": "Screen capture"}): cv.ENTITY_BASE_SCHEMA.extend(
            {
                cv.GenerateID(): cv.declare_id(ScreenCamera),
                cv.Optional(CONF_QUALITY, default=70): cv.int_range(1, 100),
            }
        ).extend(cv.COMPONENT_SCHEMA),
        **{
            cv.Optional(key, default={"name": name}): button.button_schema(WallButton, icon=icon)
            for key, (name, icon, _) in _BUTTONS.items()
        },
    }
)


def _directory(value):
    path = Path(CORE.relative_config_path(cv.string(value)))
    if not path.is_dir():
        raise cv.Invalid(f"No directory {path}")
    return str(path.resolve())


# Boards kept with this component (boards/<name>/, laid out as kobento-mcu's esp32/boards/),
# for panels kobento-mcu has no board for yet. kobento-mcu's own come second.
LOCAL_BOARDS = Path(__file__).parent / "boards"


def _board_dirs(root: Path):
    return (LOCAL_BOARDS, root / "esp32" / "boards")


def _board_dir(root: Path, name: str) -> Path | None:
    for boards in _board_dirs(root):
        if (boards / name / "sdkconfig.defaults").is_file():
            return boards / name
    return None


def _kobento(config):
    root = Path(config[CONF_PATH])
    for name in KOBENTO_COMPONENTS:
        if not (root / "components" / name / "CMakeLists.txt").is_file():
            raise cv.Invalid(f"{root} is not a kobento-mcu checkout: no components/{name}")
    if _board_dir(root, config[CONF_BOARD]) is None:
        known = sorted(
            p.name
            for boards in _board_dirs(root)
            if boards.is_dir()
            for p in boards.iterdir()
            if (p / "sdkconfig.defaults").is_file()
        )
        raise cv.Invalid(f"Unknown board {config[CONF_BOARD]}; boards: {', '.join(known)}")
    return config


# The player's faces (kobento-mcu's assets/fonts), as glyph packs name them.
GLYPH_FACES = {
    "regular": ("Roboto-Regular.ttf", "roboto", 400),
    "bold": ("Roboto-Bold.ttf", "roboto", 700),
    "mono": ("RobotoMono-Regular.ttf", "roboto mono", 400),
}
# What every wall screen sets its text in: Roboto at 12, 13, 15, 17 and 22 px, Bold at 16.
DEFAULT_EMBED = [
    {CONF_FACE: "regular", CONF_SIZES: [12, 13, 15, 17, 22]},
    {CONF_FACE: "bold", CONF_SIZES: [16]},
]


GLYPHS_SCHEMA = cv.Schema(
    {
        # Styles drawn into the firmware: no fetch, read where they lie in the app image. 4 bits
        # a pixel, some 6 KB a style; on a board that runs from PSRAM (the LCD-7) the image is
        # copied to PSRAM, so each style costs that much PSRAM. [] embeds none.
        cv.Optional(CONF_EMBED, default=DEFAULT_EMBED): cv.ensure_list(
            cv.Schema(
                {
                    cv.Required(CONF_FACE): cv.one_of(*GLYPH_FACES, lower=True),
                    cv.Required(CONF_SIZES): cv.ensure_list(cv.int_range(min=6, max=96)),
                }
            )
        ),
        cv.Optional(CONF_CHARS): cv.string,
        cv.Optional(CONF_BITS, default=4): cv.one_of(1, 2, 4, 8, int=True),
        # The design server (its root, e.g. http://192.168.0.10:8080) to fetch what no embedded
        # style has: another size, or a character outside `chars`. Kept in NVS across restarts.
        cv.Optional(CONF_SERVER): cv.url,
    }
)


def _rc_file(value):
    path = Path(CORE.relative_config_path(cv.string(value)))
    if not path.is_file():
        raise cv.Invalid(f"No file {path}")
    return str(path)


_REQUIRES = re.compile(r"\b(?:PRIV_)?REQUIRES\b([^)]*?)(?=\b(?:PRIV_REQUIRES|REQUIRES|EMBED_FILES|SRCS|INCLUDE_DIRS)\b|\))", re.S)


def _idf_requires(component: Path) -> set[str]:
    """The ESP-IDF components a component's CMakeLists.txt names in (PRIV_)REQUIRES."""
    text = (component / "CMakeLists.txt").read_text()
    names = set()
    for match in _REQUIRES.finditer(text):
        names.update(w for w in match.group(1).split() if not w.startswith("$"))
    return names


def _sdkconfig_defaults(path: Path):
    """The board's `CONFIG_X=value` lines, as add_idf_sdkconfig_option takes them."""
    options = {}
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        name, value = line.split("=", 1)
        if value == "y":
            options[name] = True
        elif value == "n":
            options[name] = False
        elif value.startswith('"') and value.endswith('"'):
            options[name] = value[1:-1]
        else:
            try:
                options[name] = int(value, 0)
            except ValueError:
                options[name] = value
    return options


def _board_target(config):
    path = _board_dir(Path(config[CONF_KOBENTO][CONF_PATH]), config[CONF_KOBENTO][CONF_BOARD])
    return _sdkconfig_defaults(path / "sdkconfig.defaults").get("CONFIG_IDF_TARGET")


def _final_validate(config):
    want = _board_target(config)
    have = esp32.get_esp32_variant().lower().replace("-", "")
    if want and want != have:
        raise cv.Invalid(
            f"Board {config[CONF_KOBENTO][CONF_BOARD]} is an {want}, but `esp32:` builds for {have}",
            path=[CONF_KOBENTO, CONF_BOARD],
        )
    return config


CONFIG_SCHEMA = cv.All(
    cv.Schema(
        {
            cv.GenerateID(): cv.declare_id(TerrazzoWall),
            cv.Required(CONF_URL): cv.url,
            cv.Required(CONF_TOKEN): cv.string_strict,
            cv.Optional(CONF_DASHBOARD, default="default"): cv.string_strict,
            cv.Optional(CONF_VIEW, default="0"): cv.string_strict,
            cv.Optional(CONF_CARD): cv.string_strict,
            cv.Optional(CONF_STYLE, default="classic"): cv.string_strict,
            cv.Optional(CONF_DARK, default=True): cv.boolean,
            cv.Optional(CONF_WAIT, default="25s"): cv.All(
                cv.positive_time_period_seconds,
                cv.Range(max=cv.TimePeriod(seconds=55)),
            ),
            cv.Optional(CONF_FIT, default=True): cv.boolean,
            # Page through the view a screen of cards at a time (false: a card at a time).
            cv.Optional(CONF_SCREENS, default=True): cv.boolean,
            # A bar along the bottom of the screen: ‹ and › page, then a tab per dashboard (the
            # current one's tab steps through its views). Cards lay out above it.
            cv.Optional(CONF_NAVIGATION, default=True): cv.boolean,
            # A scroll bar down the right: where the screen showing is in its view. A tap above
            # its thumb goes up a screen, below it down one. Cards lay out left of it.
            cv.Optional(CONF_SCROLL_BAR, default=True): cv.boolean,
            # The most of the screen one picture (a camera, an image) takes: a larger one is drawn
            # narrower, so other cards fit beside and under it.
            cv.Optional(CONF_PICTURE_MAX, default="25%"): cv.percentage,
            # A card's layout animations (a value's text resizing its row, a slot appearing, an
            # entity going unavailable): some 320 ms on the wall clock per state change. Each
            # frame redraws only what moved and doesn't measure the layout again; a card that
            # loads settles without animating. Off, a change is one frame.
            cv.Optional(CONF_LAYOUT_ANIMATIONS, default=False): cv.boolean,
            cv.Optional(CONF_SCREEN_TIMEOUT, default="5min"): cv.positive_time_period_milliseconds,
            cv.Optional(CONF_SPLASH): _rc_file,
            # Text composed from glyph packs instead of rasterised on the panel: see GLYPHS_SCHEMA.
            cv.Optional(CONF_GLYPHS, default={}): GLYPHS_SCHEMA,
            cv.Optional(CONF_HOME_ASSISTANT, default={}): HOME_ASSISTANT_SCHEMA,
            # How long the panel may sit elsewhere, untouched, before it goes back home (see
            # home_assistant.idle_return, which can change it at run time). 0s: never.
            cv.Optional(CONF_IDLE_RETURN, default="5min"): cv.All(
                cv.positive_time_period_minutes, cv.Range(max=cv.TimePeriod(minutes=240))
            ),
            # A debugging aid: `GET http://<device>:<port>/snapshot.bmp` is the screen as it is
            # now, `/doc.rc` the document on it. No authentication: anyone on the network can
            # see the panel. Off by default.
            cv.Optional(CONF_SNAPSHOT_PORT): cv.port,
            cv.GenerateID(CONF_RAW_DATA_ID): cv.declare_id(cg.uint8),
            cv.Optional(CONF_TIMEZONE): cv.string_strict,
            cv.Required(CONF_KOBENTO): cv.All(
                cv.Schema(
                    {
                        cv.Required(CONF_PATH): _directory,
                        cv.Required(CONF_BOARD): cv.string_strict,
                        cv.Optional(CONF_ENGINE): _directory,
                    }
                ),
                _kobento,
            ),
        }
    ).extend(cv.COMPONENT_SCHEMA),
    cv.only_on_esp32,
    cv.only_with_framework("esp-idf"),
)

FINAL_VALIDATE_SCHEMA = _final_validate


async def to_code(config):
    var = cg.new_Pvariable(config[CONF_ID])
    await cg.register_component(var, config)
    # JPEG: pictures (cameras, images) are decoded with it, the screen camera encodes with it.
    esp32.add_idf_component(name="espressif/esp_new_jpeg", ref="1.0.2")
    cg.add(var.set_url(config[CONF_URL]))
    cg.add(var.set_token(config[CONF_TOKEN]))
    cg.add(var.set_dashboard(config[CONF_DASHBOARD]))
    cg.add(var.set_view(config[CONF_VIEW]))
    if CONF_CARD in config:
        cg.add(var.set_card(config[CONF_CARD]))
    cg.add(var.set_style(config[CONF_STYLE]))
    cg.add(var.set_dark(config[CONF_DARK]))
    cg.add(var.set_wait(config[CONF_WAIT].total_seconds))
    cg.add(var.set_fit(config[CONF_FIT]))
    cg.add(var.set_screens(config[CONF_SCREENS]))
    cg.add(var.set_navigation(config[CONF_NAVIGATION]))
    cg.add(var.set_scroll_bar(config[CONF_SCROLL_BAR]))
    cg.add(var.set_picture_max(int(round(config[CONF_PICTURE_MAX] * 100))))
    ha = config[CONF_HOME_ASSISTANT]
    dashboards = await select.new_select(ha[CONF_DASHBOARD_SELECT], options=[])
    await cg.register_parented(dashboards, var)
    cg.add(var.set_dashboard_select(dashboards))
    place = await text_sensor.new_text_sensor(ha[CONF_PLACE])
    cg.add(var.set_place_sensor(place))
    screen = await switch.new_switch(ha[CONF_SCREEN])
    await cg.register_parented(screen, var)
    cg.add(var.set_screen_switch(screen))
    idle = await number.new_number(ha[CONF_IDLE_RETURN], min_value=0, max_value=240, step=1)
    await cg.register_parented(idle, var)
    cg.add(var.set_idle_return_number(idle))
    cg.add(var.set_idle_return_default(config[CONF_IDLE_RETURN].total_minutes))
    capture = ha[CONF_SCREEN_CAPTURE]
    cg.add_define("USE_CAMERA")
    camera = cg.new_Pvariable(capture[CONF_ID])
    await setup_entity(camera, capture, "camera")
    await cg.register_component(camera, capture)
    cg.add(camera.set_quality(capture[CONF_QUALITY]))
    for key, (_, _, kind) in _BUTTONS.items():
        b = await button.new_button(ha[key])
        await cg.register_parented(b, var)
        cg.add(b.set_kind(kind))
    cg.add(var.set_layout_animations(config[CONF_LAYOUT_ANIMATIONS]))
    cg.add(var.set_screen_timeout(config[CONF_SCREEN_TIMEOUT].total_milliseconds))
    if CONF_SNAPSHOT_PORT in config:
        cg.add_define("USE_TERRAZZO_SNAPSHOT")
        cg.add(var.set_snapshot_port(config[CONF_SNAPSHOT_PORT]))
        esp32.include_builtin_idf_component("esp_http_server")
    # Something on screen from boot, for the status banner to show on: the splash asked for,
    # else kobento-mcu's own.
    splash = config.get(CONF_SPLASH)
    default_splash = Path(config[CONF_KOBENTO][CONF_PATH]) / "documents" / "kobento_splash.rc"
    if splash is None and default_splash.is_file():
        splash = str(default_splash)
    if splash is not None:
        data = Path(splash).read_bytes()
        array = cg.progmem_array(config[CONF_RAW_DATA_ID], [HexInt(b) for b in data])
        cg.add(var.set_splash(array, len(data)))

    await _glyph_packs_to_code(var, config)

    # Kobento: the player library from a local kobento-mcu checkout, and the board's display,
    # touch and BSP components.
    kobento = config[CONF_KOBENTO]
    root = Path(kobento[CONF_PATH])
    for name in KOBENTO_COMPONENTS:
        esp32.add_idf_component(name=name, path=str(root / "components" / name))
    board = _board_dir(root, kobento[CONF_BOARD])
    requires = set()
    for name in KOBENTO_COMPONENTS:
        requires |= _idf_requires(root / "components" / name)
    for component in sorted((board / "components").iterdir()):
        if (component / "CMakeLists.txt").is_file():
            esp32.add_idf_component(name=component.name, path=str(component))
            requires |= _idf_requires(component)
    # ESPHome leaves ESP-IDF components it doesn't use out of the build (esp_lcd, esp_driver_i2c,
    # esp_http_client, …): bring back the ones the player and the board name.
    for name in sorted(requires):
        esp32.include_builtin_idf_component(name)
    # The wall API client (terrazzo_wall.cpp): ESP-IDF's HTTP client, and the TLS it links.
    esp32.request_http_client()
    # https: check Home Assistant's certificate against ESP-IDF's CA bundle (ESPHome leaves the
    # bundle out unless something asks for it).
    if config[CONF_URL].lower().startswith("https://"):
        esp32.require_certificate_bundle()
    for name, value in _sdkconfig_defaults(board / "sdkconfig.defaults").items():
        if not name.startswith(MANAGED_SDKCONFIG):
            esp32.add_idf_sdkconfig_option(name, value)
    # What kobento-mcu's own player apps set (esp32/firmware/rcplayer/sdkconfig.defaults): the
    # engine throws and catches its own errors and uses dynamic_cast, which ESP-IDF leaves off;
    # and it allocates documents with plain new, so malloc may use PSRAM above 4 KB (ESPHome's
    # `psram:` allows PSRAM through heap_caps only).
    esp32.add_idf_sdkconfig_option("CONFIG_COMPILER_CXX_EXCEPTIONS", True)
    esp32.add_idf_sdkconfig_option("CONFIG_COMPILER_CXX_RTTI", True)
    esp32.add_idf_sdkconfig_option("CONFIG_SPIRAM_USE_CAPS_ALLOC", False)
    esp32.add_idf_sdkconfig_option("CONFIG_SPIRAM_USE_MALLOC", True)
    # No plain allocation stays internal: the player's (ThorVG's many small blocks a shape and
    # glyph) filled internal RAM on a deep screen, and Wi-Fi, which must allocate there, aborted
    # (an esp_timer it couldn't create in ppTask). 64 KB of internal RAM is kept for what can
    # only live there (Wi-Fi, DMA); everything else prefers PSRAM, the player's stack included.
    esp32.add_idf_sdkconfig_option("CONFIG_SPIRAM_MALLOC_ALWAYSINTERNAL", 0)
    esp32.add_idf_sdkconfig_option("CONFIG_SPIRAM_MALLOC_RESERVE_INTERNAL", 65536)
    # Sockets: ESP-IDF's 10 are nearly all taken before the panel asks anything (the API's
    # listener and up to three clients, OTA's listener, the snapshot server's two), and the long
    # poll, a tap's request and a picture's each need one more. With 10, a tap failed whenever a
    # log viewer was connected ("can't reach Home Assistant").
    esp32.add_idf_sdkconfig_option("CONFIG_LWIP_MAX_SOCKETS", 16)
    # TLS's buffers (some 40 KB a connection, and the poll and a tap can each hold one) in PSRAM.
    esp32.add_idf_sdkconfig_option("CONFIG_MBEDTLS_INTERNAL_MEM_ALLOC", False)
    esp32.add_idf_sdkconfig_option("CONFIG_MBEDTLS_EXTERNAL_MEM_ALLOC", True)
    # The player owns core 1: a big card's layout (seconds on this chip) keeps core 1's idle task
    # from running, which the task watchdog took for a hang and reset the panel.
    esp32.add_idf_sdkconfig_option("CONFIG_ESP_TASK_WDT_CHECK_IDLE_TASK_CPU1", False)
    # The RemoteCompose engine rccore builds from (default: remotecompose-experiments beside
    # the kobento-mcu checkout).
    if CONF_ENGINE in kobento:
        CORE.add_cmake_arg("RC_EXPERIMENTS", kobento[CONF_ENGINE])
    if CONF_TIMEZONE in config:
        esp32.add_idf_sdkconfig_option("CONFIG_RCSTAGE_TIMEZONE", config[CONF_TIMEZONE])
    # rcstage keeps the clock with SNTP; ESPHome's `time:` does too, so leave rcstage's off.
    esp32.add_idf_sdkconfig_option("CONFIG_RCSTAGE_NTP_SERVER", "")


async def _glyph_packs_to_code(var, config):
    """The embedded styles' packs, drawn from the player's own faces, as flash arrays."""
    from .glyph_packs import DEFAULT_CHARS, render_pack

    glyphs = config[CONF_GLYPHS]
    fonts = Path(config[CONF_KOBENTO][CONF_PATH]) / "assets" / "fonts"
    chars = glyphs.get(CONF_CHARS, DEFAULT_CHARS)
    bits = glyphs[CONF_BITS]
    identity = zlib.crc32(f"{bits}".encode())
    for style in glyphs[CONF_EMBED]:
        file, family, weight = GLYPH_FACES[style[CONF_FACE]]
        for size in sorted(set(style[CONF_SIZES])):
            try:
                pack = render_pack(fonts / file, family, weight, size, chars, bits)
            except ImportError as e:
                raise cv.Invalid(f"glyphs: drawing packs needs freetype-py ({e})") from e
            identity = zlib.crc32(pack, identity)
            array_id = ID(f"terrazzo_glyphs_{style[CONF_FACE]}_{size}", is_declaration=True, type=cg.uint8)
            array = cg.progmem_array(array_id, [HexInt(b) for b in pack])
            cg.add(var.add_glyph_pack(array, len(pack)))
    cg.add(var.set_glyph_bits(bits))
    cg.add(var.set_glyph_config(identity))
    if CONF_SERVER in glyphs:
        cg.add(var.set_glyph_server(glyphs[CONF_SERVER]))


CARD_ACTION_SCHEMA = automation.maybe_simple_id({cv.GenerateID(): cv.use_id(TerrazzoWall)})


@automation.register_action("terrazzo_wall.next_card", NextCardAction, CARD_ACTION_SCHEMA, synchronous=True)
@automation.register_action("terrazzo_wall.previous_card", PreviousCardAction, CARD_ACTION_SCHEMA, synchronous=True)
async def card_action_to_code(config, action_id, template_arg, args):
    var = cg.new_Pvariable(action_id, template_arg)
    await cg.register_parented(var, config[CONF_ID])
    return var


@automation.register_action("terrazzo_wall.next_dashboard", NextDashboardAction, CARD_ACTION_SCHEMA, synchronous=True)
@automation.register_action("terrazzo_wall.previous_dashboard", PreviousDashboardAction, CARD_ACTION_SCHEMA, synchronous=True)
@automation.register_action("terrazzo_wall.next_view", NextViewAction, CARD_ACTION_SCHEMA, synchronous=True)
@automation.register_action("terrazzo_wall.previous_view", PreviousViewAction, CARD_ACTION_SCHEMA, synchronous=True)
@automation.register_action("terrazzo_wall.bench", BenchAction, CARD_ACTION_SCHEMA, synchronous=True)
@automation.register_action("terrazzo_wall.wake", WakeAction, CARD_ACTION_SCHEMA, synchronous=True)
@automation.register_action("terrazzo_wall.sleep", SleepAction, CARD_ACTION_SCHEMA, synchronous=True)
async def screen_action_to_code(config, action_id, template_arg, args):
    var = cg.new_Pvariable(action_id, template_arg)
    await cg.register_parented(var, config[CONF_ID])
    return var
