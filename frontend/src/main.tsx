import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';
import './base.css';

// Sprint 10.185: Google Analytics is NOT started here. It is consent-gated — the ConsentProvider loads it only
// after the visitor explicitly accepts analytics (and re-loads it for a returning visitor who already accepted).

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
