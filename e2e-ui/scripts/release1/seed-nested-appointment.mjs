// T214: the showcase flow NESTED-APPOINTMENT through the REAL admin API only (same pattern as seed-ref-order.mjs):
// one source (an appointment with two array levels, VehicleList[] -> ContainerList[]) and one API target that receives a JSON
// ARRAY with ONE flat record per container (the appointment and vehicle fields repeated on each). The mapping is the raw JSONata
// expression in nested-appointment/expression.jsonata (the drawn-links emitter cannot repeat a parent field per child, the raw
// editor and the engine can); the sample in nested-appointment/sample.json is both the sample run and the documented source.
// Idempotent and resumable: a flow with this code that is PUBLISHED means nothing is written; a draft left by a run that died is resumed
// (existing systems and contract versions are reused and only advanced: submit if draft, approve if pending) and published.
// Fictional test data only. Never run against the hosted app from here - the hosted seed run is a separate go.
import { readFileSync } from 'node:fs';

const SCHEMA = 'https://json-schema.org/draft/2020-12/schema';
const file = (name) => readFileSync(new URL(`./nested-appointment/${name}`, import.meta.url), 'utf8').trim();

const text = { type: 'string' };
const num = { type: 'number' };
const dateTime = { type: 'string', format: 'date-time' };
// `required` lists only the keys every valid document has; the rest may be absent (the expression then omits them from the record)
const objectSchema = (properties, required = []) => ({ type: 'object', ...(required.length ? { required } : {}), properties });

/** The source contract: the payload exactly as received (numbers as numbers, ids as strings, ISO-8601 date-times). */
const sourceDocument = {
  $schema: SCHEMA,
  ...objectSchema({
    TerminalOpsId: num, TerminalId: num, AppointmentId: num, AppointmentDate: dateTime, AppointmentStatus: text, NoofVehicle: num, BerthNo: text,
    VehicleList: {
      type: 'array',
      items: objectSchema({
        SlotDate: dateTime, SlotStartTime: dateTime, SlotEndTime: dateTime, DriverPortId: text,
        ContainerList: {
          type: 'array',
          items: objectSchema({ BookingType: text, TransactionID: num, ContainerNo: text, Size: text, Type: text, ISOCode: text, NoOfPackages: num, GrossWeight: num, ActualWeight: num }, ['ContainerNo'])
        }
      })
    }
  }, ['AppointmentId', 'VehicleList'])
};

/** The target contract describes ONE record; the body sent is an array of them (one per container). */
const targetDocument = {
  $schema: SCHEMA,
  ...objectSchema({
    terminalOpsId: num, terminalId: num, appointmentId: num, appointmentDate: dateTime, appointmentStatus: text, noOfVehicles: num, berthNo: text,
    slotDate: dateTime, slotStartTime: dateTime, slotEndTime: dateTime, driverPortId: text,
    bookingType: text, transactionId: num, containerNo: text, size: text, type: text, isoCode: text, noOfPackages: num, grossWeight: num, actualWeight: num
  }, ['appointmentId', 'containerNo'])
};

/** Informational links for the designer (source path -> target field); the compiled expression is what runs. */
const LINKS = Object.freeze([
  ['TerminalOpsId', 'terminalOpsId'], ['TerminalId', 'terminalId'], ['AppointmentId', 'appointmentId'], ['AppointmentDate', 'appointmentDate'],
  ['AppointmentStatus', 'appointmentStatus'], ['NoofVehicle', 'noOfVehicles'], ['BerthNo', 'berthNo'],
  ['VehicleList.SlotDate', 'slotDate'], ['VehicleList.SlotStartTime', 'slotStartTime'], ['VehicleList.SlotEndTime', 'slotEndTime'], ['VehicleList.DriverPortId', 'driverPortId'],
  ['VehicleList.ContainerList.BookingType', 'bookingType'], ['VehicleList.ContainerList.TransactionID', 'transactionId'], ['VehicleList.ContainerList.ContainerNo', 'containerNo'],
  ['VehicleList.ContainerList.Size', 'size'], ['VehicleList.ContainerList.Type', 'type'], ['VehicleList.ContainerList.ISOCode', 'isoCode'],
  ['VehicleList.ContainerList.NoOfPackages', 'noOfPackages'], ['VehicleList.ContainerList.GrossWeight', 'grossWeight'], ['VehicleList.ContainerList.ActualWeight', 'actualWeight']
]);

