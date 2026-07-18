/**
 * Setup Component - API Key configuration screen (first-time use)
 */

import { saveApiKey, getApiKey } from '../services/storage.js';
import { validateApiKey } from '../services/gemini.js';
import { initGemini } from '../services/gemini.js';

export function renderSetup(container, onComplete) {
  const existingKey = getApiKey();

  container.innerHTML = `
    <section class="view setup-screen animate-fadeInUp" id="setup-view">
      <div class="setup-card glass-panel">
        <div class="setup-header">
          <span class="setup-emoji">🔑</span>
          <h2 class="setup-title">Configura tu API Key</h2>
          <p class="setup-subtitle">
            Necesitas un API Key gratuito de Google Gemini para analizar tus videos con IA.
            <strong>Es 100% gratis</strong>, no requiere tarjeta de crédito.
          </p>
        </div>

        <div class="setup-steps">
          <div class="step-item">
            <span class="step-number">1</span>
            <div class="step-content">
              <p class="step-text">Ve a Google AI Studio y crea tu API Key gratis</p>
              <a href="https://aistudio.google.com/apikey" target="_blank" rel="noopener noreferrer" class="setup-link btn btn-secondary btn-sm">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/><polyline points="15 3 21 3 21 9"/><line x1="10" y1="14" x2="21" y2="3"/></svg>
                Obtener API Key Gratis
              </a>
            </div>
          </div>
          <div class="step-item">
            <span class="step-number">2</span>
            <div class="step-content">
              <p class="step-text">Pega tu API Key aquí abajo</p>
            </div>
          </div>
        </div>

        <form class="setup-form" id="setup-form">
          <div class="input-group">
            <label class="label" for="api-key-input">Tu API Key de Gemini</label>
            <div class="input-wrapper">
              <input
                type="password"
                id="api-key-input"
                class="input api-key-input"
                placeholder="AIza..."
                value="${existingKey || ''}"
                autocomplete="off"
                spellcheck="false"
                required
              />
              <button type="button" class="btn-icon input-toggle" id="toggle-key-visibility" title="Mostrar/ocultar key" aria-label="Toggle key visibility">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
              </button>
            </div>
          </div>

          <div class="setup-status" id="setup-status"></div>

          <button type="submit" class="btn btn-primary btn-lg setup-submit" id="setup-submit">
            <span class="btn-text">Verificar y Continuar</span>
            <span class="btn-loader" style="display:none">
              <svg class="spin" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 12a9 9 0 1 1-6.219-8.56"/></svg>
            </span>
          </button>
        </form>

        <div class="setup-footer">
          <p class="setup-note">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>
            Tu API Key se guarda solo en tu dispositivo. Nunca se envía a terceros.
          </p>
        </div>
      </div>
    </section>
  `;

  // Event Listeners
  const form = document.getElementById('setup-form');
  const input = document.getElementById('api-key-input');
  const toggleBtn = document.getElementById('toggle-key-visibility');
  const submitBtn = document.getElementById('setup-submit');
  const status = document.getElementById('setup-status');

  // Toggle password visibility
  toggleBtn.addEventListener('click', () => {
    const isPassword = input.type === 'password';
    input.type = isPassword ? 'text' : 'password';
    toggleBtn.innerHTML = isPassword
      ? '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/><line x1="1" y1="1" x2="23" y2="23"/></svg>'
      : '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>';
  });

  // Form submission
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const key = input.value.trim();

    if (!key) {
      showStatus(status, 'error', 'Por favor ingresa tu API Key');
      return;
    }

    // Show loading state
    submitBtn.disabled = true;
    submitBtn.querySelector('.btn-text').style.display = 'none';
    submitBtn.querySelector('.btn-loader').style.display = 'flex';
    showStatus(status, 'info', 'Verificando API Key...');

    try {
      const isValid = await validateApiKey(key);

      if (isValid) {
        saveApiKey(key);
        initGemini(key);
        showStatus(status, 'success', '✅ API Key válido. ¡Listo para crear contenido!');
        
        // Transition to main app after brief delay
        setTimeout(() => {
          onComplete();
        }, 1000);
      } else {
        showStatus(status, 'error', '❌ API Key inválido. Verifica que lo copiaste correctamente.');
        resetButton();
      }
    } catch (err) {
      showStatus(status, 'error', `❌ Error: ${err.message}`);
      resetButton();
    }
  });

  function resetButton() {
    submitBtn.disabled = false;
    submitBtn.querySelector('.btn-text').style.display = 'inline';
    submitBtn.querySelector('.btn-loader').style.display = 'none';
  }

  // Auto-focus input if empty
  if (!existingKey) {
    setTimeout(() => input.focus(), 300);
  }
}

function showStatus(el, type, message) {
  el.className = `setup-status status-${type}`;
  el.textContent = message;
  el.style.display = 'block';
}
