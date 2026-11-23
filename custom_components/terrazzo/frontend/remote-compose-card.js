import { HaCardElement, serviceCallFor, hostValuesFromConfig, MESSAGE_CARD_TYPE, MESSAGE_SUBMIT, messageInputs, messageAction } from './ha-card.js?register=false';

/** A published Wear OS watch face as a card (`face`, optional `server` and `size`). */
export const WATCH_FACE_CARD_TYPE = 'watchface';
const WATCH_FACE_DEFAULT_SIZE = 450;

export const DEFAULT_LIBRARY = 'https://ha.coo.ee/designs/v1/';
const DOCUMENTATION_URL =
  'https://github.com/yschimke/homeassistant-remotecompose/blob/main/docs/HOME_ASSISTANT.md#add-a-remote-compose-lovelace-card';
/** The Home Assistant cards this card draws with live bindings, and the visual editor offers. */
export const BOUND_CARD_TYPES = ['tile', 'button', 'entity', 'gauge'];
/** `HaUberTheme` ids, as the visual editor offers them. */
const CARD_STYLES = [
  ['classic', 'Classic'], ['home-assistant', 'Home Assistant'], ['expressive', 'Material 3 Expressive'],
  ['braun', 'Braun'], ['lcd', 'LCD'], ['ikea', 'IKEA Showroom'], ['trinitron', 'Trinitron'],
  ['nixie', 'Nixie'], ['console', 'Console'],
];
const MAX_SUGGESTIONS = 3;
const DESIGN_ID = /^[^\u0000-\u001f]{1,256}$/;

/**
 * What Home Assistant knows about [entityId]'s device, from the registries the frontend already
 * holds (`hass.entities`, `hass.devices`): `DeviceInfo` in `ha-model`, the fields design matching
 * reads. Null for an entity the registry doesn't list.
 */
export function deviceInfo(hass, entityId) {
  const entity = hass?.entities?.[entityId];
  const deviceClass = hass?.states?.[entityId]?.attributes?.device_class ?? null;
  // An entity the registry doesn't list still has its state's class: a class design matches it.
  if (!entity) {
    return deviceClass == null ? null : {
      integration: null, manufacturer: null, model: null, translationKey: null, deviceClass,
    };
  }
  const device = entity.device_id ? hass.devices?.[entity.device_id] : undefined;
  return {
    integration: entity.platform ?? null,
    manufacturer: device?.manufacturer ?? null,
    model: device?.model ?? null,
    translationKey: entity.translation_key ?? null,
    // What kind of thing it is (`garage`), whoever made it: the state's attribute, as HA draws it.
    deviceClass,
  };
}

const MATCH_FIELDS = ['integration', 'manufacturer', 'model', 'translationKey', 'deviceClass'];

