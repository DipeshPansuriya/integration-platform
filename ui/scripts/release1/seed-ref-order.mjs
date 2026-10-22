// T174b: the Release 1 reference example (REF-ORDER) through the REAL admin API only: one source, two targets -
// target A an API system (contract v1.0) on the compose WireMock, target B an MQ system (contract v2.0) on the compose
// Artemis - every contract and the flow approved by a second user (four-eyes: the author never approves).
//
// PRODUCT GAP (measured in code, main ecef544b): the MQ-CSV contract (columns, delimiter, ...) and the connect user
// live in the MQ system's registry JSON (JdbcMqCsvTargets.java:25-27 "shortcut"), but SystemSettings validates an MQ
// system to host, port, queue only (SystemSettings.java:136-137) and refuses any other key, so the admin API cannot
// configure an MQ-CSV target. `mqContractSql` is the one owner-SQL step, the same one the E18 journey does
// (E18Seed.java); it writes no pipeline row.

const SCHEMA = 'https://json-schema.org/draft/2020-12/schema';
const objectOf = (fields) => ({
  $schema: SCHEMA,
  type: 'object',
  required: Object.keys(fields),
  properties: Object.fromEntries(Object.entries(fields).map(([name, type]) => [name, { type }]))
});

export const REF = Object.freeze({
  flowCode: 'REF-ORDER',
  source: Object.freeze({ system: 'ref-source', name: 'Reference source', versionLabel: '1.0', document: objectOf({ orderNo: 'string', customer: 'string', qty: 'number' }) }),
  targetA: Object.freeze({ system: 'ref-target-a', name: 'Reference target A (API)', versionLabel: '1.0', document: objectOf({ orderNo: 'string', customer: 'string', qty: 'number' }),
    expression: "{'orderNo': orderNo, 'customer': customer, 'qty': qty}",
    links: Object.freeze([['orderNo', 'orderNo'], ['customer', 'customer'], ['qty', 'qty']]) }),
  targetB: Object.freeze({ system: 'ref-target-b', name: 'Reference target B (MQ-CSV)', versionLabel: '2.0', document: objectOf({ order_no: 'string', customer: 'string', total: 'number' }),
    expression: "{'order_no': orderNo, 'customer': customer, 'total': qty}",
    links: Object.freeze([['orderNo', 'order_no'], ['customer', 'customer'], ['qty', 'total']]) })
});

/** The CSV contract the MQ target renders (same shape as the E18 journey: header row, CRLF, comma). */
export function mqCsvContract() {
  return {
    columns: [{ name: 'order_no', type: 'text' }, { name: 'customer', type: 'text' }, { name: 'total', type: 'number' }],
    delimiter: ',', quote: '"', quoteAll: false, encoding: 'UTF-8', lineEnding: 'CRLF', header: true
  };
}

/** The owner-SQL step: adds the CSV contract to the MQ system's settings. The system code is a plain slug. */
export function mqContractSql(systemCode) {
  if (!/^[a-z0-9-]{1,64}$/.test(systemCode)) throw new Error(`mqContractSql: ${systemCode} is not a plain system code`);
  const json = JSON.stringify({ contract: mqCsvContract() });
  return `UPDATE public.system SET settings = (settings::jsonb || '${json}'::jsonb) WHERE code='${systemCode}' AND type='MQ'`;
}

function must(answer, what) {
  if (answer.status !== 200 && answer.status !== 201) throw new Error(`${what}: HTTP ${answer.status} ${JSON.stringify(answer.data)}`);
  return answer.data;
}

/** The route (of the flow's routes answer) that belongs to `system`: by its code or id in the route, else by position. */
export function routeFor(routes, system, index, systemId) {
  const hit = routes.find((route) => {
    const text = JSON.stringify(route);
    return text.includes(`"${system}"`) || (systemId && text.includes(systemId));
  });
  return hit ?? routes[index];
}

/**
 * Seeds REF-ORDER. `call(login, method, path, body)` is the boot's console-session call; `author` / `approver` are its
 * login names. Returns ids and the generated interface code (no secret except the issued source credential, which the
 * caller turns into a token in memory).
 */