export const NESTED = Object.freeze({
  flowCode: 'NESTED-APPOINTMENT',
  source: Object.freeze({ system: 'nested-source', name: 'Nested appointment source', versionLabel: '1.0', document: sourceDocument }),
  target: Object.freeze({ system: 'nested-target', name: 'Nested appointment target (API, one record per container)', versionLabel: '1.0', document: targetDocument, expression: file('expression.jsonata'), links: LINKS })
});

/** The sample record the mapping's mandatory sample run posts: the documented payload, as given. */
export const SAMPLE = JSON.parse(file('sample.json'));

/** The value with every object's keys sorted: JSON content compared regardless of key order. */
const canonical = (value) => (Array.isArray(value) ? value.map(canonical) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map((key) => [key, canonical(value[key])])) : value);

function must(answer, what) {
  if (answer.status !== 200 && answer.status !== 201) throw new Error(`${what}: HTTP ${answer.status} ${JSON.stringify(answer.data)}`);
  return answer.data;
}

/**
 * Registers NESTED-APPOINTMENT. `call(login, method, path, body)` is the boot's console-session call; `author` / `approver` are its login
 * names. Returns { skipped, resumed, flowId, ... }; `skipped: true` (nothing written, no credential - a secret is shown once) when the flow is already published.
 */
