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

/** `formatState`: the display string the converters bake and the live binding pushes. */
export function formatState(entity) {
  const raw = entity ? entity.state : null;
  if (raw === null || raw === undefined) return 'Unavailable';
  if (raw === 'unavailable' || raw === 'unknown') return 'Unavailable';
  const unit = content(entity.attributes ? entity.attributes.unit_of_measurement : null);
  const normalized = normalizeNumericValue(raw);
  if (normalized !== null) return formatValueWithUnit(normalized, unit);
  const text = raw.replaceAll('_', ' ');
  if (text.length === 0) return text;
  const upper = text.charAt(0).toUpperCase();
  return (upper.length === 1 ? upper : text.charAt(0)) + text.substring(1);
}

export function formatValueWithUnit(value, unit) {
  const normalized = normalizeNumericValue(value) ?? value;
  const cleanUnit = (unit ?? '').trim();
  return cleanUnit.length === 0 ? normalized : `${normalized} ${cleanUnit}`;
}

const X_PLACEHOLDER = /^(-?\d+)\.[xX]+$/;
const DECIMAL = /^([+-]?)(\d*)(?:\.(\d*))?(?:[eE]([+-]?\d+))?$/;

/** Decimal-string HALF_UP rounding to two places, exactly as `HaStateFormat.kt` does it. */
function normalizeNumericValue(raw) {
  const placeholder = X_PLACEHOLDER.exec(raw);
  if (placeholder) return placeholder[1];
  const match = DECIMAL.exec(raw);
  // Kotlin first asks `toDoubleOrNull()?.isFinite()`. Every string DECIMAL accepts that
  // Kotlin parses, Number parses to the same double.
  if (!match || !Number.isFinite(Number(raw))) return null;
  const integer = match[2];
  const fraction = match[3] ?? '';
  const exponentText = match[4] ?? '';
  const exponent = exponentText.length === 0 ? 0 : Number.parseInt(exponentText, 10);
  if (!Number.isSafeInteger(exponent) || Math.abs(exponent) > 2147483647) return null;
  if (exponent < -400) return '0';
  if (exponent > 400) return null;
  const digits = integer + fraction;
  if (digits.length === 0) return null;
  const decimalAt = integer.length + exponent;
  let whole;
  if (decimalAt <= 0) whole = '0';
  else if (decimalAt >= digits.length)
    whole = digits + '0'.repeat(Math.min(decimalAt - digits.length, 400));
  else whole = digits.substring(0, decimalAt);
  whole = whole.replace(/^0+/, '') || '0';
  let fractional;
  if (decimalAt <= 0) fractional = '0'.repeat(Math.min(-decimalAt, 400)) + digits;
  else if (decimalAt >= digits.length) fractional = '';
  else fractional = digits.substring(decimalAt);
  fractional = fractional.padEnd(3, '0');
  let scaled = (whole + fractional.substring(0, 2)).replace(/^0+/, '') || '0';
  if (fractional.charAt(2) >= '5') {
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
  const padded = scaled.padStart(3, '0');
  const decimals = padded.slice(-2).replace(/0+$/, '');
  const result = padded.slice(0, -2) + (decimals.length === 0 ? '' : `.${decimals}`);
  return match[1] === '-' && result !== '0' ? `-${result}` : result;
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

/** `AlarmStateInt`: wire keys, never reordered. */
const ALARM_STATE_INT = {
  disarmed: 0, armed_home: 1, armed_away: 2, armed_night: 3, armed_vacation: 4,
  armed_custom_bypass: 5, triggered: 6, pending: 7, arming: 8, disarming: 9, unavailable: 10,
};

/** `HaLiveBindings.stateInt` ↔ `<slot>.state_int`; null when the domain has no int key. */
export function stateInt(entityId, state) {
  if (domainOf(entityId) !== 'alarm_control_panel') return null;
  return Object.hasOwn(ALARM_STATE_INT, state) ? ALARM_STATE_INT[state] : 11;
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
    strings[`${slot}.state`] = formatState(state);
    strings[`${slot}.name`] = name;
    booleans[`${slot}.is_on`] = state ? isOn(entityId, state.state) : false;
    ints[`${slot}.state_int`] = (state ? stateInt(entityId, state.state) : null) ?? -1;
    ints[`${slot}.status`] = statusOf(state);
    floats[`${slot}.numeric_state`] = (state ? numericState(state.state) : null) ?? Number.NaN;
  });
  return { strings, booleans, ints, floats };
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
 * `{ state, attributes }`.
 */
export function applyEntitiesEvent(states, event) {
  if (event.a) {
    for (const [id, s] of Object.entries(event.a)) states[id] = { state: s.s, attributes: s.a ?? {} };
  }
  if (event.c) {
    for (const [id, diff] of Object.entries(event.c)) {
      const current = states[id];
      if (!current) continue;
      const next = { state: current.state, attributes: { ...current.attributes } };
      if (diff['+']) {
        if (diff['+'].s !== undefined) next.state = diff['+'].s;
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
        const msg = JSON.parse(message.data);
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
    socket.onmessage = (message) => this.receive(JSON.parse(message.data));
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
 * - `theme`: `light`, `dark` or `auto` (default).
 * - `height`: a fixed CSS height in px (default: the card's own).
 * - `read-only`: taps run no service calls.
 *
 * Events (bubbling, composed): `ha-card-action` (cancelable, before a tap runs: detail is the
 * action), `ha-card-more-info` and `ha-card-navigate` (actions a page handles itself). The
 * element's `data-status` is `loading`, `signed-out`, `connecting`, `live`, `reconnecting` or
 * `error`.
 */
export class HaCardElement extends BaseElement {
  static get observedAttributes() {
    return ['hass', 'card', 'design', 'design-key', 'card-style', 'library', 'theme', 'height'];
  }

  constructor() {
    super();
    this.states = {};
    this.pushed = new Map();
    this.generation = 0;
    this.player = null;
    this.unsubscribe = null;
    this.localStates = null;
    const root = this.attachShadow({ mode: 'open' });
    root.innerHTML = `
      <style>
        :host { display: block; position: relative; }
        canvas { display: block; width: 100%; }
        .status { font: 14px system-ui, sans-serif; padding: 8px 0; color: #555; }
        .status:empty { display: none; }
        button { font: inherit; cursor: pointer; }
      </style>
      <canvas part="canvas"></canvas>
      <div class="status" part="status"></div>`;
    this.canvas = root.querySelector('canvas');
    this.statusLine = root.querySelector('.status');
  }

  connectedCallback() {
    this.start();
    if (globalThis.ResizeObserver) {
      this.resizer = new ResizeObserver(() => this.fit());
      this.resizer.observe(this);
    }
  }

  disconnectedCallback() {
    this.stop();
    if (this.resizer) this.resizer.disconnect();
  }

  attributeChangedCallback(_name, previous, next) {
    if (previous !== next && this.isConnected) this.start();
  }

  /** Render from these states instead of a Home Assistant: entity id → `{ state, attributes }`. */
  setStates(states) {
    this.localStates = states;
    // Without any Home Assistant: no more events over these states, and no service calls.
    this.detachHass();
    this.states = { ...states };
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
      const library = new URL(this.getAttribute('library') ?? new URL('../../designs/v1/', import.meta.url));
      if (!library.pathname.endsWith('/')) library.pathname += '/';
      const designKey = this.getAttribute('design-key');
      let design = this.getAttribute('design');
      if (!design && !designKey) {
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
        fetchTemplate(library, design, designKey ? JSON.parse(designKey) : null),
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

  async play(RC, bytes, card) {
    const dpr = globalThis.devicePixelRatio || 1;
    // Laid out tall first, so nothing is clipped, then fitted to the card's own height; hidden
    // until then.
    this.canvas.style.visibility = 'hidden';
    this.canvas.width = Math.round((this.clientWidth || 360) * dpr);
    this.canvas.height = Math.round(Number(this.getAttribute('height') || 1200) * dpr);
    const player = new RC.RcdPlayer(this.canvas);
    player.setDensity(dpr);
    const theme = this.getAttribute('theme');
    if (theme === 'light' || theme === 'dark') player.setTheme(theme);
    const doc = await new Promise((resolve, reject) => {
      player.setOnLoad(resolve);
      player.loadFromArrayBuffer(bytes).catch(reject);
    });
    this.player = player;
    this.pushed.clear();
    patchStringOverrides(player.getRemoteContext());
    doc.addActionCallback({
      onAction: (name, value) => {
        if (name === HA_ACTION_NAME) this.tap(resolveTap(card, value));
      },
    });
    this.render();
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
    const context = player.getRemoteContext();
    if (!context) return;
    const bindings = slotBindings(card, this.states);
    const push = (name, value, set) => {
      const key = `USER:${name}`;
      if (Object.is(this.pushed.get(key), value)) return false;
      this.pushed.set(key, value);
      set(key, value);
      return true;
    };
    let changed = false;
    for (const [n, v] of Object.entries(bindings.strings)) changed = push(n, v, (k, x) => context.setNamedStringOverride(k, x)) || changed;
    // A RemoteBoolean is an int (0/1) in the document.
    for (const [n, v] of Object.entries(bindings.booleans)) changed = push(n, v ? 1 : 0, (k, x) => context.setNamedIntegerOverride(k, x)) || changed;
    for (const [n, v] of Object.entries(bindings.ints)) changed = push(n, v, (k, x) => context.setNamedIntegerOverride(k, x)) || changed;
    for (const [n, v] of Object.entries(bindings.floats)) changed = push(n, v, (k, x) => context.setNamedFloatOverride(k, x)) || changed;
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
    const width = Math.round((this.clientWidth || 360) * dpr);
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

if (globalThis.customElements && !customElements.get('ha-card')) {
  customElements.define('ha-card', HaCardElement);
}
