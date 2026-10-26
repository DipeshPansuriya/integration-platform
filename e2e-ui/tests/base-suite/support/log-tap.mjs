// T131 (row 323, F21 BS-C, BS-09): the app-log tap. The harness keeps the app's stdout/stderr in the
// RUNNER's memory (scripts/boot-backend.mjs:535-537, logDump() :692-694); Playwright workers are other
// processes. global-setup serves that buffer on the loopback only - read-only, in memory, for the run's
// lifetime, never written to a file (a log line may carry what R07 keeps out of files).
//   GET /size        -> { size }   the buffer's length in characters now
//   GET /log?from=n  -> text       the buffer from character n (BS-09 reads only its own POST's lines)

import http from 'node:http';

/** Serves `dump()` (the boot's logDump) on 127.0.0.1 at a free port: { url, close() }. */
export async function startLogTap(dump) {
  const server = http.createServer((request, response) => {
    const url = new URL(request.url, 'http://127.0.0.1');
    if (request.method !== 'GET') {
      response.writeHead(405).end();
      return;
    }
    if (url.pathname === '/size') {
      response.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify({ size: String(dump()).length }));
      return;
    }
    if (url.pathname === '/log') {
      const from = url.searchParams.get('from') ?? '0';
      if (!/^\d+$/.test(from)) {
        response.writeHead(400).end();
        return;
      }
      response.writeHead(200, { 'content-type': 'text/plain; charset=utf-8' }).end(String(dump()).slice(Number(from)));
      return;
    }
    response.writeHead(404).end();
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  return {
    url: `http://127.0.0.1:${server.address().port}`,
    close: () => new Promise((resolve) => {
      server.closeAllConnections?.();
      server.close(() => resolve());
    })
  };
}
