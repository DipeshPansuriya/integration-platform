// T131 (row 323, F21 BS-C, BS-09): one topic's records with their headers, read from the broker the
// pipeline uses - bounded (30 s), read-only (a console consumer never writes a record).
//
// HARNESS GAP (T106): the base-suite boot starts NO Kafka today (servicePlan knows only wiremock,
// unit/boot-services.test.mjs:42). Which container holds the broker and its console-consumer argv are
// TBD(T106) in pending-names.mjs: this FAILS, naming T106, until those two lines are swapped.

import { execFileSync } from 'node:child_process';
import { nameOf } from '../lib/pending-names.mjs';
import { parseConsumerOutput } from '../lib/trace.mjs';

const TOPIC = /^platform\.[a-z0-9.-]+$/;

/** Every record on `topic` from the beginning: [{ headers, key, value }]. */
export function topicRecords(topic) {
  if (!TOPIC.test(topic)) throw new Error(`kafka: ${topic} is not a platform topic`);
  const envName = nameOf('kafkaContainerEnv');
  const container = process.env[envName];
  if (typeof container !== 'string' || container === '') throw new Error(`kafka: ${envName} is not set - the boot named no broker`);
  const consumer = nameOf('kafkaConsumer').split(' ').filter(Boolean);
  const output = execFileSync('docker', ['exec', container, ...consumer, '--topic', topic, '--from-beginning', '--timeout-ms', '10000',
    '--property', 'print.headers=true', '--property', 'print.key=true'], { encoding: 'utf8', timeout: 30000, stdio: ['ignore', 'pipe', 'pipe'] });
  return parseConsumerOutput(output);
}