export async function seedRefOrder({ call, author = 'default', approver = 'approver', customerId, apiBase, sourceBase, mq }) {
  const as = async (login, method, path, body, what) => must(await call(login, method, path, body), `REF-ORDER ${what}`);
  const source = await as(author, 'POST', '/api/v1/admin/systems', { code: REF.source.system, name: REF.source.name, direction: 'INBOUND', type: 'API', settings: { baseUrl: sourceBase } }, 'source system');
  const targetA = await as(author, 'POST', '/api/v1/admin/systems', { code: REF.targetA.system, name: REF.targetA.name, direction: 'OUTBOUND', type: 'API', settings: { baseUrl: apiBase } }, 'target A system');
  const targetB = await as(author, 'POST', '/api/v1/admin/systems', { code: REF.targetB.system, name: REF.targetB.name, direction: 'OUTBOUND', type: 'MQ', settings: { host: mq.host, port: mq.port, queue: mq.queue } }, 'target B (MQ) system');

  const versionIds = {};
  for (const [key, contract, role] of [['source', REF.source, 'SOURCE_REQUEST'], ['targetA', REF.targetA, 'TARGET_REQUEST'], ['targetB', REF.targetB, 'TARGET_REQUEST']]) {
    const { id } = await as(author, 'POST', '/api/v1/admin/contracts/versions/import', { system: contract.system, role, versionLabel: contract.versionLabel, format: 'JSON_SCHEMA', document: contract.document }, `import ${contract.name}`);
    await as(author, 'POST', `/api/v1/admin/contracts/versions/${id}/submit`, { changeNote: `REF-ORDER ${contract.name} v${contract.versionLabel}` }, `submit ${contract.name}`);
    await as(approver, 'POST', `/api/v1/admin/contracts/versions/${id}/approve`, undefined, `approve ${contract.name}`);
    versionIds[key] = id;
  }

  const flowBody = { code: REF.flowCode, name: REF.flowCode, sourceSystem: REF.source.system, destinations: [REF.targetA.system, REF.targetB.system], businessKeyRule: 'orderNo' };
  const flow = await as(author, 'POST', '/api/v1/admin/flows', flowBody, 'flow');
  const routes = (await as(author, 'GET', `/api/v1/admin/flows/${flow.id}/routes`, undefined, 'routes')).routes ?? [];
  if (routes.length !== 2) throw new Error(`REF-ORDER routes: expected 2, got ${routes.length}`);
  const bound = [];
  for (const [index, [key, target, systemId]] of [['targetA', REF.targetA, targetA.id], ['targetB', REF.targetB, targetB.id]].entries()) {
    const route = routeFor(routes, target.system, index, systemId);
    await as(author, 'PUT', `/api/v1/admin/routes/${route.id}/mapping`, {
      compiledExpression: target.expression,
      sourceContractVersionId: versionIds.source,
      targetContractVersionId: versionIds[key],
      fieldMappings: target.links.map(([from, to]) => ({ source: from, target: to }))
    }, `mapping ${target.system}`);
    bound.push(route.id);
  }
  await as(author, 'PUT', `/api/v1/admin/flows/${flow.id}`, flowBody, 'draft save');
  await as(author, 'POST', `/api/v1/admin/flows/${flow.id}/submit`, { changeNote: 'REF-ORDER one call, two targets' }, 'submit');
  await as(approver, 'POST', `/api/v1/admin/flows/${flow.id}/approve`, undefined, 'approve');
  await as(author, 'POST', `/api/v1/admin/flows/${flow.id}/publish`, undefined, 'publish');
  const published = await as(author, 'GET', `/api/v1/admin/flows/${flow.id}`, undefined, 'read published');
  const credential = await as(author, 'POST', `/api/v1/admin/systems/${source.id}/source-credentials`, { customerId }, 'source credential');
  return {
    flowId: flow.id, interfaceCode: published.code, routeIds: bound, versionIds,
    sourceSystemId: source.id, targetASystemId: targetA.id, targetBSystemId: targetB.id,
    credential: { clientId: credential.clientId, secret: credential.secret }
  };
}