export async function seedNestedAppointment({ call, author = 'default', approver = 'approver', customerId, apiBase, sourceBase }) {
  const as = async (login, method, path, body, what) => must(await call(login, method, path, body), `NESTED-APPOINTMENT ${what}`);

  const flows = await as(author, 'GET', '/api/v1/admin/flows', undefined, 'list flows');
  const existing = flows.find((flow) => flow.code === NESTED.flowCode || flow.name === NESTED.flowCode);
  if (existing?.status === 'PUBLISHED') return { skipped: true, resumed: false, flowId: existing.id, interfaceCode: existing.code, credential: null };

  const systems = await as(author, 'GET', '/api/v1/admin/systems', undefined, 'list systems');
  const ensureSystem = async (def, direction, baseUrl) =>
    systems.find((system) => system.code === def.system)
    ?? await as(author, 'POST', '/api/v1/admin/systems', { code: def.system, name: def.name, direction, type: 'API', settings: { baseUrl } }, `${direction.toLowerCase()} system`);
  const source = await ensureSystem(NESTED.source, 'INBOUND', sourceBase);
  await ensureSystem(NESTED.target, 'OUTBOUND', apiBase);

  // the contract versions that already exist (a run that died leaves any state)
  const contracts = (await as(author, 'GET', '/api/v1/admin/contracts', undefined, 'list contracts')).contracts ?? [];
  // an existing contract version is reused only when its content is ours; checked for both BEFORE any write, so a clash stops the run clean
  const existingVersion = (contract, role) =>
    contracts.filter((c) => c.system === contract.system && c.role === role).flatMap((c) => c.versions ?? []).find((v) => v.versionLabel === contract.versionLabel);
  const pairs = [['source', NESTED.source, 'SOURCE_REQUEST'], ['target', NESTED.target, 'TARGET_REQUEST']];
  for (const [, contract, role] of pairs) {
    const version = existingVersion(contract, role);
    if (!version) continue;
    const stored = await as(author, 'GET', `/api/v1/admin/contracts/versions/${version.id}`, undefined, `read ${contract.name}`);
    if (JSON.stringify(canonical(stored.jsonSchema)) !== JSON.stringify(canonical(contract.document))) {
      throw new Error(`NESTED-APPOINTMENT: contract ${contract.system} ${contract.versionLabel} already exists with different content - retire it or use another version label, then run again`);
    }
  }

  // each contract version: imported if absent, submitted if still a draft, approved if not yet (a run that died leaves any of these)
  const versionIds = {};
  for (const [key, contract, role] of pairs) {
    let version = existingVersion(contract, role);
    if (!version) {
      const imported = await as(author, 'POST', '/api/v1/admin/contracts/versions/import', { system: contract.system, role, versionLabel: contract.versionLabel, format: 'JSON_SCHEMA', document: contract.document }, `import ${contract.name}`);
      version = { id: imported.id, status: 'DRAFT' };
    }
    if (version.status === 'DRAFT') {
      await as(author, 'POST', `/api/v1/admin/contracts/versions/${version.id}/submit`, { changeNote: `NESTED-APPOINTMENT ${contract.name} v${contract.versionLabel}` }, `submit ${contract.name}`);
      version.status = 'PENDING_APPROVAL';
    }
    if (version.status === 'PENDING_APPROVAL') {
      await as(approver, 'POST', `/api/v1/admin/contracts/versions/${version.id}/approve`, undefined, `approve ${contract.name}`);
    }
    versionIds[key] = version.id;
  }

  const flowBody = { code: NESTED.flowCode, name: NESTED.flowCode, sourceSystem: NESTED.source.system, destinations: [NESTED.target.system], businessKeyRule: 'AppointmentId' };
  const flow = existing ?? await as(author, 'POST', '/api/v1/admin/flows', flowBody, 'flow');
  const routes = (await as(author, 'GET', `/api/v1/admin/flows/${flow.id}/routes`, undefined, 'routes')).routes ?? [];
  if (routes.length !== 1) throw new Error(`NESTED-APPOINTMENT routes: expected 1, got ${routes.length}`);
  const route = routes[0];
  await as(author, 'PUT', `/api/v1/admin/routes/${route.id}/mapping`, {
    compiledExpression: NESTED.target.expression,
    sourceContractVersionId: versionIds.source,
    targetContractVersionId: versionIds.target,
    fieldMappings: NESTED.target.links.map(([from, to]) => ({ source: from, target: to }))
  }, 'mapping');
  // publish needs a passing sample run of the mapping's pinned content (T-037)
  const mapped = (await as(author, 'GET', `/api/v1/admin/flows/${flow.id}/routes`, undefined, 'routes after mapping')).routes ?? [];
  const mappingId = mapped.find((r) => r.id === route.id)?.mappingId;
  if (!mappingId) throw new Error(`NESTED-APPOINTMENT: route ${route.id} has no mapping id after the save`);
  const run = await as(author, 'POST', `/api/v1/admin/mappings/${mappingId}/test`, { sample: SAMPLE }, 'sample run');
  if (run.status !== 'OK') throw new Error(`NESTED-APPOINTMENT sample run: status ${run.status}`);
  await as(author, 'PUT', `/api/v1/admin/flows/${flow.id}`, flowBody, 'draft save');
  await as(author, 'POST', `/api/v1/admin/flows/${flow.id}/submit`, { changeNote: 'NESTED-APPOINTMENT one record per container' }, 'submit');
  await as(approver, 'POST', `/api/v1/admin/flows/${flow.id}/approve`, undefined, 'approve');
  await as(author, 'POST', `/api/v1/admin/flows/${flow.id}/publish`, undefined, 'publish');
  const published = await as(author, 'GET', `/api/v1/admin/flows/${flow.id}`, undefined, 'read published');
  const credential = await as(author, 'POST', `/api/v1/admin/systems/${source.id}/source-credentials`, { customerId }, 'source credential');
  return { skipped: false, resumed: Boolean(existing), flowId: flow.id, interfaceCode: published.code, routeId: route.id, versionIds, credential: { clientId: credential.clientId, secret: credential.secret } };
}
