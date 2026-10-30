/**
 * `<ha-card>`: one Home Assistant card on any web page.
 *
 *   <script type="module" src="https://ha.coo.ee/embed/v1/ha-card.js"></script>
 *   <ha-card hass="https://my-ha.example:8123"
 *            design="<sha256 of the template>"
 *            card='{"type":"tile","entity":"light.kitchen"}'></ha-card>
 *
 * The card's look is a values-free card design template from the design library
 * (`<library>/blobs/<sha256>.rc`, see docs/architecture/card-design-server.md), played by the
 * TypeScript RemoteCompose player. Its values come from the *viewer's own* Home Assistant: the
 * viewer signs in with HA's OAuth (client_id = this page's origin, the way HA's Cast receiver
 * does), and the card subscribes to its entities over HA's WebSocket API and pushes the slot
 * bindings a template reads. A tap runs one of the card's own actions through `call_service`.
 * Nothing about the viewer's Home Assistant ever reaches the design server.
 *
 * Design: docs/architecture/card-embedding.md. This file is served as is (no build step) by the
 * design server at `/embed/v1/ha-card.js`, next to the player at `/embed/v1/rc-player.js`.
 *
 * The binding functions are a port of `ha-model` (`formatState`, `HaLiveBindings`,
 * `CardDesignSlots.slotBindings`); `embed/test/bindings-golden.json` pins them to the Kotlin
 * output on both sides (`EmbedBindingsGoldenTest` in design-server, `bindings.test.mjs` here).
 */

// ---------------------------------------------------------------------------------------------
// Bindings: a port of ha-model's host-reproducible slot bindings.
// ---------------------------------------------------------------------------------------------

/** `CardDesignSlots.CARD_TITLE`. */
export const CARD_TITLE = 'card.title';

export const STATUS_AVAILABLE = 0;
export const STATUS_UNAVAILABLE = 1;
export const STATUS_UNKNOWN = 2;
export const STATUS_MISSING = 3;

/** kotlinx `jsonPrimitive.contentOrNull`: a primitive's text, null for null and non-primitives. */
function content(value) {
  if (value === null || value === undefined) return null;
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  return null;
}

function isObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

export function domainOf(entityId) {
  const dot = entityId.indexOf('.');
  return dot < 0 ? '' : entityId.substring(0, dot);
}

export function slotId(domain, index) {
  return `${domain}.s${index}`;
}

/**
 * What a value that exists but can't be shown honestly reads as (`NO_VALUE` in ha-model): a
 * redacted (`21.xx`) or negative duration. Never a number the entity didn't report.
 */
export const NO_VALUE = '—';

/**
 * `formatState`: the display string the converters bake and the live binding pushes, read the way
 * Home Assistant's frontend shows a state — relative times, binary-sensor words, display
 * precision, currency, durations, compass bearings, count units; free text untouched. A port of
 * ha-model's `HaStateFormat.kt`, rule for rule.
 *
 * [entity] is `{ state, attributes }` for [entityId]. [displayPrecision] is the entity registry's
 * (`dp`); the embed doesn't read the registry, so it passes none, like a snapshot without devices.
 * [nowMs] is the reference instant for relative times (`3 h ago`): the embed's own clock, as on
 * every other host — see `isRelativeTimeState` for refreshing it.
 */
export function formatState(entity, entityId = '', displayPrecision = null, nowMs = Date.now()) {
  const raw = entity ? entity.state : null;
  if (raw === null || raw === undefined) return 'Unavailable';
  if (raw === 'unavailable') return 'Unavailable';
  if (raw === 'unknown') return 'Unknown';
  const attributes = isObject(entity.attributes) ? entity.attributes : {};
  const domain = domainOf(entityId);
  const deviceClass = attrString(attributes, 'device_class');
  const unit = (attrString(attributes, 'unit_of_measurement') ?? '').trim();

  if (isTimestampEntity(domain, deviceClass)) {
    const time = parseInstant(raw);
    if (time !== null) return formatRelativeTime(time, nowMs);
  }
  if (deviceClass === 'date') {
    const date = parseLocalDate(raw);
    if (date !== null) return formatDate(date.year, date.month, date.day);
  }
  if (domain === 'binary_sensor') {
    const label = binarySensorLabel(deviceClass, raw);
    if (label !== null) return label;
  }
  if (isRedacted(raw)) return NO_VALUE;
  if (isNumber(raw)) {
    const precision =
      displayPrecision ?? attrInt(attributes, 'display_precision') ?? attrInt(attributes, 'suggested_display_precision');
    return formatNumericState(raw, unit, deviceClass, precision, attrString(attributes, 'friendly_name'));
  }
  return humanize(raw);
}

/** `formatValueWithUnit`: [value] with its [unit], formatted as `formatState` formats a number. */
export function formatValueWithUnit(value, unit, deviceClass = null, displayPrecision = null) {
  const cleanUnit = (unit ?? '').trim();
  if (isRedacted(value)) return NO_VALUE;
  if (!isNumber(value)) return joinUnit(value, cleanUnit);
  return formatNumericState(value, cleanUnit, deviceClass, displayPrecision, null);
}

/** How often a card showing a relative time re-derives its bindings: well inside a minute. */
const RELATIVE_TIME_TICK_MS = 20_000;

/**
 * `isRelativeTimeState`: whether [entity]'s display string is relative to now (`3 h ago`), so the
 * embed re-derives it on a timer even when the entity doesn't change.
 */
export function isRelativeTimeState(entity, entityId) {
  if (!entity || typeof entity.state !== 'string') return false;
  const attributes = isObject(entity.attributes) ? entity.attributes : {};
  return isTimestampEntity(domainOf(entityId), attrString(attributes, 'device_class')) && parseInstant(entity.state) !== null;
}

/** `string()` in HaStateFormat.kt: a primitive's text, null when absent or blank. */
function attrString(attributes, key) {
  const text = content(attributes[key]);
  return text === null || text.trim().length === 0 ? null : text;
}

/** `int()` in HaStateFormat.kt: kotlinx `intOrNull`, kept to 0..10. */
function attrInt(attributes, key) {
  const text = content(attributes[key]);
  if (text === null || !/^[+-]?\d+$/.test(text)) return null;
  const value = Number.parseInt(text, 10);
  return value >= 0 && value <= 10 ? value : null;
}

/** Kotlin's `kotlin.math.round`: half to even. */
function roundHalfEven(x) {
  const floor = Math.floor(x);
  const diff = x - floor;
  if (diff < 0.5) return floor;
  if (diff > 0.5) return floor + 1;
  return floor % 2 === 0 ? floor : floor + 1;
}

// Time ---------------------------------------------------------------------------------------

