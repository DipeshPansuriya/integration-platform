import test from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const templatesDir = join(__dirname, '..', '..', 'src', 'templates');
const systems = await import('../../src/api/systems.ts');

const loadTemplates = () => {
  const templates = [];
  for (const file of readdirSync(templatesDir).filter((f) => f.endsWith('.json'))) {
    const parsed = JSON.parse(readFileSync(join(templatesDir, file), 'utf8'));
    if (Array.isArray(parsed)) templates.push(...parsed);
    else templates.push(parsed);
  }
  return templates;
};

test('templates: both named templates exist', () => {
  const names = loadTemplates().map((t) => t.name ?? t.title).filter(Boolean);
  assert.ok(names.includes('Blank API-to-API'), 'blank template');
  assert.ok(names.includes('Nested appointment showcase'), 'nested template');
});

test('templates: systems are valid and pass client settings validators', () => {
  for (const template of loadTemplates()) {
    const codes = template.systems.map((s) => s.code);
    assert.equal(new Set(codes).size, codes.length, `${template.name}: unique system codes`);
    for (const system of template.systems) {
      assert.ok(system.code, `${template.name}: system code`);
      assert.ok(system.name, `${template.name}: system name`);
      assert.ok(['INBOUND', 'OUTBOUND'].includes(system.direction), `${template.name}: direction`);
      assert.ok(systems.SYSTEM_TYPES.includes(system.type), `${template.name}: system type`);
      // Form values mirror what RegisterSystemForm sends through clientSettingsErrors.
      const values = system.values ?? {};
      assert.deepEqual(systems.clientSettingsErrors(system.type, values), [], `${template.name}: ${system.code} settings`);
    }
  }
});

test('templates: contract documents parse and template JSON carries validators', () => {
  for (const template of loadTemplates()) {
    assert.ok(Array.isArray(template.contracts) && template.contracts.length > 0, `${template.name}: contracts`);
    for (const contract of template.contracts) {
      assert.ok(contract.system, `${template.name}: contract system`);
      assert.ok(contract.role, `${template.name}: contract role`);
      assert.ok(contract.versionLabel, `${template.name}: version label`);
      assert.ok(contract.format, `${template.name}: format`);
      assert.doesNotThrow(() => JSON.parse(contract.document), `${template.name}: invalid contract document`);
    }
  }
});

test('templates: flows have names and mappings are strings', () => {
  for (const template of loadTemplates()) {
    assert.ok(template.flow?.name?.trim(), `${template.name}: flow name`);
    assert.ok(['Message Received', 'Scheduled (Cron)', 'Webhook', 'File Drop'].includes(template.flow.trigger), `${template.name}: trigger`);
    assert.equal(typeof template.mapping?.expression, 'string', `${template.name}: mapping expression`);
    assert.ok(template.mapping.expression.trim().length > 0, `${template.name}: mapping expression`);
  }
});

test('templates: no secrets or live tenants are embedded', () => {
  const json = loadTemplates().map((t) => JSON.stringify(t)).join('\n');
  assert.equal(/password|secret|token|credential/i.test(json), false, 'no secret-like words');
});
