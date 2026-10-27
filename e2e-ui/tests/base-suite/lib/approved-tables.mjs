// lane qa-f3-approved-tables (2026-10-22): the pure parts of the E16-F3 spec - request bodies and the oracles that
// read the API's real answers. Every code below is read from the product source on main (c95fa254), not invented:
//   ApprovedTableAdminController / ApprovedTableService (platform-config, system/adapter/in and system/application):
//     POST request 201 | 400 bad name or non-DB system | 404 unknown system | 409 duplicate
//     POST approve/revoke 200 | 403 {"refusal":"REQUESTER_IS_APPROVER"} | 404 other system/tenant row
//                         | 409 {"refusal":"NOT_PENDING_APPROVAL" | "NOT_APPROVED" | "TARGET_CHANGED"}
//   DbTablePublishListener: publish of an unapproved DB table -> 400 {"error": "the publish was refused: the DB table is
//     not approved for the target (<systemCode>.<table>); request and approve it first"}
//   DbTestConnection: an off-list port or a refused host -> {"result":"FAILED","reason":"DESTINATION_REFUSED"}

export const DB_TABLE = 'f3_orders';
export const DB_ACCOUNT = 'f3_etl';
export const DB_NAME = 'f3orders';
export const STATUS = Object.freeze({ pending: 'PENDING_APPROVAL', approved: 'APPROVED', revoked: 'REVOKED' });
export const REFUSAL = Object.freeze({
  notPending: 'NOT_PENDING_APPROVAL',
  notApproved: 'NOT_APPROVED'
});

/** The DB target contract: every column the drawn mapping sends (the publish-time column check reads these). */
export const DB_TARGET_COLUMNS = Object.freeze({ clientId: 'string', referenceNo: 'string', invoiceValue: 'number' });

export const approvedTablesPath = (systemId, rowId, action) =>
  `/api/v1/admin/systems/${systemId}/approved-tables${rowId ? `/${rowId}` : ''}${action ? `/${action}` : ''}`;

/** A DB system the tenant registers; `host`/`port` are the exception host (the only host the app allows). */
export function dbSystemBody(code, name, exception) {
  return {
    code, name, direction: 'OUTBOUND', type: 'DB',
    settings: { host: exception.host, port: exception.port, database: DB_NAME, user: DB_ACCOUNT, table: DB_TABLE }
  };
}

/** The named refusal code of a ConfigApiException answer ({"refusal": code}); null when the answer carries none. */
export const refusalOf = (answer) => (typeof answer?.data?.refusal === 'string' ? answer.data.refusal : null);

/** Problems with a publish answer that must be the F3 "table not approved" refusal naming `systemCode.table`. */
export function publishRefusalProblems(answer, systemCode, table = DB_TABLE) {
  const problems = [];
  if (answer?.status !== 400) problems.push(`status ${answer?.status}, expected 400`);
  const text = String(answer?.data?.error ?? '');
  if (!/not approved/i.test(text)) problems.push('error does not say the table is not approved');
  if (!text.includes(`${systemCode}.${table}`)) problems.push(`error does not name ${systemCode}.${table}`);
  return problems;
}

/** Problems with a freshly requested row (the 201 body of the request). */
export function requestedRowProblems(row, systemId, table = DB_TABLE) {
  const problems = [];
  if (row?.status !== STATUS.pending) problems.push(`status ${row?.status}, expected ${STATUS.pending}`);
  if (row?.systemId !== systemId) problems.push('systemId is not the requested system');
  if (row?.table !== table) problems.push(`table ${row?.table}, expected ${table}`);
  if (!row?.requestedBy) problems.push('requestedBy is empty');
  if (row?.decidedBy != null) problems.push('decidedBy must be empty before a decision');
  return problems;
}

/** Problems with a decided row: the status and a named decider (the requester may be the decider: single-user workflow). */
export function decidedRowProblems(row, status) {
  const problems = [];
  if (row?.status !== status) problems.push(`status ${row?.status}, expected ${status}`);
  if (!row?.decidedBy) problems.push('decidedBy is empty');
  return problems;
}

/** The foreign ids (system ids, row ids, codes) that appear in `list` - any hit is a cross-tenant leak. */
export function leaksOf(list, foreign) {
  const text = JSON.stringify(list ?? null);
  return foreign.filter((needle) => needle && text.includes(needle));
}
