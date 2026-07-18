/**
 * NarradorAI - Entry Point
 * Initializes the app, registers service worker, and handles PWA
 */

import { initApp } from './app.js';

// Initialize app when DOM is ready
document.addEventListener('DOMContentLoaded', () => {
  // Hide loading screen
  const loadingScreen = document.getElementById('loading-screen');
  if (loadingScreen) {
    loadingScreen.classList.add('animate-fadeOut');
    setTimeout(() => {
      loadingScreen.remove();
      initApp();
    }, 400);
  } else {
    initApp();
  }
});

// Register Service Worker for PWA
if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js')
      .then(reg => {
        console.log('✅ Service Worker registered:', reg.scope);
      })
      .catch(err => {
        console.warn('⚠️ Service Worker registration failed:', err);
      });
  });
}

// PWA Install Prompt
let deferredPrompt;
window.addEventListener('beforeinstallprompt', (e) => {
  e.preventDefault();
  deferredPrompt = e;

  // Show install button after a delay
  setTimeout(() => {
    showInstallBanner();
  }, 10000); // Show after 10s
});

function showInstallBanner() {
  if (!deferredPrompt) return;

  const banner = document.createElement('div');
  banner.className = 'install-banner glass-panel animate-slideIn';
  banner.innerHTML = `
    <div class="install-banner-content">
      <span class="install-banner-icon">📲</span>
      <div class="install-banner-text">
        <strong>Instalar NarradorAI</strong>
        <p>Accede rápido desde tu pantalla de inicio</p>
      </div>
      <div class="install-banner-actions">
        <button class="btn btn-primary btn-sm" id="btn-install">Instalar</button>
        <button class="btn btn-ghost btn-sm" id="btn-dismiss-install">Ahora no</button>
      </div>
    </div>
  `;
  document.body.appendChild(banner);

  document.getElementById('btn-install').addEventListener('click', async () => {
    deferredPrompt.prompt();
    const { outcome } = await deferredPrompt.userChoice;
    console.log(`Install prompt outcome: ${outcome}`);
    deferredPrompt = null;
    banner.remove();
  });

  document.getElementById('btn-dismiss-install').addEventListener('click', () => {
    banner.remove();
  });
}

// Handle app installed
window.addEventListener('appinstalled', () => {
  console.log('✅ NarradorAI installed as PWA');
  deferredPrompt = null;
});
