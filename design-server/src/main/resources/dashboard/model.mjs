/** Retain unknown integers outside JavaScript's safe range instead of rounding dashboard config. */
export function parseJson(text) {
  return JSON.parse(text, (key, value, context) => {
    if (typeof value !== 'number' || Number.isFinite(value) && (!Number.isInteger(value) || Number.isSafeInteger(value))) return value;
    if (!context?.source || !JSON.rawJSON) throw new Error('This browser cannot preserve large JSON numbers. Use a browser with JSON.rawJSON support.');
    return JSON.rawJSON(context.source);
  });
}

/** Structural JSON equality, independent of object key insertion order. */
export function canonical(value) {
  if (JSON.isRawJSON?.(value)) return value.rawJSON;
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
  if (value && typeof value === 'object') return `{${Object.keys(value).sort().map(k => `${JSON.stringify(k)}:${canonical(value[k])}`).join(',')}}`;
  return JSON.stringify(value);
}

export const copy = value => parseJson(JSON.stringify(value));

/** The browser host owns HA writes. This is an observed-conflict check, not an atomic HA CAS. */
export class DashboardTransaction {
  constructor(connection, path, baseline, readOnlyReason = null) {
    this.connection = connection;
    this.path = path;
    this.baseline = copy(baseline);
    this.previous = null;
    this.readOnlyReason = readOnlyReason;
    this.busy = false;
  }
  async replace(expected, replacement) {
    if (this.readOnlyReason) throw new Error(this.readOnlyReason);
    const path = this.path == null ? {} : { url_path: this.path };
    const current = await this.connection.send({ type: 'lovelace/config', ...path });
    if (canonical(current) !== canonical(expected)) throw new Error('Dashboard conflict: Home Assistant changed. Export your draft, reload, and reapply your changes.');
    await this.connection.send({ type: 'lovelace/config/save', config: copy(replacement), ...path });
  }
  async save(config) {
    if (this.busy) throw new Error('A dashboard write is already in progress.');
    if (this.readOnlyReason) throw new Error(this.readOnlyReason);
    const candidate = copy(config);
    if (canonical(candidate) === canonical(this.baseline)) return false;
    this.busy = true;
    try {
      await this.replace(this.baseline, candidate);
      this.previous = this.baseline;
      this.baseline = candidate;
      return true;
    } finally { this.busy = false; }
  }
  async undo() {
    if (!this.previous) throw new Error('There is no saved change to undo.');
    if (this.busy) throw new Error('A dashboard write is already in progress.');
    this.busy = true;
    try {
      await this.replace(this.baseline, this.previous);
      this.baseline = this.previous;
      this.previous = null;
    } finally { this.busy = false; }
  }
}

/** Explicit entity references only; strings in labels, Markdown and unknown fields stay literal. */
export function bindTemplate(template, bindings, states) {
  const slots = new Map(template.slots.map(slot => [slot.id, slot]));
  if (slots.size !== template.slots.length || canonical(Object.keys(bindings).sort()) !== canonical([...slots.keys()].sort())) throw new Error('Bind each entity slot exactly once.');
  for (const [id, entityId] of Object.entries(bindings)) {
    const slot = slots.get(id), state = states[entityId];
    if (!state || entityId.split('.')[0] !== slot.domain || (slot.deviceClass && state.attributes?.device_class !== slot.deviceClass)) throw new Error(`Entity does not match ${id}.`);
  }
  const used = new Set();
  function visit(value, entity = false) {
    if (JSON.isRawJSON?.(value)) return value;
    if (Array.isArray(value)) return value.map(v => visit(v, entity));
    if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([key, v]) => [key, visit(v, ['entity', 'entity_id', 'entities'].includes(key))]));
    if (entity && typeof value === 'string' && slots.has(value)) { used.add(value); return bindings[value]; }
    return value;
  }
  const result = visit(template.config);
  if (used.size !== slots.size) throw new Error('Template declares an unused entity slot.');
  return result;
}

export function entityIds(config) {
  const ids = new Set();
  function visit(value, entity = false) {
    if (JSON.isRawJSON?.(value)) return value;
    if (Array.isArray(value)) value.forEach(v => visit(v, entity));
    else if (value && typeof value === 'object') Object.entries(value).forEach(([key, v]) => visit(v, ['entity', 'entity_id', 'entities'].includes(key)));
    else if (entity && typeof value === 'string' && /^[a-z_]+\.[a-z0-9_]+$/.test(value)) ids.add(value);
  }
  visit(config);
  return [...ids];
}

/** States of the selected card, with credential-bearing image attributes left in the host. */
export function previewSnapshot(card, states) {
  return { states: Object.fromEntries(entityIds(card).filter(id => states[id]).map(id => [id, {
    entityId: id, state: states[id].state,
    attributes: Object.fromEntries(Object.entries(states[id].attributes ?? {}).filter(([key]) => !['access_token', 'entity_picture'].includes(key))),
  }])) };
}

/** Capture a reusable section/view, replacing only explicit references with domain slots. */
export function createTemplate(kind, config, states) {
  if (!['Section', 'View'].includes(kind) || !config || typeof config !== 'object' || Array.isArray(config)) throw new Error('Choose a section or a sections view.');
  if (kind === 'View' && config.type !== 'sections') throw new Error('Only sections views can become editable templates.');
  const counters = Object.create(null), replacements = Object.create(null), slots = [];
  for (const id of entityIds(config)) {
    const state = states[id];
    if (!state) throw new Error(`Load the state of ${id} before capturing this template.`);
    const domain = id.split('.')[0], index = counters[domain] ?? 0;
    counters[domain] = index + 1;
    const slot = `${domain}.s${index}`; replacements[id] = slot;
    slots.push({id:slot,domain,...(state.attributes?.device_class ? {deviceClass:state.attributes.device_class} : {})});
  }
  function visit(value, entity = false) {
    if (JSON.isRawJSON?.(value)) return value;
    if (Array.isArray(value)) return value.map(v => visit(v,entity));
    if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([key,v]) => [key,visit(v,['entity','entity_id','entities'].includes(key))]));
    return entity && typeof value === 'string' && replacements[value] ? replacements[value] : value;
  }
  return {schema:'harc-lovelace-template/v1',id:`local-${kind.toLowerCase()}`,title:config.title || `${kind} template`,kind,config:visit(config),slots};
}
