// T-158 (E3 lane D): the console's entry point. T78 lane F: the token layer and the
// base primitives load here, before the app renders; the theme itself is resolved
// inline in index.html before first paint (no flash of wrong theme).

import './tokens.css';
import './ui/base.css';
import React from 'react';
import ReactDOM from 'react-dom/client';
import { App } from './App';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);