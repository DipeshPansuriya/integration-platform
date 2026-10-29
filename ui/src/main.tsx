// T-158 (E3 lane D): the console's entry point. T78 lane F: the token layer and the
// base primitives load here, before the app renders; the theme itself is resolved
// inline in index.html before first paint (no flash of wrong theme).

import './tokens.css';
import './ui/base.css';
// iPaaS Console design system: ONE root import (W3) — tokens + components as a
// library; ds.css keeps the app chrome winning the collisions (removed in W6).
import './ds.css';
// W6: the console adopts the DS look (accent engine, glass, shell restyle) - loaded last so it wins.
import './console/adopt.css';
import React from 'react';
import ReactDOM from 'react-dom/client';
import { App } from './App';
// W5: the ONLY mock install point. Off unless VITE_API_MODE=mock (default = the real backend);
// static import so the switch is decided before React mounts. mock.ts itself never reads env.
import { installMockApi } from './api/mock';

if (import.meta.env.VITE_API_MODE === 'mock') installMockApi();

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);