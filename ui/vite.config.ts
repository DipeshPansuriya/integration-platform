import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The console (E3 lane D, T-158): minimal Vite + React scaffold. The design rules are T-095's
// (design.md is mahesh-architect's); nothing is invented here beyond the scaffold itself.
// The screen-test protocol (playwright.config.mjs) proxies /api to the booted backend:
// API_PROXY_TARGET is a run-time environment NAME (R07 - a name, never a value in any file).
const proxyTarget = process.env.API_PROXY_TARGET;

export default defineConfig({
  plugins: [react()],
  preview: {
    proxy: proxyTarget ? { '/api': proxyTarget } : undefined
  }
});