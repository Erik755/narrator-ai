/**
 * Setup Component - API Key configuration screen (first-time use)
 */

import { saveApiKey, getApiKey } from '../services/storage.js';
import { validateApiKey, initNvidia as initAI } from '../services/nvidia.js';

export function renderSetup(container, onComplete) {
  const existingKey = getApiKey();

  container.innerHTML = `
    <section class="view setup-screen animate-fadeInUp" id="setup-view">
      <div class="setup-card glass-panel">
        <div class="setup-header">
          <span class="setup-emoji">🚀</span>
          <h2 class="setup-title">Configura tu API Key</h2>
          <p class="setup-subtitle">
            Necesitas un API Key gratuito de NVIDIA NIM para analizar tus videos con IA.
            <strong>Es 100% gratis</strong> (recibes créditos gratuitos abundantes).
          </p>
        </div>

        <div class="setup-steps">
          <div class="step-item">
            <span class="step-number">1</span>
            <div class="step-content">
              <p class="step-text">Ve a NVIDIA Build y crea tu API Key gratis</p>
              <a href="https://build.nvidia.com" target="_blank" rel="noopener noreferrer" class="setup-link btn btn-secondary btn-sm">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/><polyline points="15 3 21 3 21 9"/><line x1="10" y1="14" x2="21" y2="3"/></svg>
                Obtener API Key NVIDIA
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
            <label class="label" for="api-key-input">Tu API Key de NVIDIA</label>
            <div class="input-wrapper">
              <input
                type="password"
                id="api-key-input"
                class="input api-key-input"
                placeholder="nvapi-..."
                value="${existingKey || ''}"
                required
                autocomplete="off"
              />
              <button type="button" class="btn-icon toggle-password" aria-label="Mostrar contraseña" id="toggle-pwd">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
              </button>
            </div>
            <p class="input-help">Tu clave se guarda localmente en tu navegador y nunca se envía a nuestros servidores (no tenemos servidores).</p>
          </div>

          <div class="setup-actions">
            <button type="submit" class="btn btn-primary" id="btn-save-key">
              <span>Guardar y Continuar</span>
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="5" y1="12" x2="19" y2="12"/><polyline points="12 5 19 12 12 19"/></svg>
            </button>
          </div>
          <div id="setup-error" class="setup-error" style="display: none;"></div>
        </form>
      </div>
    </section>
  `;

  // Toggle password visibility
  const pwdInput = document.getElementById('api-key-input');
  const toggleBtn = document.getElementById('toggle-pwd');
  
  toggleBtn.addEventListener('click', () => {
    const type = pwdInput.getAttribute('type') === 'password' ? 'text' : 'password';
    pwdInput.setAttribute('type', type);
    toggleBtn.classList.toggle('active');
  });

  // Handle form submission
  const form = document.getElementById('setup-form');
  const submitBtn = document.getElementById('btn-save-key');
  const errorMsg = document.getElementById('setup-error');

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const key = pwdInput.value.trim();
    
    if (!key) return;

    // Loading state
    submitBtn.classList.add('loading');
    submitBtn.disabled = true;
    errorMsg.style.display = 'none';

    try {
      // Validate key
      const isValid = await validateApiKey(key);
      
      if (isValid) {
        saveApiKey(key);
        initAI(key);
        onComplete();
      } else {
        throw new Error('API Key inválida o sin permisos.');
      }
    } catch (err) {
      console.error(err);
      errorMsg.textContent = 'Error: ' + err.message;
      errorMsg.style.display = 'block';
    } finally {
      submitBtn.classList.remove('loading');
      submitBtn.disabled = false;
    }
  });
}