/** `DeviceMatch.matches` (`ha-model`): every field the match sets, without case; `*` in a model. */
export function deviceMatches(match, device) {
  const fields = MATCH_FIELDS;
  const set = fields.filter((field) => typeof match?.[field] === 'string' && match[field].trim());
  if (!device || set.length === 0) return false;
  return set.every((field) => {
    const wanted = match[field].trim().toLowerCase();
    const actual = device[field];
    if (typeof actual !== 'string') return false;
    if (field !== 'model') return wanted === actual.toLowerCase();
    const glob = wanted.split('*').map((part) => part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('.*');
    return new RegExp(`^${glob}$`, 'i').test(actual);
  });
}

/**
 * `DeviceMatch.rank` (`ha-model`): a model design over a brand design over a role design over a
 * class design (`deviceClass`: every garage door), however many fields each sets, then more fields
 * within a scope. Zero for an empty match.
 */
export function deviceRank(match) {
  const set = (field) => typeof match?.[field] === 'string' && match[field].trim() !== '';
  const fields = MATCH_FIELDS.filter(set).length;
  const scope = set('model') ? 4
    : set('integration') || set('manufacturer') ? 3
    : set('translationKey') ? 2
    : set('deviceClass') ? 1
    : 0;
  return scope === 0 ? 0 : scope * 8 + fields;
}

/** The card a suggestion draws [entityId] with: Home Assistant's own config for [cardType]. */
function suggestedCard(cardType, entityId) {
  return { type: cardType, entity: entityId };
}

/**
 * The picker's suggestions for [entityId] from the design library's device index
 * (`/designs/v1/suggestions.json`): the designs made for its device, most specific first, at most
 * a few, each as a Remote Compose card set to that design. None for an entity whose device has
 * nothing made for it: Home Assistant's own suggestions are the right ones there.
 */
export function entitySuggestions(hass, entityId, index) {
  const device = deviceInfo(hass, entityId);
  if (!device || !Array.isArray(index?.designs)) return [];
  const domain = entityId.split('.')[0];
  const seen = new Set();
  const matches = [];
  for (const design of index.designs) {
    if (!BOUND_CARD_TYPES.includes(design?.cardType) || typeof design.designId !== 'string') continue;
    if (!DESIGN_ID.test(design.designId) || !deviceMatches(design.device, device)) continue;
    // A design drawn for another domain (the printer's light, offered on its sensor) can't draw it.
    if (design.domain != null && design.domain !== domain) continue;
    const key = `${design.designId}\n${design.cardType}`;
    if (seen.has(key)) continue;
    seen.add(key);
    matches.push(design);
  }
  return matches
    .sort((a, b) => deviceRank(b.device) - deviceRank(a.device))
    .slice(0, MAX_SUGGESTIONS)
    .map((design) => ({
      config: {
        type: 'custom:remote-compose-card',
        card: suggestedCard(design.cardType, entityId),
        design_id: design.designId,
      },
      label: typeof design.title === 'string' && design.title.trim()
        ? design.title.trim()
        : `Made for this device (${design.cardType})`,
    }));
}

const suggestionIndexes = new Map();

/**
 * Start fetching [library]'s device index, once per page. The picker asks for suggestions
 * synchronously, so they come from this copy; it is fetched when an editor shows Remote Compose
 * (the card picker's preview, the visual editor) or an administrator's page is idle
 * ([preloadForEditors]); a page that can't edit dashboards never fetches it. The
 * request carries no cookies, referrer or Home Assistant data.
 */
export function loadSuggestionIndex(library = DEFAULT_LIBRARY, fetchImpl = globalThis.fetch) {
  const url = new URL('suggestions.json', library).toString();
  let entry = suggestionIndexes.get(url);
  if (!entry) {
    entry = { index: null };
    entry.pending = (async () => {
      try {
        const response = await fetchImpl(url, { credentials: 'omit', referrerPolicy: 'no-referrer' });
        if (response.ok) entry.index = await response.json();
      } catch (_) {
        // No index, no suggestions: the picker still has Home Assistant's own.
      }
      // A failure is forgotten, so the next editor hook asks again.
      if (entry.index == null) suggestionIndexes.delete(url);
      return entry.index;
    })();
    suggestionIndexes.set(url, entry);
  }
  return entry.pending;
}

function loadedSuggestionIndex(library = DEFAULT_LIBRARY) {
  return suggestionIndexes.get(new URL('suggestions.json', library).toString())?.index ?? null;
}

/**
 * Fetch the index ahead of the picker for someone who can edit dashboards: the picker's **By
 * entity** tab asks for suggestions before any other hook runs, and keeps its first answer for an
 * entity. Only administrators can save a dashboard, so only their pages fetch it, once, when idle.
 */
export function preloadForEditors(root = globalThis.document) {
  try {
    if (root?.querySelector?.('home-assistant')?.hass?.user?.is_admin) loadSuggestionIndex();
  } catch (_) {
    // Not Home Assistant's frontend: the editor hooks still load it.
  }
}

/** `getEntitySuggestion` for the card picker: synchronous, from the index already fetched. */
export function getEntitySuggestion(hass, entityId) {
  loadSuggestionIndex();
  const suggestions = entitySuggestions(hass, entityId, loadedSuggestionIndex());
  return suggestions.length ? suggestions : null;
}

const STUB_DOMAINS = ['light', 'switch', 'fan', 'input_boolean', 'sensor', 'binary_sensor'];

/** The entity a new card starts with: an unused one of a domain a tile shows well. */
export function stubEntity(hass, entities = [], entitiesFallback = []) {
  for (const list of [entities, entitiesFallback, Object.keys(hass?.states ?? {})]) {
    for (const domain of STUB_DOMAINS) {
      const found = (list ?? []).find((id) => typeof id === 'string' && id.startsWith(`${domain}.`));
      if (found) return found;
    }
  }
  return 'sun.sun';
}

/** The visual editor: Home Assistant's own card fields under `card`, and the card style. */
const CONFIG_FORM = {
  schema: [
    {
      name: 'card', type: 'expandable', title: 'Home Assistant card', expanded: true,
      schema: [
        { name: 'type', required: true, selector: { select: { mode: 'dropdown',
          options: BOUND_CARD_TYPES.map((value) => ({ value, label: value[0].toUpperCase() + value.slice(1) })) } } },
        { name: 'entity', required: true, selector: { entity: {} } },
        { name: 'name', selector: { text: {} } },
      ],
    },
    { name: 'card_style', selector: { select: { mode: 'dropdown',
      options: CARD_STYLES.map(([value, label]) => ({ value, label })) } } },
  ],
  computeLabel: (field) => ({
    type: 'Card type', entity: 'Entity', name: 'Name', card_style: 'Card style',
  })[field.name],
  computeHelper: (field) => (field.name === 'card_style'
    ? 'How Remote Compose draws the card. The card itself, its entity and its actions, stay a normal Home Assistant card.'
    : undefined),
  assertConfig: (config) => {
    if (!BOUND_CARD_TYPES.includes(config?.card?.type)) {
      throw new Error('This card type is edited in YAML.');
    }
    if (config.template_url != null || config.design != null) {
      throw new Error('A card with its own template is edited in YAML.');
    }
    if (config.bindings != null || config.actions != null) {
      throw new Error('A card with custom bindings or actions is edited in YAML.');
    }
  },
};

function isPlainObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

// Register a distinct tag: HA already owns <ha-card> for its card chrome.
export class RemoteComposeCard extends HaCardElement {
  static get observedAttributes() { return HaCardElement.observedAttributes; }
  setConfig(config) {
    const watchFace = config.card?.type === WATCH_FACE_CARD_TYPE;
    if (!config.card || !(watchFace || ['tile', 'button', 'entity', 'gauge', MESSAGE_CARD_TYPE].includes(config.card.type))) {
      throw new Error('Remote Compose requires a tile, button, entity, gauge or watchface card.');
    }
    if (watchFace) {
      // A published Wear OS face (design server WatchFaces): it ticks on the player's clock and
      // carries its own defaults for what a watch would push, so no host bindings are needed.
      if (typeof config.card.face !== 'string' || !config.card.face.trim()) {
        throw new Error('A watchface card needs `face`: a design id published on ui.coo.ee.');
      }
      if (config.card.size != null && !(Number.isInteger(config.card.size) && config.card.size >= 64 && config.card.size <= 1024)) {
        throw new Error('A watchface card\'s `size` is a whole number of pixels, 64 to 1024.');
      }
    }
    if (config.design_id != null) {
      if (typeof config.design_id !== 'string' || !DESIGN_ID.test(config.design_id) || !config.design_id.trim()) {
        throw new Error('design_id must be a design id such as library:tile.');
      }
      if (config.design != null || config.template_url != null) {
        throw new Error('Use design_id, design or template_url, not more than one.');
      }
    }
    if (config.template_url != null) {
      if (typeof config.template_url !== 'string' || !config.template_url.trim()) {
        throw new Error('template_url must be a non-empty URL or path.');
      }
      const url = new URL(config.template_url, document.baseURI);
      if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password) {
        throw new Error('template_url must be HTTP(S) without credentials.');
      }
      if (config.design != null) throw new Error('Use template_url or design, not both.');
    }
    if (config.device_id != null) {
      if (typeof config.device_id !== 'string' || !config.device_id.trim()) {
        throw new Error('device_id must be a non-empty string.');
      }
    }
    if (config.bindings != null) {
      if (!isPlainObject(config.bindings)) {
        throw new Error('bindings must be an object mapping variable names to entities.');
      }
      for (const [key, value] of Object.entries(config.bindings)) {
        if (!key.trim() || !(typeof value === 'string' ? value.trim() : isPlainObject(value))) {
          throw new Error(`bindings.${key} must be an entity id or binding object.`);
        }
      }
    }
    if (config.actions != null) {
      if (!isPlainObject(config.actions)) {
        throw new Error('actions must be an object mapping action names to actions.');
      }
      for (const [key, value] of Object.entries(config.actions)) {
        if (!key.trim() || !(typeof value === 'string' ? value.trim() : isPlainObject(value))) {
          throw new Error(`actions.${key} must be an entity id or action object.`);
        }
      }
    }
    const previous = JSON.stringify(this.config?.card);
    if (previous !== JSON.stringify(config.card)) this.inputDrafts?.clear();
    this.config = structuredClone(config);
    if (watchFace) this.removeAttribute('require-host-bindings');
    else this.setAttribute('require-host-bindings', '');
    // Set local mode before attributes can trigger an asynchronous start/OAuth.
    this.hostValues = hostValuesFromConfig(this._hass?.config);
    this.entityRegistry = isPlainObject(this._hass?.entities) ? this._hass.entities : null;
    this.setStates(this._hass?.states ?? {});
    const attrs = {
      card: JSON.stringify(config.card),
      library: config.library ?? DEFAULT_LIBRARY,
      design: config.design,
      'design-id': config.design_id,
      'template-url': config.template_url,
      'card-style': config.card_style ?? 'classic',
      'device-id': config.device_id,
      bindings: config.bindings != null ? JSON.stringify(config.bindings) : undefined,
      actions: config.actions != null ? JSON.stringify(config.actions) : undefined,
      // A face is square: by default as tall as the document is wide.
      height: config.height ?? (watchFace ? (config.card.size ?? WATCH_FACE_DEFAULT_SIZE) : undefined),
      theme: this._hass?.themes?.darkMode ? 'dark' : 'light',
    };
    for (const [name, value] of Object.entries(attrs)) {
      if (value == null) this.removeAttribute(name);
      else this.setAttribute(name, String(value));
    }
  }
  set hass(hass) {
    const wasAuthenticated = !!this._hass;
    const accountChanged = this._hass?.user?.id != null && this._hass.user.id !== hass?.user?.id;
    if (accountChanged) { this.inputDrafts.clear(); this.stop(); }
    this._hass = hass;
    // Home Assistant's own unit system, for a design that shows its unit (a °F thermostat).
    this.hostValues = hostValuesFromConfig(hass.config);
    this.entityRegistry = isPlainObject(hass.entities) ? hass.entities : null;
    this.setStates(hass.states);
    if (!wasAuthenticated && this.player) this.configureInputPlayer(this.player, this.config.card);
    this.setAttribute('theme', hass.themes?.darkMode ? 'dark' : 'light');
    if (accountChanged) this.start();
  }
  configureInputPlayer(player, card) {
    if (card.type !== MESSAGE_CARD_TYPE) return super.configureInputPlayer(player, card);
    if (!player.configureInputs) throw new Error('Update the player to use message inputs');
    const editable = !!this._hass && !this.hasAttribute('read-only');
    player.configureInputs({ editable, drafts: this.inputDrafts,
      onSubmit: (action) => {
        if (action !== MESSAGE_SUBMIT || !this._hass || this.hasAttribute('read-only')) return;
        try { this.tap(messageAction(this.config.card, this._hass.states, this.inputDrafts)); }
        catch (error) { this.setStatus('error', error.message); }
      },
    });
  }
  /** The card the picker previews and a new card starts as (HA adds `type`). */
  static getStubConfig(hass, entities, entitiesFallback) {
    loadSuggestionIndex();
    return { card: { type: 'tile', entity: stubEntity(hass, entities, entitiesFallback) } };
  }
  static getConfigForm() {
    loadSuggestionIndex();
    return CONFIG_FORM;
  }
  getCardSize() { return Math.max(1, Math.ceil((this.offsetHeight || 120) / 50)); }
  getGridOptions() { return { columns: 12, rows: 'auto' }; }
  async tap(action) {
    if (this.hasAttribute('read-only') || !this._hass || !action || action.kind === 'None') return;
    const confirmation = this.config.card.tap_action?.confirmation;
    if (confirmation && !confirmation.exemptions?.some((e) => e.user === this._hass.user.id)) {
      if (!window.confirm(confirmation.text || 'Run this action?')) return;
    }
    try {
      if (action.kind === 'MoreInfo') {
        this.dispatchEvent(new CustomEvent('hass-more-info', {
          detail: { entityId: action.entityId }, bubbles: true, composed: true,
        }));
      } else if (action.kind === 'Navigate') {
        const url = new URL(action.path, location.href);
        if (url.origin !== location.origin) throw new Error('Navigation must stay in Home Assistant');
        history.pushState(null, '', url);
        window.dispatchEvent(new CustomEvent('location-changed', { detail: { replace: false } }));
      } else if (action.kind === 'Url') {
        if (/^https?:/i.test(action.url)) window.open(action.url, '_blank', 'noopener');
      } else {
        const call = serviceCallFor(action);
        if (call) {
          const configured = this.config.card.tap_action;
          // Keep HA's full target (entity arrays, area/device/floor IDs) and service data. An
          // entity action (a garage door's Close) runs on the card's own entity, never the tap's.
          const target = action.entityAction ? call.target : configured?.target ?? call.target;
          await this._hass.callService(call.domain, call.service, call.service_data, target);
        }
      }
    } catch (error) {
      this.setStatus('error', `Action failed: ${error.message}`);
    }
  }
}
// Terrazzo adds this module as a Lovelace resource; a resource the user added with another URL
// (a cache-busting query, an absolute URL) loads a second copy that must not redefine it.
if (!customElements.get('remote-compose-card')) {
  customElements.define('remote-compose-card', RemoteComposeCard);
  // HA's frontend sets `hass` on <home-assistant> after this resource loads; look once it's idle.
  const idle = globalThis.requestIdleCallback ?? ((run) => globalThis.setTimeout?.(run, 2000));
  idle?.(() => preloadForEditors());
  window.customCards = window.customCards || [];
  window.customCards.push({ type: 'remote-compose-card', name: 'Remote Compose',
    description: 'A Home Assistant card drawn by Remote Compose, in a card style or a design made for your device',
    preview: true, documentationURL: DOCUMENTATION_URL, getEntitySuggestion });
}
