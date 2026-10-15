// Row 278 T94 (reopened) - the harness finds a JDK on a PC where JAVA_HOME is unset and java is
// not on PATH, FAILING FIRST. No docker: pure functions of scripts/boot-backend.mjs, pointed at a
// temp home dir with fake ~/.jdks/<jdk>/bin/java(.exe) entries.
//
//   order    : JAVA_HOME's bin/java -> java on PATH -> the newest JDK 25 folder under <home>/.jdks
//   none     : the error names JAVA_HOME, PATH and the .jdks folder it looked in
//   child env: the resolved JDK's JAVA_HOME and its bin first on PATH, for the mvnw and java
//              spawns only - process.env and the OS environment are never changed

import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { jdkEnv, resolveJdk } from '../../scripts/boot-backend.mjs';

const JAVA = process.platform === 'win32' ? 'java.exe' : 'java';
const notOnPath = () => false;

function tempHome(t) {
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'jvm-lookup-'));
  t.after(() => fs.rmSync(home, { recursive: true, force: true }));
  return home;
}

/** A fake JDK folder: <home>/.jdks/<name>/bin/java(.exe), or an empty one when withJava is false. */
function fakeJdk(home, name, withJava = true) {
  const bin = path.join(home, '.jdks', name, 'bin');
  fs.mkdirSync(bin, { recursive: true });
  if (withJava) fs.writeFileSync(path.join(bin, JAVA), '', { mode: 0o755 });
  return path.join(home, '.jdks', name);
}

test('JAVA_HOME set and valid: its bin/java wins, even with a .jdks folder present', (t) => {
  const home = tempHome(t);
  const javaHome = fakeJdk(home, 'jdk-21.0.1');
  fakeJdk(home, 'jdk-25.0.4.1+1');
  const jdk = resolveJdk({ env: { JAVA_HOME: javaHome }, homeDir: home, javaOnPath: notOnPath });
  assert.equal(jdk.javaHome, javaHome);
  assert.equal(jdk.java, path.join(javaHome, 'bin', JAVA));
});

test('JAVA_HOME unset, java on PATH: the bare name, no JAVA_HOME forced', (t) => {
  const home = tempHome(t);
  fakeJdk(home, 'jdk-25.0.4.1+1');
  const jdk = resolveJdk({ env: {}, homeDir: home, javaOnPath: () => true });
  assert.deepEqual(jdk, { javaHome: null, java: 'java' });
});

test('JAVA_HOME unset, java not on PATH: the newest JDK 25 under <home>/.jdks', (t) => {
  const home = tempHome(t);
  fakeJdk(home, 'jdk-21.0.9');
  fakeJdk(home, 'jdk-25.0.4.1+1');
  const newest = fakeJdk(home, 'jdk-25.0.10');
  fakeJdk(home, 'jdk-25.0.99', false); // no bin/java - not a JDK
  const jdk = resolveJdk({ env: {}, homeDir: home, javaOnPath: notOnPath });
  assert.equal(jdk.javaHome, newest);
  assert.equal(jdk.java, path.join(newest, 'bin', JAVA));
});

test('JAVA_HOME pointing at a folder without bin/java: falls through to <home>/.jdks', (t) => {
  const home = tempHome(t);
  const only = fakeJdk(home, 'jdk-25.0.4.1+1');
  const jdk = resolveJdk({ env: { JAVA_HOME: path.join(home, 'nowhere') }, homeDir: home, javaOnPath: notOnPath });
  assert.equal(jdk.javaHome, only);
});

test('no JDK anywhere: the error names JAVA_HOME, PATH and the .jdks folder', (t) => {
  const home = tempHome(t);
  fakeJdk(home, 'jdk-21.0.9'); // a JDK, but not 25
  assert.throws(
    () => resolveJdk({ env: {}, homeDir: home, javaOnPath: notOnPath }),
    (error) => {
      assert.match(error.message, /no JVM found/);
      assert.match(error.message, /JAVA_HOME=<unset>/);
      assert.match(error.message, /PATH/);
      assert.ok(error.message.includes(path.join(home, '.jdks')), error.message);
      return true;
    }
  );
});

test('child env: JAVA_HOME and the JDK bin first on PATH, the existing PATH key kept, the input untouched', () => {
  const javaHome = path.join('C:', 'jdks', 'jdk-25.0.4.1+1');
  const base = { Path: ['a', 'b'].join(path.delimiter), OTHER: 'x' };
  const env = jdkEnv(base, { javaHome, java: path.join(javaHome, 'bin', JAVA) });
  assert.equal(env.JAVA_HOME, javaHome);
  assert.equal(env.Path, [path.join(javaHome, 'bin'), 'a', 'b'].join(path.delimiter));
  assert.equal(env.PATH, undefined); // one PATH key - never a second one beside Windows' "Path"
  assert.equal(env.OTHER, 'x');
  assert.deepEqual(base, { Path: ['a', 'b'].join(path.delimiter), OTHER: 'x' });
});

test('child env: java from PATH leaves the env as it was', () => {
  const base = { PATH: 'a', OTHER: 'x' };
  assert.deepEqual(jdkEnv(base, { javaHome: null, java: 'java' }), base);
});
