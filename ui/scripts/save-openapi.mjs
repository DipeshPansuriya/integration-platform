// npm run generate:client (T-158, E3 lane D): fetch the backend's OpenAPI document - by default
// from a running platform-app at $API_OPENAPI_URL - and (re)generate the typed client.
// The committed artifacts ui/openapi.json + ui/src/api/openapi.d.ts are regenerated output only;
// the drift test keeps them equal to the live document. R07: environment variable NAMES only.

import { API_URL_VARIABLE, OPENAPI_URL_DEFAULT, OPENAPI_URL_VARIABLE, fetchDocumentFrom, persistGeneratedClient } from './typescribe.mjs';

const apiUrl = process.env[OPENAPI_URL_VARIABLE] ?? OPENAPI_URL_DEFAULT;
console.log(`generate:client: reading ${OPENAPI_URL_VARIABLE}=${apiUrl}`);

const doc = await fetchDocumentFrom(apiUrl);
const written = await persistGeneratedClient(doc);
console.log(`generate:client: wrote ${written.doc} and ${written.types} (paths: ${Object.keys(doc.paths ?? {}).length})`);
console.log(`console runtime reads its base URL from ${API_URL_VARIABLE} (a name only, never a value - R07)`);