const TIMESTAMP_DOMAINS = new Set(['event', 'button', 'input_button', 'scene']);
const MINUTE = 60;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
const INSTANT =
  /^(\d{4})-(\d{2})-(\d{2})[Tt](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?(?:([Zz])|([+-])(\d{2}):?(\d{2}))$/;

function isTimestampEntity(domain, deviceClass) {
  return deviceClass === 'timestamp' || TIMESTAMP_DOMAINS.has(domain);
}

/** An ISO-8601 instant with an offset (`2026-04-20T12:22:53.298+00:00`) as epoch ms, or null. */
function parseInstant(raw) {
  if (raw.length < 10 || !/^\d/.test(raw)) return null;
  const m = INSTANT.exec(raw);
  if (!m) return null;
  const [year, month, day, hour, minute] = [1, 2, 3, 4, 5].map((i) => Number(m[i]));
  const second = m[6] ? Number(m[6]) : 0;
  if (month < 1 || month > 12 || day < 1 || day > daysIn(year, month) || hour > 23 || minute > 59 || second > 59)
    return null;
  const ms = m[7] ? Number(m[7].padEnd(3, '0').substring(0, 3)) : 0;
  const offset = m[8] ? 0 : (m[9] === '-' ? -1 : 1) * (Number(m[10]) * 60 + Number(m[11])) * 60_000;
  return Date.UTC(year, month - 1, day, hour, minute, second, ms) - offset;
}

function daysIn(year, month) {
  return new Date(Date.UTC(year, month, 0)).getUTCDate();
}

function parseLocalDate(raw) {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(raw);
  if (!m) return null;
  const [year, month, day] = [1, 2, 3].map((i) => Number(m[i]));
  if (month < 1 || month > 12 || day < 1 || day > daysIn(year, month)) return null;
  return { year, month, day };
}

/**
 * `formatRelativeTime`: `just now`, `5 min ago`, `3 h ago`, `2 days ago` (`in …` ahead), with
 * Home Assistant's `selectUnit` thresholds; a date (`20 Apr 2026`, local time) a week or more away.
 */
export function formatRelativeTime(timeMs, nowMs) {
  const seconds = Math.trunc((nowMs - timeMs) / 1000);
  const past = seconds >= 0;
  const magnitude = Math.abs(seconds);
  const relative = (text) => (past ? `${text} ago` : `in ${text}`);
  const minutes = roundHalfEven(magnitude / MINUTE);
  const hours = roundHalfEven(magnitude / HOUR);
  const days = roundHalfEven(magnitude / DAY);
  if (magnitude < 45) return past ? 'just now' : 'now';
  if (minutes < 45) return relative(`${minutes} min`);
  if (hours < 22) return relative(`${hours} h`);
  if (days < 7) return relative(days === 1 ? '1 day' : `${days} days`);
  const local = new Date(timeMs);
  return formatDate(local.getFullYear(), local.getMonth() + 1, local.getDate());
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

function formatDate(year, month, day) {
  return `${day} ${MONTHS[month - 1]} ${year}`;
}

// Binary sensors: Home Assistant's English state words by device class [off, on] ---------------

const BINARY_SENSOR_LABELS = {
  battery: ['Normal', 'Low'],
  battery_charging: ['Not charging', 'Charging'],
  carbon_monoxide: ['Clear', 'Detected'],
  co: ['Clear', 'Detected'],
  cold: ['Normal', 'Cold'],
  connectivity: ['Disconnected', 'Connected'],
  door: ['Closed', 'Open'],
  garage_door: ['Closed', 'Open'],
  gas: ['Clear', 'Detected'],
  heat: ['Normal', 'Hot'],
  light: ['No light', 'Light detected'],
  lock: ['Locked', 'Unlocked'],
  moisture: ['Dry', 'Wet'],
  motion: ['Clear', 'Detected'],
  moving: ['Not moving', 'Moving'],
  occupancy: ['Clear', 'Detected'],
  opening: ['Closed', 'Open'],
  plug: ['Unplugged', 'Plugged in'],
  presence: ['Away', 'Home'],
  problem: ['OK', 'Problem'],
  running: ['Not running', 'Running'],
  safety: ['Safe', 'Unsafe'],
  smoke: ['Clear', 'Detected'],
  sound: ['Clear', 'Detected'],
  tamper: ['Clear', 'Tampering detected'],
  update: ['Up-to-date', 'Update available'],
  vibration: ['Clear', 'Detected'],
  window: ['Closed', 'Open'],
};

function binarySensorLabel(deviceClass, raw) {
  if (deviceClass === null || !Object.hasOwn(BINARY_SENSOR_LABELS, deviceClass)) return null;
  const [off, on] = BINARY_SENSOR_LABELS[deviceClass];
  if (raw === 'off') return off;
  if (raw === 'on') return on;
  return null;
}

// Enum / free text ---------------------------------------------------------------------------

const ENUM_STATE = /^[a-z0-9_]*[a-z][a-z0-9_]*$/;
const ENUM_LABELS = { heat_cool: 'Heat/Cool', not_home: 'Away' };

/** Known enum / snake_case states humanised; anything else is the entity's own text, untouched. */
function humanize(raw) {
  if (!ENUM_STATE.test(raw)) return raw;
  if (Object.hasOwn(ENUM_LABELS, raw)) return ENUM_LABELS[raw];
  const text = raw.replaceAll('_', ' ');
  return text.charAt(0).toUpperCase() + text.substring(1);
}

// Numbers ------------------------------------------------------------------------------------

const REDACTED = /^-?\d+\.[xX]+$/;
const DECIMAL = /^([+-]?)(\d*)(?:\.(\d*))?(?:[eE]([+-]?\d+))?$/;

function isRedacted(raw) {
  return REDACTED.test(raw);
}

/** A plain finite decimal. `Number('')` is 0, so a digit is required, as Kotlin's parse does. */
function isNumber(raw) {
  return DECIMAL.test(raw) && /\d/.test(raw) && Number.isFinite(Number(raw));
}

function formatNumericState(value, unit, deviceClass, precision, name) {
  const number = Number(value);
  if (deviceClass === 'duration') {
    const duration = formatDuration(number, unit);
    if (duration !== null) return duration;
  }
  const currency = currencyOf(unit, deviceClass);
  if (currency !== null) return formatMoney(value, currency, precision);
  if (deviceClass === 'wind_direction' || (unit === '°' && mentionsBearing(name))) {
    const degrees = formatDecimal(value, precision ?? 0, precision ?? 0) ?? value;
    return `${compassPoint(number)} ${degrees}°`;
  }
  if (unit === 'm' && (deviceClass === null || deviceClass === 'distance') && Math.abs(number) >= 1000) {
    const km = formatDecimal(scaleDown(value, 3), 0, precision ?? 1);
    return km === null ? joinUnit(value, unit) : `${km} km`;
  }
  const [min, max] = precision !== null ? [precision, precision] : [0, defaultDecimals(deviceClass, unit)];
  const formatted = formatDecimal(value, min, max) ?? value;
  const shownUnit = repeatsName(unit, name) ? '' : countUnit(unit, formatted);
  return joinUnit(formatted, shownUnit);
}

function joinUnit(value, unit) {
  if (unit.length === 0) return value;
  if (unit === '°') return `${value}°`;
  return `${value} ${unit}`;
}

const DECIMALS_BY_UNIT = new Map([
  ...['°C', '°F', '℃', '℉', 'K'].map((u) => [u, 1]),
  ['%', 0],
  ...['W', 'Wh', 'lx', 'ppm', 'ppb', 'µg/m³', 'μg/m³', 'mg/m³', 'dB', 'dBm', 'dBA', 'hPa', 'mbar', 'Pa', 'W/m²', 'mA', 'g', 'cm', 'mL'].map((u) => [u, 0]),
  ...['kW', 'MW', 'kWh', 'MWh', 'A', 'inHg', 'bar', 'm³'].map((u) => [u, 2]),
  ...['V', 'kPa', 'psi', 'km/h', 'mph', 'm/s', 'kn', 'ft/s', 'km', 'mi', 'mm', 'in', 'mm/h', 'in/h', 'L', 'gal', 'kg', 'lb'].map((u) => [u, 1]),
]);
const DECIMALS_BY_CLASS = { temperature: 1, humidity: 0, battery: 0, moisture: 0, illuminance: 0, aqi: 0 };

function defaultDecimals(deviceClass, unit) {
  if (DECIMALS_BY_UNIT.has(unit)) return DECIMALS_BY_UNIT.get(unit);
  if (deviceClass !== null && Object.hasOwn(DECIMALS_BY_CLASS, deviceClass)) return DECIMALS_BY_CLASS[deviceClass];
  return 2;
}

/** `formatDecimal`: HALF_UP to [maxDecimals], trailing zeros trimmed down to [minDecimals]. */
export function formatDecimal(raw, minDecimals, maxDecimals) {
  const rounded = roundDecimal(raw, maxDecimals);
  if (rounded === null) return null;
  if (maxDecimals <= minDecimals || !rounded.includes('.')) return rounded;
  const [whole, fraction] = rounded.split('.');
  const kept = fraction.replace(/0+$/, '').padEnd(minDecimals, '0');
  return kept.length === 0 ? whole : `${whole}.${kept}`;
}

/** Decimal-string HALF_UP rounding, exactly as `HaStateFormat.kt` does it. */
function roundDecimal(raw, decimals) {
  const match = DECIMAL.exec(raw);
  if (!match || !isNumber(raw)) return null;
  const integer = match[2];
  const fraction = match[3] ?? '';
  const exponentText = match[4] ?? '';
  const exponent = exponentText.length === 0 ? 0 : Number.parseInt(exponentText, 10);
  // Kotlin's `toIntOrNull()`: an exponent outside Int is no number this can round.
  if (!Number.isSafeInteger(exponent) || exponent > 2147483647 || exponent < -2147483648) return null;
  const zero = decimals === 0 ? '0' : `0.${'0'.repeat(decimals)}`;
  if (exponent < -400) return zero;
  if (exponent > 400) return null;
  const digits = integer + fraction;
  if (digits.length === 0) return null;
  const decimalAt = integer.length + exponent;
  let whole;
  if (decimalAt <= 0) whole = '0';
  else if (decimalAt >= digits.length) whole = digits + '0'.repeat(Math.min(decimalAt - digits.length, 400));
  else whole = digits.substring(0, decimalAt);
  whole = whole.replace(/^0+/, '') || '0';
  let fractional;
  if (decimalAt <= 0) fractional = '0'.repeat(Math.min(-decimalAt, 400)) + digits;
  else if (decimalAt >= digits.length) fractional = '';
  else fractional = digits.substring(decimalAt);
  fractional = fractional.padEnd(decimals + 1, '0');
  let scaled = (whole + fractional.substring(0, decimals)).replace(/^0+/, '') || '0';
  if (fractional.charAt(decimals) >= '5') {
    const chars = scaled.split('');
    let carry = true;
    for (let i = chars.length - 1; i >= 0 && carry; i--) {
      if (chars[i] === '9') chars[i] = '0';
      else {
        chars[i] = String.fromCharCode(chars[i].charCodeAt(0) + 1);
        carry = false;
      }
    }
    scaled = (carry ? '1' : '') + chars.join('');
  }
  const padded = scaled.padStart(decimals + 1, '0');
  const result = decimals === 0 ? padded : `${padded.slice(0, -decimals)}.${padded.slice(-decimals)}`;
  const isZero = /^[0.]*$/.test(result);
  return match[1] === '-' && !isZero ? `-${result}` : result;
}

/** [raw] divided by 10^[places]: only the exponent moves. */
function scaleDown(raw, places) {
  const match = DECIMAL.exec(raw);
  if (!match) return raw;
  const exponent = match[4] ? Number.parseInt(match[4], 10) : 0;
  const mantissa = raw.split(/[eE]/)[0];
  return `${mantissa}e${exponent - places}`;
}

// Currency -----------------------------------------------------------------------------------

const CURRENCY_SYMBOLS = {
  GBP: '£', EUR: '€', USD: '$', JPY: '¥', CNY: 'CN¥', INR: '₹', KRW: '₩', AUD: 'A$', CAD: 'CA$',
  NZD: 'NZ$', HKD: 'HK$', MXN: 'MX$', BRL: 'R$', ILS: '₪', VND: '₫', TWD: 'NT$', PHP: '₱',
};
const CURRENCY_CODES = new Set([
  ...Object.keys(CURRENCY_SYMBOLS),
  'CHF', 'SEK', 'NOK', 'DKK', 'PLN', 'CZK', 'HUF', 'RON', 'BGN', 'ZAR', 'SGD', 'THB', 'TRY', 'IDR',
  'MYR', 'AED', 'SAR', 'ISK',
]);
const ZERO_DECIMAL_CURRENCIES = new Set(['JPY', 'KRW', 'VND', 'ISK', 'HUF']);
const CURRENCY_UNIT = /^([A-Z]{3})(?:\s*\/\s*(.+))?$/;

function currencyOf(unit, deviceClass) {
  const match = CURRENCY_UNIT.exec(unit);
  if (!match) return null;
  const code = match[1];
  if (!CURRENCY_CODES.has(code) && deviceClass !== 'monetary') return null;
  return { code, per: match[2] && match[2].length > 0 ? match[2] : null };
}

/** `£3.84`, `£0.25/kWh`, `CHF 3.84`. */
function formatMoney(value, currency, precision) {
  const fixed = ZERO_DECIMAL_CURRENCIES.has(currency.code) ? 0 : 2;
  let min;
  let max;
  if (precision !== null) [min, max] = [precision, precision];
  else if (currency.per !== null) [min, max] = [fixed, Math.max(fixed, 4)];
  else [min, max] = [fixed, fixed];
  const amount = formatDecimal(value, min, max) ?? value;
  const negative = amount.startsWith('-');
  const magnitude = negative ? amount.substring(1) : amount;
  const symbol = CURRENCY_SYMBOLS[currency.code];
  const money = symbol !== undefined ? `${symbol}${magnitude}` : `${currency.code} ${magnitude}`;
  const per = currency.per !== null ? `/${currency.per}` : '';
  return (negative ? '-' : '') + money + per;
}

// Durations, bearings, names, counts ---------------------------------------------------------

const SECOND_UNITS = new Set(['s', '', 'ms', 'µs', 'μs']);

/** `1 h 05 min`, `5 min`, `45 s`, `2 d 3 h`; negative is NO_VALUE; null for an unknown unit. */
function formatDuration(value, unit) {
  let seconds;
  switch (unit) {
    case 'd': seconds = value * DAY; break;
    case 'h': seconds = value * HOUR; break;
    case 'min': seconds = value * MINUTE; break;
    case 's':
    case '': seconds = value; break;
    case 'ms': seconds = value / 1000; break;
    case 'µs':
    case 'μs': seconds = value / 1_000_000; break;
    default: return null;
  }
  if (seconds < 0) return NO_VALUE;
  const total = roundHalfEven(seconds);
  const pad = (n) => String(n).padStart(2, '0');
  if (total < MINUTE) return `${total} s`;
  if (total < HOUR) {
    const rest = total % MINUTE;
    const minutes = Math.floor(total / MINUTE);
    return rest === 0 || !SECOND_UNITS.has(unit) ? `${minutes} min` : `${minutes} min ${pad(rest)} s`;
  }
  if (total < DAY) {
    const minutes = Math.floor((total % HOUR) / MINUTE);
    const hours = Math.floor(total / HOUR);
    return minutes === 0 ? `${hours} h` : `${hours} h ${pad(minutes)} min`;
  }
  const hours = Math.floor((total % DAY) / HOUR);
  const days = Math.floor(total / DAY);
  return hours === 0 ? `${days} d` : `${days} d ${hours} h`;
}

const COMPASS = ['N', 'NNE', 'NE', 'ENE', 'E', 'ESE', 'SE', 'SSE', 'S', 'SSW', 'SW', 'WSW', 'W', 'WNW', 'NW', 'NNW'];

/** The 16-point compass point for a bearing in degrees. */
export function compassPoint(degrees) {
  const normalized = (((degrees % 360) + 360) % 360);
  return COMPASS[Math.trunc((normalized + 11.25) / 22.5) % 16];
}

function mentionsBearing(name) {
  if (name === null) return false;
  const lower = name.toLowerCase();
  return lower.includes('bearing') || lower.includes('direction');
}

/** A unit the entity's name already ends with (`UV index` on "Garden UV index"): shown once. */
function repeatsName(unit, name) {
  if (name === null || unit.length < 3 || !/\p{L}/u.test(unit)) return false;
  const lowerName = name.trim().toLowerCase();
  const lowerUnit = unit.toLowerCase();
  if (!lowerName.endsWith(lowerUnit)) return false;
  const before = lowerName.length - lowerUnit.length - 1;
  return before < 0 || !/[\p{L}\p{Nd}]/u.test(lowerName.charAt(before));
}

const COUNT_UNITS = new Map([
  ...['alert', 'commit', 'cycle', 'device', 'download', 'email', 'error', 'event', 'file', 'follower', 'fork',
    'issue', 'item', 'message', 'notification', 'package', 'pull request', 'request', 'star', 'step', 'task',
    'time', 'update', 'visitor', 'warning', 'watcher'].map((one) => [one, `${one}s`]),
  ['entity', 'entities'],
  ['person', 'people'],
]);
const COUNT_SINGULAR = new Map([...COUNT_UNITS].map(([one, many]) => [many, one]));

/** `1 star`, `2 stars`: a known count unit agreeing with [formatted]; any other unit unchanged. */
function countUnit(unit, formatted) {
  const singular = COUNT_SINGULAR.get(unit) ?? (COUNT_UNITS.has(unit) ? unit : null);
  if (singular === null) return unit;
  return formatted === '1' || formatted === '-1' ? singular : COUNT_UNITS.get(singular);
}

const ON_OFF = new Set(['light', 'switch', 'input_boolean', 'fan', 'siren', 'humidifier', 'binary_sensor']);
const MEDIA_INACTIVE = new Set(['off', 'idle', 'standby', 'paused', 'unavailable']);
const MEDIA_KNOWN = new Set(['playing', 'paused', 'idle', 'off', 'on', 'standby', 'buffering', 'unavailable']);
const CLIMATE_ACTIVE = new Set(['heat', 'cool', 'heat_cool', 'auto', 'dry', 'fan_only']);
const ALARM_ACTIVE = new Set([
  'armed_home', 'armed_away', 'armed_night', 'armed_vacation', 'armed_custom_bypass',
  'triggered', 'pending', 'arming',
]);

/** `HaEntity.isActive`, null for domains without an active concept. */
function isActive(domain, state) {
  if (ON_OFF.has(domain)) return state === 'on';
  switch (domain) {
    case 'cover':
      return state === 'open' || state === 'opening' || state === 'closing';
    case 'lock':
      return state === 'locked';
    case 'media_player':
      // An unrecognised raw state parses to MediaPlayerState.Unknown, which is inactive.
      return MEDIA_KNOWN.has(state) && !MEDIA_INACTIVE.has(state);
    case 'climate':
      return CLIMATE_ACTIVE.has(state);
    case 'alarm_control_panel':
      return ALARM_ACTIVE.has(state);
    case 'vacuum':
      return state === 'cleaning' || state === 'returning';
    case 'person':
    case 'device_tracker':
      return state === 'home';
    default:
      return null;
  }
}

/** `HaLiveBindings.isOn` ↔ `<slot>.is_on`. */
export function isOn(entityId, state) {
  return isActive(domainOf(entityId), state) ?? (state === 'on' || state === 'open');
}

/** `HaLiveBindings.isAvailable` ↔ `<slot>.is_available`. */
export function isAvailable(state) {
  return state !== 'unavailable' && state !== 'unknown';
}

/** `AlarmStateInt`: wire keys, never reordered. */
const ALARM_STATE_INT = {
  disarmed: 0, armed_home: 1, armed_away: 2, armed_night: 3, armed_vacation: 4,
  armed_custom_bypass: 5, triggered: 6, pending: 7, arming: 8, disarming: 9, unavailable: 10,
};

/** `HaLiveBindings.coverStateInt`: wire keys, never reordered; anything else is 5 (unknown). */
const COVER_STATE_INT = { closed: 0, open: 1, opening: 2, closing: 3, unavailable: 4 };

/** `HaLiveBindings.stateInt` ↔ `<slot>.state_int`; null when the domain has no int key. */
export function stateInt(entityId, state) {
  switch (domainOf(entityId)) {
    case 'alarm_control_panel':
      return Object.hasOwn(ALARM_STATE_INT, state) ? ALARM_STATE_INT[state] : 11;
    case 'cover':
      return Object.hasOwn(COVER_STATE_INT, state) ? COVER_STATE_INT[state] : 5;
    default:
      return null;
  }
}

// Kotlin's toFloatOrNull follows Java's Float.valueOf grammar: surrounding control/space
// characters, an optional f/F/d/D suffix. Hexadecimal floats are not supported here; no Home
// Assistant state is one.
const JAVA_DECIMAL = /^[\x00-\x20]*[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?[fFdD]?[\x00-\x20]*$/;

/** `HaLiveBindings.numericState` ↔ `<slot>.numeric_state` (a 32-bit float), null if none. */
export function numericState(state) {
  if (!JAVA_DECIMAL.test(state)) return null;
  // eslint-disable-next-line no-control-regex
  const text = state.replace(/^[\x00-\x20]+|[\x00-\x20]+$/g, '').replace(/[fFdD]$/, '');
  const value = Math.fround(Number(text));
  return Number.isFinite(value) ? value : null;
}

/**
 * `HaLiveBindings.lastChanged` ↔ `<slot>.last_changed`: HA's `last_changed` as whole Unix seconds,
 * 0 when unknown. The document formats "5 min ago" from it against the player's own clock.
 */
export function lastChanged(entity) {
  const seconds = entity ? entity.last_changed : undefined;
  return typeof seconds === 'number' && Number.isFinite(seconds) ? Math.floor(seconds) : 0;
}

/**
 * `HaLiveBindings.lastChangedOffset` ↔ `<slot>.last_changed_offset`: the UTC offset in force at
 * `last_changed` in the browser's time zone, in seconds; 0 when unknown. The document dates a week-old
 * change with it, so a daylight-saving switch since doesn't move the change to the next day.
 */
export function lastChangedOffset(entity) {
  const seconds = lastChanged(entity);
  // `0 -`, not unary minus: a zero offset must be 0, not -0.
  return seconds === 0 ? 0 : 0 - new Date(seconds * 1000).getTimezoneOffset() * 60;
}

/** `CardDesignSlots.statusOf` ↔ `<slot>.status`. */
export function statusOf(entity) {
  const state = entity ? entity.state : null;
  if (state === null || state === undefined) return STATUS_MISSING;
  if (state === 'unavailable') return STATUS_UNAVAILABLE;
  if (state === 'unknown') return STATUS_UNKNOWN;
  return STATUS_AVAILABLE;
}

/**
 * `CardDesignSlots.slotEntries`: the entities a card binds, in slot order, each with the config
 * object it came from (for its `tap_action`). Null when the card binds none.
 */
export function slotEntries(card) {
  if (!isObject(card)) return null;
  const entity = content(card.entity);
  if (entity !== null) return [{ entityId: entity, name: content(card.name), config: card }];
  if (Array.isArray(card.cards)) {
    return card.cards.flatMap((child) => {
      if (!isObject(child) || content(child.type) === null) return [];
      return slotEntries(child) ?? [];
    });
  }
  if (Array.isArray(card.elements)) {
    return card.elements.flatMap((el) => {
      const id = isObject(el) ? content(el.entity) : null;
      return id === null ? [] : [{ entityId: id, name: null, config: el }];
    });
  }
  if (!Array.isArray(card.entities)) return null;
  return card.entities.flatMap((row) => {
    if (isObject(row)) {
      const id = content(row.entity);
      return id === null ? [] : [{ entityId: id, name: content(row.name), config: row }];
    }
    const id = content(row);
    return id === null ? [] : [{ entityId: id, name: null, config: null }];
  });
}

/** `CardDesignSlots.cardTitle`. */
export function cardTitle(card) {
  return content(card.heading) ?? content(card.title);
}

/** The distinct real entity ids a card binds: what the embed subscribes to. */
export function cardEntityIds(card) {
  return [...new Set((slotEntries(card) ?? []).map((e) => e.entityId))];
}

/**
 * `CardDesignSlots.slotBindings`: every binding a template reads, keyed by slot name, from
 * [states] (entity id → `{ state, attributes }`). Missing values get neutral defaults.
 */
export function slotBindings(card, states) {
  const strings = {};
  const booleans = {};
  const ints = {};
  const floats = {};
  const title = cardTitle(card);
  if (title !== null) strings[CARD_TITLE] = title;
  (slotEntries(card) ?? []).forEach((entry, i) => {
    const entityId = entry.entityId;
    const slot = slotId(domainOf(entityId), i);
    const state = states[entityId] ?? null;
    const name =
      entry.name ?? content(state && state.attributes ? state.attributes.friendly_name : null) ?? entityId;
    strings[`${slot}.state`] = formatState(state, entityId);
    strings[`${slot}.name`] = name;
    booleans[`${slot}.is_on`] = state ? isOn(entityId, state.state) : false;
    booleans[`${slot}.is_available`] = state ? isAvailable(state.state) : false;
    ints[`${slot}.state_int`] = (state ? stateInt(entityId, state.state) : null) ?? -1;
    ints[`${slot}.status`] = statusOf(state);
    ints[`${slot}.last_changed`] = lastChanged(state);
    ints[`${slot}.last_changed_offset`] = lastChangedOffset(state);
    floats[`${slot}.numeric_state`] = (state ? numericState(state.state) : null) ?? Number.NaN;
  });
  return { strings, booleans, ints, floats };
}

// ---------------------------------------------------------------------------------------------
// Binding recipes: a port of ha-model's `CardBindingRecipe`, a template's card-level bindings
// (`card.min`, `card.range`, …) as data.
// ---------------------------------------------------------------------------------------------

/** `CardBindingRecipe.BINDING`: the named string a template carries its recipe in. */
export const RECIPE_BINDING = 'meta.binding_recipe';
const RECIPE_VERSION = 1;
const JAVA_SPECIAL = /^[\x00-\x20]*([+-]?)(NaN|Infinity)[\x00-\x20]*$/;

/** Kotlin's `String.toDoubleOrNull` (decimal forms; hexadecimal floats read as null). */
function toDoubleOrNull(text) {
  const special = JAVA_SPECIAL.exec(text);
  if (special) return special[2] === 'NaN' ? Number.NaN : special[1] === '-' ? -Infinity : Infinity;
  if (!JAVA_DECIMAL.test(text)) return null;
  // eslint-disable-next-line no-control-regex
  const value = Number(text.replace(/^[\x00-\x20]+|[\x00-\x20]+$/g, '').replace(/[fFdD]$/, ''));
  return Number.isNaN(value) ? null : value;
}

/** `CardBindingRecipe.formatRangeNumber`: whole numbers bare, else one decimal, half up. */
export function formatRangeNumber(d) {
  if (!Number.isFinite(d)) return String(d);
  if (Number.isInteger(d)) return BigInt(d).toString();
  const tenths = Math.floor(d * 10 + 0.5);
  const sign = tenths < 0 ? '-' : '';
  const abs = Math.abs(tenths);
  return `${sign}${Math.floor(abs / 10)}.${abs % 10}`;
}

/** `CardBindingRecipe.rangeText`. */
export function rangeText(min, max, unit) {
  return `${formatRangeNumber(min)} – ${formatRangeNumber(max)} ${unit ?? ''}`;
}

/** `CardBindingRecipe.FUNCTIONS`. */
const RECIPE_FUNCTIONS = {
  range_text: ([min, max, unit]) =>
    typeof min === 'number' && typeof max === 'number' && typeof unit === 'string'
      ? rangeText(min, max, unit)
      : null,
};

/** `CardBindingRecipe.parse`: the recipe in [json] (text or object), or null. */
export function parseRecipe(json) {
  let recipe = json;
  if (typeof json === 'string') {
    try {
      recipe = JSON.parse(json);
    } catch {
      return null;
    }
  }
  if (!isObject(recipe) || recipe.v !== RECIPE_VERSION || typeof recipe.prototype !== 'string') return null;
  return {
    prototype: recipe.prototype,
    floats: isObject(recipe.floats) ? recipe.floats : {},
    strings: isObject(recipe.strings) ? recipe.strings : {},
  };
}

/** `CardBindingRecipe.evaluate`: the bindings [recipe] gives for [card] and [states]. */
export function recipeBindings(recipe, card, states) {
  const slots = (slotEntries(card) ?? []).map((e) => e.entityId);
  const values = new Map();
  const floats = {};
  const strings = {};
  const number = (v) => (typeof v === 'number' ? v : typeof v === 'string' ? toDoubleOrNull(v) : null);
  // kotlinx `intOrNull`: the primitive's text as an Int.
  const slotIndex = (slot) => {
    const text = content(slot);
    return text !== null && /^[+-]?\d{1,9}$/.test(text) ? Number(text) : null;
  };
  const entity = (slot) => {
    const index = slotIndex(slot);
    return index !== null && index >= 0 && index < slots.length ? states[slots[index]] ?? null : null;
  };
  const bindingName = (name) => {
    if (!name.startsWith('$')) return name;
    const dot = name.indexOf('.');
    const index = name.substring(1, dot < 0 ? name.length : dot);
    if (!/^[+-]?\d+$/.test(index)) return null;
    const entityId = slots[Number(index)];
    if (entityId === undefined) return null;
    const slot = slotId(domainOf(entityId), Number(index));
    return dot < 0 ? slot : slot + name.substring(dot);
  };
  const evaluate = (expr) => {
    if (expr === null || expr === undefined || Array.isArray(expr)) return null;
    if (typeof expr === 'string') return expr;
    if (typeof expr === 'number') return expr;
    if (typeof expr === 'boolean') return null;
    if ('config' in expr) {
      const path = typeof expr.config === 'string' || typeof expr.config === 'number' ? String(expr.config) : null;
      if (path === null) return null;
      let node = card;
      for (const part of path.split('.')) {
        if (!isObject(node) || !(part in node)) return null;
        node = node[part];
      }
      return content(node);
    }
    if ('state' in expr) {
      const state = entity(expr.state);
      return state ? state.state : null;
    }
    if ('attr' in expr) {
      const name = content(expr.attr);
      const state = entity('slot' in expr ? expr.slot : 0);
      if (name === null || !state || !isObject(state.attributes)) return null;
      return content(state.attributes[name]);
    }
    if ('number' in expr) return number(evaluate(expr.number));
    if ('first' in expr) {
      if (!Array.isArray(expr.first)) return null;
      for (const e of expr.first) {
        const v = evaluate(e);
        if (v !== null) return v;
      }
      return null;
    }
    if ('ref' in expr) return values.get(content(expr.ref)) ?? null;
    if ('fn' in expr) {
      const fn = Object.hasOwn(RECIPE_FUNCTIONS, content(expr.fn) ?? '') ? RECIPE_FUNCTIONS[content(expr.fn)] : null;
      const args = Array.isArray(expr.args) ? expr.args.map(evaluate) : [];
      return fn ? fn(args) : null;
    }
    return null;
  };
  for (const [name, expr] of Object.entries(recipe.floats)) {
    const value = number(evaluate(expr));
    const key = bindingName(name);
    if (value === null || key === null) continue;
    values.set(name, value);
    floats[key] = Math.fround(value);
  }
  for (const [name, expr] of Object.entries(recipe.strings)) {
    const v = evaluate(expr);
    const value = v === null ? null : typeof v === 'number' ? formatRangeNumber(v) : String(v);
    const key = bindingName(name);
    if (value === null || key === null) continue;
    values.set(name, value);
    strings[key] = value;
  }
  return { strings, floats };
}

/** Slot bindings with [recipe]'s card-level ones on top (`slotBindings + designBindings`). */
export function cardBindings(card, states, recipe) {
  const bindings = slotBindings(card, states);
  if (!recipe) return bindings;
  const extra = recipeBindings(recipe, card, states);
  return {
    ...bindings,
    strings: { ...bindings.strings, ...extra.strings },
    floats: { ...bindings.floats, ...extra.floats },
  };
}

/**
 * The host's ambient flag (`RcAmbientValue`, docs/architecture/ambient-mode.md): `1` while the
 * display is in a low-power ambient state. The display's state, not the card's data.
 */
export const AMBIENT_BINDING = 'host.ambient';

/** The flag's value for an `ambient` attribute: `true`/empty is 1, `false` is 0, absent is none. */
export function ambientValue(attribute) {
  if (attribute === null || attribute === undefined) return null;
  return attribute === 'false' ? 0 : 1;
}

const OPPOSITE_STATES = new Map([
  ['on', 'off'], ['On', 'Off'], ['open', 'closed'], ['Open', 'Closed'],
  ['locked', 'unlocked'], ['Locked', 'Unlocked'], ['home', 'not_home'], ['Home', 'Away'],
]);
for (const [a, b] of [...OPPOSITE_STATES]) OPPOSITE_STATES.set(b, a);

/**
 * Other values for a design's [values] (`{strings, floats, ints, booleans}`): each one changed so
 * that a design bound to it moves. A fraction swings across its range and stays in it, a number
 * grows, a flag or an on/off state flips; an entity's status and other text are kept. Changing them back is the same call
 * on the result's source, so a page toggles between the two.
 */
export function changedValues(values) {
  const floats = {};
  for (const [name, x] of Object.entries(values.floats ?? {})) {
    floats[name] = x >= 0 && x <= 1 ? (x <= 0.5 ? x + 0.4 : x - 0.4) : x * 1.5;
  }
  const ints = {};
  for (const [name, x] of Object.entries(values.ints ?? {})) {
    // A status stays available ([STATUS_AVAILABLE]): changing it would show a different card, not
    // the same card moving.
    ints[name] = name.endsWith('.status') ? x : x === 0 || x === 1 ? 1 - x : x + 1;
  }
  const booleans = {};
  for (const [name, x] of Object.entries(values.booleans ?? {})) booleans[name] = !x;
  const strings = {};
  for (const [name, x] of Object.entries(values.strings ?? {})) strings[name] = OPPOSITE_STATES.get(x) ?? x;
  return { strings, floats, ints, booleans };
}

// ---------------------------------------------------------------------------------------------
// Actions: a template only picks which of the card's own actions runs.
// ---------------------------------------------------------------------------------------------

const HA_ACTION_NAME = 'ha';
const HA_ACTION_TYPE = 'ee.schimke.ha.rc.components.HaAction.';

/** Decode an `ha` host action's JSON payload (`HaAction`, kotlinx polymorphic) or null. */
export function decodeTemplateAction(value) {
  if (typeof value !== 'string') return null;
  try {
    const action = JSON.parse(value);
    if (!isObject(action) || typeof action.type !== 'string') return null;
    if (!action.type.startsWith(HA_ACTION_TYPE)) return null;
    return { ...action, kind: action.type.substring(HA_ACTION_TYPE.length) };
  } catch {
    return null;
  }
}

/** `parseHaAction`: a Lovelace `tap_action` object; `{ kind: 'None' }` when it is none or broken. */
export function parseLovelaceAction(cfg, defaultEntityId) {
  if (!isObject(cfg)) return null;
  const none = { kind: 'None' };
  switch (content(cfg.action)) {
    case 'toggle': {
      const entityId = content(cfg.entity) ?? defaultEntityId;
      return entityId ? { kind: 'Toggle', entityId } : none;
    }
    case 'more-info': {
      const entityId = content(cfg.entity) ?? defaultEntityId;
      return entityId ? { kind: 'MoreInfo', entityId } : none;
    }
    case 'navigate': {
      const path = content(cfg.navigation_path);
      return path ? { kind: 'Navigate', path } : none;
    }
    case 'url': {
      const url = content(cfg.url_path);
      return url ? { kind: 'Url', url } : none;
    }
    case 'call-service':
    case 'perform-action': {
      const svc = content(cfg.perform_action ?? cfg.service);
      const dot = svc ? svc.indexOf('.') : -1;
      if (dot < 0) return none;
      const data = isObject(cfg.data) ? cfg.data : isObject(cfg.service_data) ? cfg.service_data : {};
      const target = isObject(cfg.target) ? cfg.target : null;
      const entityId =
        content(target ? target.entity_id : null) ??
        content(isObject(cfg.service_data) ? cfg.service_data.entity_id : null) ??
        defaultEntityId;
      return { kind: 'CallService', domain: svc.substring(0, dot), service: svc.substring(dot + 1), entityId, serviceData: data };
    }
    default:
      return none;
  }
}

const TOGGLE_DEFAULT = new Set(['light', 'switch', 'input_boolean', 'fan', 'cover', 'media_player', 'lock']);

/** `defaultTapActionFor`: HA's default tap action per domain. */
export function defaultTapActionFor(entityId) {
  if (!entityId) return { kind: 'None' };
  return TOGGLE_DEFAULT.has(domainOf(entityId)) ? { kind: 'Toggle', entityId } : { kind: 'MoreInfo', entityId };
}

const SLOT_ENTITY = /^[a-z0-9_]+\.s(\d+)$/;

/**
 * The card-local action for a tap whose template-encoded action is [value], the counterpart of
 * `CardTemplatePlayback.resolveTap`. A template is remote content, so what it encodes only picks a
 * slot: a tap on slot N runs that slot's own `tap_action`, or else its entity's domain default.
 * The template's action kind, service and service data are never run. Anything else runs the
 * card's own `tap_action` or its domain default.
 */
export function resolveTap(card, value) {
  const entries = slotEntries(card) ?? [];
  const first = entries.length > 0 ? entries[0].entityId : null;
  const cardAction = () => parseLovelaceAction(card.tap_action, first) ?? defaultTapActionFor(first);
  const templateAction = decodeTemplateAction(value);
  const slotMatch = templateAction && typeof templateAction.entityId === 'string'
    ? SLOT_ENTITY.exec(templateAction.entityId)
    : null;
  if (!slotMatch) return cardAction();
  const index = Number(slotMatch[1]);
  const entry = entries[index];
  if (!entry || slotId(domainOf(entry.entityId), index) !== templateAction.entityId) return cardAction();
  const real = entry.entityId;
  const configured = entry.config ? parseLovelaceAction(entry.config.tap_action, real) : null;
  return configured ?? defaultTapActionFor(real);
}

/** The `call_service` message for [action], or null when it is not a service call. */
export function serviceCallFor(action) {
  if (action.kind === 'Toggle') {
    return { domain: 'homeassistant', service: 'toggle', service_data: {}, target: { entity_id: action.entityId } };
  }
  if (action.kind === 'CallService') {
    const message = { domain: action.domain, service: action.service, service_data: action.serviceData ?? {} };
    if (action.entityId) message.target = { entity_id: action.entityId };
    return message;
  }
  return null;
}

/** Versioned form schema. Editing remains a player custom component, not a host-only control. */
export const MESSAGE_CARD_TYPE = 'custom:terrazzo-message';
export const MESSAGE_SUBMIT = 'ha:submit-inputs:v1';
export function messageInputs(card, states = {}) {
  if (!Array.isArray(card.inputs) || !card.inputs.length) throw new Error('Message card needs inputs');
  const seen = new Set();
  return card.inputs.map((field, index) => {
    if (!/^[a-z][a-z0-9_]*$/.test(field.key) || seen.has(field.key)) throw new Error('Invalid/duplicate input key');
    seen.add(field.key);
    const entity = states[field.entity];
    const type = field.type ?? (['select', 'input_select'].includes(domainOf(field.entity ?? '')) ? 'choice' : 'text');
    if (!['text', 'multiline', 'choice'].includes(type)) throw new Error(`Unsupported input type: ${type}`);
    const chrome = field.chrome ?? 'default';
    if (!['default', 'none'].includes(chrome)) throw new Error('Unsupported input chrome');
    const options = type === 'choice' ? (field.options ?? entity?.attributes?.options ?? []) : [];
    if (!Array.isArray(options) || (type === 'choice' && !options.length) ||
        options.some(o => typeof o !== 'string' || !o || o.includes('\n')) || new Set(options).size !== options.length) {
      throw new Error('Choice input needs unique, non-empty options');
    }
    return { key: field.key, label: field.label ?? field.key, value: entity?.state ?? field.value ?? '',
      options, chrome, multiline: type === 'multiline', submit: index === card.inputs.length - 1 ? MESSAGE_SUBMIT : '' };
  });
}
/** Exact data placeholders only. Never template service names or targets, or evaluate code. */
export function messageAction(card, states, drafts) {
  const values = {};
  for (const field of messageInputs(card, states)) {
    const value = drafts instanceof Map ? drafts.get(field.key) ?? field.value : drafts[field.key] ?? field.value;
    if (typeof value !== 'string' || value.length > 4096 || (field.options.length && !field.options.includes(value))) {
      throw new Error(`Enter a valid ${field.label}`);
    }
    values[field.key] = value;
  }
  const action = parseLovelaceAction(card.tap_action, null);
  if (action?.kind !== 'CallService') throw new Error('Message card needs a configured service action');
  const bind = value => {
    if (typeof value === 'string' && value.startsWith('{{input.') && value.endsWith('}}')) {
      const key = value.slice(8, -2);
      if (!Object.hasOwn(values, key)) throw new Error(`Unknown input ${key}`);
      return values[key];
    }
    if (Array.isArray(value)) return value.map(bind);
    if (isObject(value)) return Object.fromEntries(Object.entries(value).map(([key, v]) => [key, bind(v)]));
    return value;
  };
  return { ...action, serviceData: bind(action.serviceData) };
}

// ---------------------------------------------------------------------------------------------
// Home Assistant: OAuth (client_id = this page's origin) and the WebSocket API.
// ---------------------------------------------------------------------------------------------

const AUTH_CALLBACK_PARAM = 'ha_card_auth';
const STORAGE_PREFIX = 'ha-card-auth:';

export function normalizeHassUrl(url) {
  return new URL(url).origin;
}

function pageClientId() {
  return `${location.protocol}//${location.host}/`;
}

function pageRedirectUri() {
  const url = new URL(location.href);
  url.hash = '';
  for (const p of [AUTH_CALLBACK_PARAM, 'code', 'state']) url.searchParams.delete(p);
  url.searchParams.set(AUTH_CALLBACK_PARAM, '1');
  return url.toString();
}

function readTokens(hassUrl) {
  try {
    const raw = localStorage.getItem(STORAGE_PREFIX + hassUrl);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

function writeTokens(hassUrl, tokens) {
  try {
    if (tokens) localStorage.setItem(STORAGE_PREFIX + hassUrl, JSON.stringify(tokens));
    else localStorage.removeItem(STORAGE_PREFIX + hassUrl);
  } catch {
    // Storage blocked: the session lasts as long as the page.
  }
}

export class AuthError extends Error {}

async function tokenRequest(hassUrl, params) {
  const response = await fetch(`${hassUrl}/auth/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(params),
  });
  if (response.status === 400 || response.status === 403) throw new AuthError(`token request refused (${response.status})`);
  if (!response.ok) throw new Error(`token request failed (${response.status})`);
  const body = await response.json();
  return {
    hassUrl,
    clientId: params.client_id,
    access_token: body.access_token,
    refresh_token: body.refresh_token ?? params.refresh_token,
    expires: Date.now() + (body.expires_in ?? 1800) * 1000,
  };
}

/** One viewer's sign-in to one Home Assistant, shared by every card on the page that uses it. */
export class HaAuth {
  constructor(hassUrl) {
    this.hassUrl = hassUrl;
    this.tokens = readTokens(hassUrl);
    this.refreshing = null;
  }

  get signedIn() {
    return !!(this.tokens && this.tokens.refresh_token);
  }

  /** Send the viewer to Home Assistant's sign-in page; it comes back to this page with a code. */
  signIn() {
    const state = btoa(JSON.stringify({ hassUrl: this.hassUrl, clientId: pageClientId() }));
    const url = new URL(`${this.hassUrl}/auth/authorize`);
    url.searchParams.set('response_type', 'code');
    url.searchParams.set('client_id', pageClientId());
    url.searchParams.set('redirect_uri', pageRedirectUri());
    url.searchParams.set('state', state);
    location.assign(url.toString());
  }

  signOut() {
    this.tokens = null;
    writeTokens(this.hassUrl, null);
  }

  /** Finish a sign-in this page was redirected back from, if it was for this Home Assistant. */
  async completeSignIn() {
    const params = new URLSearchParams(location.search);
    if (params.get(AUTH_CALLBACK_PARAM) !== '1' || !params.get('code')) return false;
    let state;
    try {
      state = JSON.parse(atob(params.get('state') ?? ''));
    } catch {
      return false;
    }
    if (state.hassUrl !== this.hassUrl) return false;
    const code = params.get('code');
    exchanges[code] ??= tokenRequest(this.hassUrl, {
      grant_type: 'authorization_code',
      code,
      client_id: state.clientId,
    });
    try {
      this.tokens = await exchanges[code];
      writeTokens(this.hassUrl, this.tokens);
    } finally {
      const url = new URL(location.href);
      for (const p of [AUTH_CALLBACK_PARAM, 'code', 'state']) url.searchParams.delete(p);
      history.replaceState(history.state, '', url.toString());
    }
    return true;
  }

  /** A current access token, refreshed when it is about to expire. */
  async accessToken() {
    if (!this.tokens) throw new AuthError('not signed in');
    if (this.tokens.expires - Date.now() > 30_000) return this.tokens.access_token;
    this.refreshing ??= tokenRequest(this.hassUrl, {
      grant_type: 'refresh_token',
      refresh_token: this.tokens.refresh_token,
      client_id: this.tokens.clientId,
    }).finally(() => {
      this.refreshing = null;
    });
    try {
      this.tokens = await this.refreshing;
      writeTokens(this.hassUrl, this.tokens);
    } catch (e) {
      if (e instanceof AuthError) this.signOut();
      throw e;
    }
    return this.tokens.access_token;
  }
}

const exchanges = {};

/**
 * Applies one `subscribe_entities` event (HA's compressed state format) to [states], entity id →
 * `{ state, attributes, last_changed }` (`lc`, epoch seconds).
 */
export function applyEntitiesEvent(states, event) {
  if (event.a) {
    for (const [id, s] of Object.entries(event.a)) {
      states[id] = { state: s.s, attributes: s.a ?? {}, last_changed: s.lc };
    }
  }
  if (event.c) {
    for (const [id, diff] of Object.entries(event.c)) {
      const current = states[id];
      if (!current) continue;
      const next = { state: current.state, attributes: { ...current.attributes }, last_changed: current.last_changed };
      if (diff['+']) {
        if (diff['+'].s !== undefined) next.state = diff['+'].s;
        if (diff['+'].lc !== undefined) next.last_changed = diff['+'].lc;
        if (diff['+'].a) Object.assign(next.attributes, diff['+'].a);
      }
      if (diff['-'] && diff['-'].a) for (const key of diff['-'].a) delete next.attributes[key];
      states[id] = next;
    }
  }
  if (event.r) for (const id of event.r) delete states[id];
  return states;
}

/** One WebSocket to one Home Assistant, shared by the page's cards; reconnects and resubscribes. */
export class HaConnection {
  constructor(auth) {
    this.auth = auth;
    this.socket = null;
    this.nextId = 1;
    this.pending = new Map();
    this.subscriptions = new Set();
    this.ready = null;
    this.retryMs = 1000;
    this.listeners = new Set();
  }

  onState(state) {
    for (const listener of this.listeners) listener(state);
  }

  connect() {
    this.ready ??= this.open().catch((e) => {
      this.ready = null;
      throw e;
    });
    return this.ready;
  }

  async open() {
    const token = await this.auth.accessToken();
    const url = `${this.auth.hassUrl.replace(/^http/, 'ws')}/api/websocket`;
    const socket = new WebSocket(url);
    await new Promise((resolve, reject) => {
      socket.onmessage = (message) => {
        const msg = (this.decodeMessage ?? JSON.parse)(message.data);
        if (msg.type === 'auth_required') socket.send(JSON.stringify({ type: 'auth', access_token: token }));
        else if (msg.type === 'auth_ok') resolve();
        else if (msg.type === 'auth_invalid') {
          this.auth.signOut();
          reject(new AuthError(msg.message ?? 'auth invalid'));
        }
      };
      socket.onerror = () => reject(new Error('could not reach Home Assistant'));
      socket.onclose = () => reject(new Error('Home Assistant closed the connection'));
    });
    this.socket = socket;
    this.retryMs = 1000;
    socket.onmessage = (message) => this.receive((this.decodeMessage ?? JSON.parse)(message.data));
    socket.onclose = () => this.lost();
    for (const sub of this.subscriptions) this.startSubscription(sub);
    this.onState('live');
  }

  lost() {
    this.socket = null;
    this.ready = null;
    for (const { reject } of this.pending.values()) reject(new Error('connection lost'));
    this.pending.clear();
    if (this.subscriptions.size === 0) return;
    this.onState('reconnecting');
    const delay = this.retryMs;
    this.retryMs = Math.min(this.retryMs * 2, 30_000);
    setTimeout(() => {
      this.connect().catch((e) => {
        if (e instanceof AuthError) this.onState('signed-out');
        else this.lost();
      });
    }, delay);
  }

  receive(msg) {
    if (msg.type === 'result') {
      const pending = this.pending.get(msg.id);
      if (!pending) return;
      if (!pending.subscription) this.pending.delete(msg.id);
      if (msg.success) pending.resolve(msg.result);
      else pending.reject(new Error(msg.error ? msg.error.message : 'request failed'));
    } else if (msg.type === 'event') {
      const pending = this.pending.get(msg.id);
      if (pending && pending.subscription) pending.subscription.callback(msg.event);
    }
  }

  send(message, subscription) {
    if (!this.socket) return Promise.reject(new Error('not connected'));
    const id = this.nextId++;
    const promise = new Promise((resolve, reject) => this.pending.set(id, { resolve, reject, subscription }));
    this.socket.send(JSON.stringify({ ...message, id }));
    if (subscription) subscription.id = id;
    return promise;
  }

  startSubscription(sub) {
    this.send(sub.message, sub).catch(() => {});
  }

  /** `subscribe_entities` for [entityIds]; [callback] gets each compressed event. */
  async subscribeEntities(entityIds, callback) {
    const sub = { message: { type: 'subscribe_entities', entity_ids: entityIds }, callback, id: null };
    await this.connect();
    // Added once connected: a (re)connect starts every subscription already in the set.
    this.subscriptions.add(sub);
    this.startSubscription(sub);
    return () => {
      this.subscriptions.delete(sub);
      if (sub.id !== null) {
        this.pending.delete(sub.id);
        if (this.socket) this.send({ type: 'unsubscribe_events', subscription: sub.id }).catch(() => {});
      }
    };
  }

  async callService(call) {
    await this.connect();
    return this.send({ type: 'call_service', ...call });
  }
}

const sessions = new Map();

/** The page's shared sign-in and connection for [hassUrl]. */
export function sessionFor(hassUrl) {
  let session = sessions.get(hassUrl);
  if (!session) {
    const auth = new HaAuth(hassUrl);
    session = { auth, connection: new HaConnection(auth), callback: null };
    sessions.set(hassUrl, session);
  }
  return session;
}

// ---------------------------------------------------------------------------------------------
// Player
// ---------------------------------------------------------------------------------------------

let playerScript = null;

function loadPlayer(src) {
  if (globalThis.RC && globalThis.RC.RcdPlayer) return Promise.resolve(globalThis.RC);
  playerScript ??= new Promise((resolve, reject) => {
    const script = document.createElement('script');
    script.src = src;
    script.onload = () => resolve(globalThis.RC);
    script.onerror = () => reject(new Error(`could not load the player from ${src}`));
    document.head.appendChild(script);
  });
  return playerScript;
}

async function sha256Hex(bytes) {
  const digest = await crypto.subtle.digest('SHA-256', bytes);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

const SHA256 = /^[0-9a-f]{64}$/;

/** Fetch only template bytes; never send HA credentials or the embedding page's URL. */
export async function fetchTemplateUrl(value, baseUrl, fetcher = fetch) {
  const url = new URL(value, baseUrl);
  if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password) {
    throw new Error('template URL must be HTTP(S) without credentials');
  }
  const response = await fetcher(url, { credentials: 'omit', referrerPolicy: 'no-referrer' });
  if (!response.ok) throw new Error(`template URL request failed (${response.status})`);
  return response.arrayBuffer();
}

/** The template's bytes: by sha256, or by design key looked up in the library's manifest. */
async function fetchTemplate(library, design, designKey) {
  let sha = design;
  if (!sha && designKey) {
    const manifest = await (await fetch(new URL('manifest.json', library))).json();
    const want = JSON.stringify(Object.entries(designKey).sort());
    const entry = manifest.designs.find((d) => JSON.stringify(Object.entries(d.key).sort()) === want);
    if (!entry) throw new Error('no design in the library for this design key');
    sha = entry.sha256;
  }
  if (!sha || !SHA256.test(sha)) throw new Error('design must be a template sha256');
  const response = await fetch(new URL(`blobs/${sha}.rc`, library));
  if (!response.ok) throw new Error(`design ${sha.substring(0, 12)}… not found (${response.status})`);
  const bytes = await response.arrayBuffer();
  if (globalThis.crypto && crypto.subtle && (await sha256Hex(bytes)) !== sha) {
    throw new Error('design bytes do not match their sha256');
  }
  return bytes;
}

/**
 * The library design that draws [card], asked of the design server's resolver
 * (`POST /api/cards/resolve`, at [resolveUrl]): its sha256. Only the design is taken: the card's
 * slot values come live from [slotBindings], and the answer's other values are the library
 * example's, not this card's, so a template's own placeholders are left to show instead.
 */
export async function resolveDesign(resolveUrl, card, { cardStyle = 'classic', dark = false } = {}, fetchImpl = fetch) {
  // An element restarts as its attributes arrive: ask once per card, style and mode. A failure
  // is forgotten, so a later start asks again.
  const key = JSON.stringify([String(resolveUrl), card, cardStyle, dark]);
  if (!resolved.has(key)) {
    const pending = askResolver(resolveUrl, card, cardStyle, dark, fetchImpl);
    resolved.set(key, pending);
    pending.catch(() => resolved.delete(key));
  }
  return resolved.get(key);
}

const resolved = new Map();

async function askResolver(resolveUrl, card, cardStyle, dark, fetchImpl) {
  const response = await fetchImpl(resolveUrl, {
    method: 'POST',
    // A simple request: no CORS preflight.
    headers: { 'Content-Type': 'text/plain' },
    body: JSON.stringify({ card, cardStyle, dark }),
  });
  if (!response.ok) {
    const reason = (await response.text()).trim();
    throw new Error(`no design for this card${reason ? `: ${reason}` : ''} (${response.status})`);
  }
  const match = await response.json();
  if (!SHA256.test(match.sha256 ?? '')) throw new Error('the resolver returned no design');
  return { sha256: match.sha256, exact: match.exact !== false };
}

/**
 * TEMPORARY, until the upstream player fix in embed/upstream/remote-compose-player.patch ships:
 * the player draws text from its paint context's cache, which `setNamedStringOverride` does not
 * update and the document's own TextData puts back on every pass. Keep each overridden text in
 * that cache ourselves. Skipped on a player that already has the fix (`textOverrides`).
 */
function patchStringOverrides(context) {
  if (context.textOverrides instanceof Map || context.__haCardTexts) return;
  const texts = new Map();
  const paint = context.canvasPaintContext;
  const loadText = context.loadText.bind(context);
  context.loadText = (id, text) => {
    loadText(id, text);
    if (texts.has(id)) paint.loadText(id, texts.get(id));
  };
  const setNamed = context.setNamedStringOverride.bind(context);
  context.setNamedStringOverride = (name, value) => {
    const variable = context.namedVariables && context.namedVariables.get(name);
    if (variable && paint) {
      texts.set(variable.id, value);
      paint.loadText(variable.id, value);
    }
    setNamed(name, value);
  };
  context.__haCardTexts = texts;
}

/**
 * The binding recipe a loaded template carries ([RECIPE_BINDING]), or null: a template captured
 * from a converter whose card-level bindings are data (`CardConverter.designRecipe`).
 */
function templateRecipe(context) {
  const variable = context && context.namedVariables && context.namedVariables.get(`USER:${RECIPE_BINDING}`);
  if (!variable || variable.type !== 0) return null;
  try {
    return parseRecipe(context.getText(variable.id));
  } catch {
    return null;
  }
}

/** Height of the laid-out card in canvas pixels, read from the root layout; 0 if unknown. */
function contentHeight(player) {
  try {
    const root = player.getDocument().mRootLayoutComponent;
    let bottom = 0;
    for (const child of root.mChildren ?? []) {
      if (typeof child.mHeight === 'number') bottom = Math.max(bottom, (child.mY ?? 0) + child.mHeight);
    }
    return Math.ceil(bottom);
  } catch {
    return 0;
  }
}

// ---------------------------------------------------------------------------------------------
// <ha-card>
// ---------------------------------------------------------------------------------------------

const BaseElement = globalThis.HTMLElement ?? class {};

/**
 * Attributes:
 * - `hass`: the viewer's Home Assistant, e.g. `https://my-ha.example:8123`.
 * - `card`: the Lovelace card config, as JSON.
 * - `design`: the template's sha256 in the library; or `design-key`, a DesignKey as JSON. With
 *   neither, the design server picks the design that draws the card (`POST /api/cards/resolve`).
 * - `card-style`: with neither, the card style to draw in (an `HaUberTheme` id; default `classic`).
 * - `library`: the design library (default: `/designs/v1/` on the host serving this script).
 * - `template-url`: a direct HTTP(S) RC template URL, relative to the embedding page if needed.
 *   Bypasses library lookup; cannot be combined with `design` or `design-key`.
 * - `theme`: `light`, `dark` or `auto` (default).
 * - `height`: a fixed CSS height in px (default: the card's own).
 * - `read-only`: taps run no service calls.
 * - `require-host-bindings`: reject templates declaring data this host cannot populate.
 * - `ambient`: the display is in a low-power ambient state (`true`) or not (`false`); written to a
 *   design that reads the host's ambient flag. Absent, the design keeps its own value. Changing it
 *   doesn't reload the card, so the design's own transition plays.
 *
 * Events (bubbling, composed): `ha-card-action` (cancelable, before a tap runs: detail is the
 * action), `ha-card-more-info` and `ha-card-navigate` (actions a page handles itself). The
 * element's `data-status` is `loading`, `signed-out`, `connecting`, `live`, `reconnecting` or
 * `error`.
 */
export class HaCardElement extends BaseElement {
  static get observedAttributes() {
    return ['hass', 'card', 'design', 'design-key', 'template-url', 'card-style', 'library', 'theme', 'height', 'require-host-bindings', 'read-only', 'ambient'];
  }

  constructor() {
    super();
    this.states = {};
    this.pushed = new Map();
    this.inputDrafts = new Map();
    this.generation = 0;
    this.player = null;
    this.unsubscribe = null;
    this.localStates = null;
    this.localValues = null;
    const root = this.attachShadow({ mode: 'open' });
    root.innerHTML = `
      <style>
        :host { display: block; position: relative; }
        .player { position: relative; }
        canvas { display: block; width: 100%; }
        .status { font: 14px system-ui, sans-serif; padding: 8px 0; color: #555; }
        .status:empty { display: none; }
        button { font: inherit; cursor: pointer; }
      </style>
      <div class="player"><canvas part="canvas"></canvas></div>
      <div class="status" part="status"></div>`;
    this.canvas = root.querySelector('canvas');
    this.statusLine = root.querySelector('.status');
  }

  connectedCallback() {
    this.start();
    // A relative time ("3 h ago") moves with the clock, not the entity: while the card shows one,
    // re-derive the bindings on a tick, as the app's dashboard does. Only changed values are
    // pushed, so a tick that rolls no label over repaints nothing.
    this.clockTimer = setInterval(() => {
      const card = this.card;
      if (card && cardEntityIds(card).some((id) => isRelativeTimeState(this.states[id], id))) this.render();
    }, RELATIVE_TIME_TICK_MS);
    if (globalThis.ResizeObserver) {
      this.resizer = new ResizeObserver(() => this.fit());
      this.resizer.observe(this);
    }
  }

  disconnectedCallback() {
    this.stop();
    clearInterval(this.clockTimer);
    if (this.resizer) this.resizer.disconnect();
  }

  attributeChangedCallback(name, previous, next) {
    if (previous === next || !this.isConnected) return;
    // The host's state, pushed into the running card: a reload would skip the design's transition.
    if (name === 'ambient') this.render();
    else this.start();
  }

  /** Render from these states instead of a Home Assistant: entity id → `{ state, attributes }`. */
  setStates(states) {
    this.localStates = states;
    // States again, not a design's values ([setValues]).
    this.localValues = null;
    // Without any Home Assistant: no more events over these states, and no service calls.
    this.detachHass();
    this.states = { ...states };
    this.render();
  }

  /**
   * Play the design with these values, as its named bindings (`{strings, floats, ints, booleans}`,
   * by binding name) rather than derived from entity states: a design library's own page showing a
   * design with its example values. No Home Assistant is used.
   */
  setValues(values) {
    this.localValues = values;
    this.localStates ??= {};
    this.detachHass();
    this.render();
  }

  get card() {
    try {
      const card = JSON.parse(this.getAttribute('card') ?? 'null');
      return isObject(card) ? card : null;
    } catch {
      return null;
    }
  }

  stop() {
    this.generation++;
    this.detachHass();
    if (this.player) this.player.destroy();
    this.player = null;
  }

  /** Drop this card's subscription, connection listener, pending retry and session. */
  detachHass() {
    if (this.retryTimer) clearTimeout(this.retryTimer);
    this.retryTimer = null;
    if (this.unsubscribe) this.unsubscribe();
    this.unsubscribe = null;
    if (this.stateListener && this.session) this.session.connection.listeners.delete(this.stateListener);
    this.stateListener = null;
    this.session = null;
  }

  setStatus(status, message = '', action = null) {
    this.dataset.status = status;
    this.statusLine.textContent = message;
    if (action) {
      const button = document.createElement('button');
      button.textContent = action.label;
      button.addEventListener('click', action.run);
      this.statusLine.append(' ', button);
    }
  }

  async start() {
    this.stop();
    const generation = this.generation;
    const current = () => generation === this.generation;
    const card = this.card;
    if (!this.localStates) this.states = {};
    if (!card) return this.setStatus('error', 'ha-card: the card attribute is not a JSON card config.');
    this.setStatus('loading');
    try {
      if (card.type === MESSAGE_CARD_TYPE) {
        const RC = await loadPlayer(new URL('rc-player.js', import.meta.url).toString());
        if (!RC.inputDocument) throw new Error('Update the Remote Compose player for message inputs');
        const bytes = RC.inputDocument(messageInputs(card, this.states));
        if (!current()) return;
        await this.play(RC, bytes, card);
        if (this.localStates) return this.setStatus('live');
        await this.connectHass(card, current);
        return;
      }
      const library = new URL(this.getAttribute('library') ?? new URL('../../designs/v1/', import.meta.url));
      if (!library.pathname.endsWith('/')) library.pathname += '/';
      const designKey = this.getAttribute('design-key');
      let design = this.getAttribute('design');
      const templateUrl = this.getAttribute('template-url');
      if (templateUrl !== null && (design || designKey)) {
        throw new Error('template-url cannot be combined with design or design-key');
      }
      if (templateUrl !== null && !templateUrl.trim()) throw new Error('template-url must not be empty');
      if (templateUrl === null && !design && !designKey) {
        // Neither given: the library's own server picks the design for this card.
        const theme = this.getAttribute('theme');
        const dark =
          theme === 'dark' ||
          (theme !== 'light' && !!globalThis.matchMedia?.('(prefers-color-scheme: dark)').matches);
        const resolved = await resolveDesign(new URL('/api/cards/resolve', library), card, {
          cardStyle: this.getAttribute('card-style') ?? 'classic',
          dark,
        });
        if (!current()) return;
        design = resolved.sha256;
      }
      const [RC, bytes] = await Promise.all([
        loadPlayer(new URL('rc-player.js', import.meta.url).toString()),
        templateUrl !== null
          ? fetchTemplateUrl(templateUrl, document.baseURI)
          : fetchTemplate(library, design, designKey ? JSON.parse(designKey) : null),
      ]);
      if (!current()) return;
      await this.play(RC, bytes, card);
      if (!current()) return;
    } catch (e) {
      if (current()) this.setStatus('error', `ha-card: ${e.message}`);
      return;
    }
    if (this.localStates) return this.setStatus('live');
    await this.connectHass(card, current);
  }

  configureInputPlayer(player, _card) {
    player.configureInputs?.({ editable: false, drafts: this.inputDrafts });
  }

  async play(RC, bytes, card) {
    const dpr = globalThis.devicePixelRatio || 1;
    // Laid out tall first, so nothing is clipped, then fitted to the card's own height; hidden
    // until then.
    this.canvas.style.visibility = 'hidden';
    this.canvas.width = Math.round((this.canvas.parentElement?.clientWidth || this.clientWidth || 360) * dpr);
    this.canvas.height = Math.round(Number(this.getAttribute('height') || 1200) * dpr);
    const player = new RC.RcdPlayer(this.canvas);
    player.setDensity(dpr);
    this.configureInputPlayer(player, card);
    const theme = this.getAttribute('theme');
    if (theme === 'light' || theme === 'dark') player.setTheme(theme);
    const doc = await new Promise((resolve, reject) => {
      player.setOnLoad(resolve);
      player.loadFromArrayBuffer(bytes).catch(reject);
    });
    this.player = player;
    this.inputRuntime = RC;
    this.inputSignature = card.type === MESSAGE_CARD_TYPE ? JSON.stringify(messageInputs(card, this.states)) : null;
    this.pushed.clear();
    patchStringOverrides(player.getRemoteContext());
    this.recipe = templateRecipe(player.getRemoteContext());
    if (this.hasAttribute('require-host-bindings')) {
      const bindings = cardBindings(card, this.states, this.recipe);
      for (const [name, variable] of player.getRemoteContext().namedVariables) {
        const key = name.replace(/^USER:/, '');
        // Colors are template/theme constants. Every data declaration must have a host value;
        // leaving a converter-specific declaration alone would display the library's fixture.
        // The recipe declares the card-level values; it is read, never shown.
        const supported = variable.type === 2 ||
          key === AMBIENT_BINDING ||
          (variable.type === 0 && key === RECIPE_BINDING) ||
          (variable.type === 0 && Object.hasOwn(bindings.strings, key)) ||
          (variable.type === 1 && Object.hasOwn(bindings.floats, key)) ||
          (variable.type === 4 && (Object.hasOwn(bindings.ints, key) || Object.hasOwn(bindings.booleans, key)));
        if (!supported) {
          player.destroy();
          this.player = null;
          this.canvas.style.visibility = 'hidden';
          throw new Error('this template needs converter bindings unavailable in this host');
        }
      }
    }
    doc.addActionCallback({
      onAction: (name, value) => {
        if (name === HA_ACTION_NAME) this.tap(resolveTap(card, value));
      },
    });
    this.render();
    // Shown even when there was nothing to push: a design played with no values (a library
    // without catalog bindings) is still drawn.
    this.fit();
  }

  async connectHass(card, started) {
    // Live while this start is current and nobody has switched the card to local states.
    const current = () => started() && !this.localStates;
    const hass = this.getAttribute('hass');
    if (!hass) return this.setStatus('error', 'ha-card: set hass to your Home Assistant URL.');
    let hassUrl;
    try {
      hassUrl = normalizeHassUrl(hass);
    } catch {
      return this.setStatus('error', 'ha-card: hass is not a URL.');
    }
    const session = sessionFor(hassUrl);
    try {
      session.callback ??= session.auth.completeSignIn();
      await session.callback;
    } catch (e) {
      if (current()) this.setStatus('error', `ha-card: sign-in failed: ${e.message}`);
      return;
    }
    if (!current()) return;
    if (!session.auth.signedIn) {
      return this.setStatus('signed-out', '', {
        label: `Connect to Home Assistant (${new URL(hassUrl).host})`,
        run: () => session.auth.signIn(),
      });
    }
    this.setStatus('connecting');
    this.session = session;
    this.stateListener = (state) => {
      if (!current()) return;
      if (state === 'signed-out') this.start();
      else this.setStatus(state);
    };
    session.connection.listeners.add(this.stateListener);
    await this.subscribe(session, card, current, 1000);
  }

  /**
   * Subscribe to the card's entities, retrying a failed first connection with backoff; once
   * connected, [HaConnection] reconnects and resubscribes by itself.
   */
  async subscribe(session, card, current, delay) {
    const entityIds = cardEntityIds(card);
    // Home Assistant reads an empty `entity_ids` as no filter, which would stream every entity.
    if (entityIds.length === 0) return this.setStatus('live');
    try {
      const unsubscribe = await session.connection.subscribeEntities(entityIds, (event) => {
        if (!current()) return;
        applyEntitiesEvent(this.states, event);
        this.render();
      });
      if (!current()) return unsubscribe();
      this.unsubscribe = unsubscribe;
      this.setStatus('live');
    } catch (e) {
      if (!current()) return;
      if (e instanceof AuthError) return this.start();
      this.setStatus('reconnecting', `ha-card: ${e.message}; retrying in ${Math.round(delay / 1000)} s`);
      this.retryTimer = setTimeout(() => {
        this.retryTimer = null;
        if (current()) this.subscribe(session, card, current, Math.min(delay * 2, 30_000));
      }, delay);
    }
  }

  /** Push the card's slot bindings for the current states; only the ones that changed. */
  render() {
    const player = this.player;
    const card = this.card;
    if (!player || !card) return;
    if (card.type === MESSAGE_CARD_TYPE) {
      try {
        const fields = messageInputs(card, this.states);
        const signature = JSON.stringify(fields);
        if (signature !== this.inputSignature) {
          this.inputSignature = signature;
          player.loadFromArrayBuffer(this.inputRuntime.inputDocument(fields)).then(() => {
            if (this.player === player) { player.repaint(); this.fit(); }
          }).catch(error => this.setStatus('error', error.message));
        }
        this.fit();
      } catch (error) { this.setStatus('error', error.message); }
      return;
    }
    const context = player.getRemoteContext();
    if (!context) return;
    const bindings = this.localValues ?? cardBindings(card, this.states, this.recipe);
    const push = (name, value, set) => {
      const key = `USER:${name}`;
      if (Object.is(this.pushed.get(key), value)) return false;
      this.pushed.set(key, value);
      set(key, value);
      return true;
    };
    let changed = false;
    for (const [n, v] of Object.entries(bindings.strings ?? {})) changed = push(n, v, (k, x) => context.setNamedStringOverride(k, x)) || changed;
    // A RemoteBoolean is an int (0/1) in the document.
    for (const [n, v] of Object.entries(bindings.booleans ?? {})) changed = push(n, v ? 1 : 0, (k, x) => context.setNamedIntegerOverride(k, x)) || changed;
    for (const [n, v] of Object.entries(bindings.ints ?? {})) changed = push(n, v, (k, x) => context.setNamedIntegerOverride(k, x)) || changed;
    for (const [n, v] of Object.entries(bindings.floats ?? {})) changed = push(n, v, (k, x) => context.setNamedFloatOverride(k, x)) || changed;
    const ambient = ambientValue(this.getAttribute('ambient'));
    if (ambient !== null && context.namedVariables?.has(`USER:${AMBIENT_BINDING}`)) {
      changed = push(AMBIENT_BINDING, ambient, (k, x) => context.setNamedFloatOverride(k, x)) || changed;
    }
    if (!changed) return;
    const doc = player.getDocument();
    if (doc && doc.invalidateMeasure) doc.invalidateMeasure();
    player.repaint();
    this.fit();
  }

  /** Size the canvas to the element's width and the card's own height. */
  fit() {
    const player = this.player;
    if (!player) return;
    const dpr = globalThis.devicePixelRatio || 1;
    const width = Math.round((this.canvas.parentElement?.clientWidth || this.clientWidth || 360) * dpr);
    const fixed = Number(this.getAttribute('height'));
    if (width !== this.canvas.width) player.resize(width, this.canvas.height);
    const height = fixed > 0 ? Math.round(fixed * dpr) : contentHeight(player);
    if (height > 0 && height !== this.canvas.height) player.resize(width, height);
    this.canvas.style.visibility = '';
  }

  /** Run a resolved tap: a service call through the viewer's connection, or an event. */
  async tap(action) {
    if (!action || action.kind === 'None') return;
    const event = new CustomEvent('ha-card-action', { detail: action, bubbles: true, composed: true, cancelable: true });
    if (!this.dispatchEvent(event)) return;
    if (action.kind === 'MoreInfo') {
      this.dispatchEvent(new CustomEvent('ha-card-more-info', { detail: { entityId: action.entityId }, bubbles: true, composed: true }));
      return;
    }
    if (action.kind === 'Navigate') {
      this.dispatchEvent(new CustomEvent('ha-card-navigate', { detail: { path: action.path }, bubbles: true, composed: true }));
      return;
    }
    if (action.kind === 'Url') {
      // Only from the card's own config; never a URL a template carries.
      if (/^https?:/i.test(action.url)) window.open(action.url, '_blank', 'noopener');
      return;
    }
    const call = serviceCallFor(action);
    if (!call || this.hasAttribute('read-only') || !this.session) return;
    try {
      await this.session.connection.callService(call);
    } catch (e) {
      this.setStatus(this.dataset.status, `ha-card: ${call.domain}.${call.service} failed: ${e.message}`);
    }
  }
}

// Hosts with their own <ha-card> (Home Assistant) import with ?register=false.
if (globalThis.customElements && !customElements.get('ha-card') &&
    new URL(import.meta.url).searchParams.get('register') !== 'false') {
  customElements.define('ha-card', HaCardElement);
}
