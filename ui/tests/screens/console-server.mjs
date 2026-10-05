// The console server for the screen protocol (T-017/T-022): serves the BUILT console
// (vite build + preview API, in-process, so no webServer ordering problem) and proxies /api
// to the booted backend. Lives inside globalSetup's process; stopped in global teardown.

import { preview } from 'vite';

export async function startConsole(baseUrlOfBackend) {
  const server = await preview({
    preview: {
      host: '127.0.0.1', // vite binds ::1 only by default; everything here speaks 127.0.0.1
      port: 4173,
      strictPort: true,
      proxy: { '/api': baseUrlOfBackend }
    }
  });
  return {
    url: 'http://127.0.0.1:4173',
    async close() {
      await server.httpServer.close();
      await new Promise((resolve) => setTimeout(resolve, 50));
    }
  };
}