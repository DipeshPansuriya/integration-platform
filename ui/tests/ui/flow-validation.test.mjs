// Row 310 B13 rework (amit-developer, lane A): the flow editor maps a server 400 to its FIELD and the
// failing field shows the refusal's own words. The judge (kavita 2026-10-15T15:18Z) failed two B13
// criteria: an 'unknown source system X' refusal (FlowAdminService.java:60,85 - a human sentence, not
// the camelCase attribute) landed on the generic 'request' bucket because api/flows.ts matched only
// 'sourceSystem', and the screens covered 2 of the 4 validatable fields.
// Pure mapping (validationFieldOf) is exercised directly; the page contracts the screens specs key off
// (per-field aria wiring, the visible error paragraph, the server-word text) are read from source -
// the same split as tests/ui/flows-versions.test.mjs.
// Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/flow-validation.test.mjs

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const src = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src');
const editor = readFileSync(join(src, 'pages', 'FlowDefinition.tsx'), 'utf8');

const { validationFieldOf } = await import('../../src/api/flows.ts');
const { fieldA11y, validationErrorId, validationText } = await import('../../src/pages/flow-lifecycle.ts');

// The four fields the editor can fail on, measured from FlowDefinition.tsx (the interface-name input
// and the ordering select are never refused by the flow-save API - ordering's sentence lands on the
// generic request alert instead).
const FIELDS = ['source-system', 'destinations', 'business-key-rule', 'source-schema'];

test('B13 rework: the unknown-source-system refusals land on the source-system field, not the request bucket', () => {
  // The flow create/draft-save refusal, verbatim from FlowAdminService.java:60,85.
  assert.equal(validationFieldOf('unknown source system ZZ'), 'source-system');
  // The same sentence from the draft-save's contract seam (ContractAdminService.java:238).
  assert.equal(validationFieldOf('unknown source system ORD-legacy'), 'source-system');
  // The source-schema seam names the system the same way for this screen (ContractAdminService.java:206 -
  // saveSourceSchema posts form.sourceSystem as body.system, so 'unknown system X' is a source-system refusal).
  assert.equal(validationFieldOf('unknown system ZZ'), 'source-system');
});

test('B13: every other designed 400 sentence still lands on its field', () => {
  assert.equal(validationFieldOf('sourceSystem is required'), 'source-system'); // SystemAdminService.java:154
  assert.equal(validationFieldOf('the mandatory business-key rule is missing: businessKeyRule'), 'business-key-rule'); // FlowAdminService.java:254
  assert.equal(validationFieldOf('destinations must name at least one registered system'), 'destinations'); // FlowAdminService.java:277
  assert.equal(validationFieldOf('sourceSchema must be a JSON Schema object'), 'source-schema'); // ContractAdminService.java:203
  assert.equal(validationFieldOf('sourceSchema is required: the draft-save stores it as the draft source contract version'), 'source-schema');
});

test('B13: what the mapping cannot name stays a generic request refusal; no error answers no error', () => {
  assert.equal(validationFieldOf('ordering must be one of [PER_KEY, NONE]'), 'request'); // FlowAdminService.java:264 - no field, generic alert
  assert.equal(validationFieldOf('role must be SOURCE_REQUEST in this cut'), 'request');
  assert.equal(validationFieldOf(undefined), null);
  assert.equal(validationFieldOf(''), null);
});

test('B13 rework: a field error shows the refusal sentence itself; the fixed text is only the no-server fallback', () => {
  // An 'unknown source system ZZ' refusal must not render the sentence 'sourceSystem is required' -
  // the editor's per-field paragraph passes the server text through when the refusal carried one.
  assert.match(editor, /validationServerText\s*\?\?\s*validationText\(/,
    'errorText() prefers validationServerText; validationText(field, serverText) is the fallback');
  // The client-side source-schema refusal (invalid JSON before any call) carries no server text: it must
  // still land on the field.
  assert.match(editor, /catch \{\s*\n?\s*setValidationField\('source-schema'\);/,
    'a JSON.parse failure on the schema sets the source-schema field');
});

test('B13: all four validatable fields are aria-wired and render their visible error paragraph', () => {
  for (const field of FIELDS) {
    assert.ok(editor.includes(`a11y('${field}')`), `the ${field} control takes aria-invalid/aria-describedby while failing`);
    assert.ok(editor.includes(`errorText('${field}')`), `the ${field} error paragraph renders`);
    assert.equal(fieldA11y(field, field)['aria-describedby'], validationErrorId(field),
      `the describedby id and the paragraph id are one string for ${field}`);
    assert.ok(fieldA11y(field, field)['aria-invalid'], `the failing ${field} is aria-invalid`);
    assert.ok(validationText(field, undefined).trim().length > 0, `${field} has visible text even without a server sentence`);
  }
  assert.ok(editor.includes('data-validation-error={field}'), 'the paragraph names its field for the screens specs');
});
