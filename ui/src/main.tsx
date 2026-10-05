// T-158 (E3 lane D): the console's entry point. No visual design yet - design.md (T-095,
// mahesh-architect) is the agreed look; nothing is invented here beyond the scaffold.

import React from 'react';
import ReactDOM from 'react-dom/client';
import { App } from './App';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);