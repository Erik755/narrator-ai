/**
 * Pipeline Component - Processing progress visualization
 */

const STEPS = [
  { id: 'upload', icon: '📤', label: 'Subiendo video', desc: 'Enviando video a Gemini AI...' },
  { id: 'analyze', icon: '🤖', label: 'Analizando video', desc: 'La IA está analizando el contenido...' },
  { id: 'tts', icon: '🗣️', label: 'Generando audio', desc: 'Creando narración con voz...' },
  { id: 'video', icon: '🎬', label: 'Procesando video', desc: 'Creando video vertical con subtítulos...' },
];

export function renderPipeline(container) {
  container.innerHTML = `
    <section class="view pipeline-view animate-fadeInUp" id="pipeline-view">
      <div class="pipeline-header">
        <h2 class="section-title">Procesando tu Contenido</h2>
        <p class="section-subtitle" id="pipeline-subtitle">Esto puede tardar unos momentos...</p>
      </div>

      <!-- Overall Progress -->
      <div class="pipeline-progress-overall glass-panel">
        <div class="progress-bar-wrapper">
          <div class="progress-bar" id="pipeline-progress-bar">
            <div class="progress-bar-fill" id="pipeline-progress-fill" style="width: 0%"></div>
          </div>
          <span class="progress-text" id="pipeline-progress-text">0%</span>
        </div>
      </div>

      <!-- Steps -->
      <div class="pipeline-steps" id="pipeline-steps">
        ${STEPS.map((step, i) => `
          <div class="pipeline-step ${i === 0 ? 'active' : 'pending'}" id="step-${step.id}" data-step="${step.id}">
            <div class="step-indicator">
              <div class="step-icon-wrapper">
                <span class="step-icon">${step.icon}</span>
                <div class="step-spinner" style="display:none">
                  <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 12a9 9 0 1 1-6.219-8.56"/></svg>
                </div>
                <div class="step-check" style="display:none">
                  <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="20 6 9 17 4 12"/></svg>
                </div>
                <div class="step-error-icon" style="display:none">
                  <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
                </div>
              </div>
              <div class="step-connector" ${i === STEPS.length - 1 ? 'style="display:none"' : ''}></div>
            </div>
            <div class="step-content">
              <h4 class="step-label">${step.label}</h4>
              <p class="step-desc">${step.desc}</p>
              <p class="step-time" id="step-time-${step.id}"></p>
            </div>
          </div>
        `).join('')}
      </div>

      <!-- Cancel button -->
      <div class="pipeline-actions">
        <button class="btn btn-ghost btn-sm" id="btn-cancel-pipeline" type="button">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg>
          Cancelar
        </button>
      </div>
    </section>
  `;

  // Cancel button
  document.getElementById('btn-cancel-pipeline').addEventListener('click', () => {
    if (confirm('¿Estás seguro de que quieres cancelar el procesamiento?')) {
      window.dispatchEvent(new CustomEvent('navigate', { detail: { view: 'upload' } }));
    }
  });
}

/**
 * Update pipeline step status
 * @param {string} stepId - Step identifier
 * @param {'pending'|'active'|'done'|'error'} status - New status
 * @param {string} [message] - Optional status message
 */
export function updatePipelineStep(stepId, status, message) {
  const stepEl = document.getElementById(`step-${stepId}`);
  if (!stepEl) return;

  // Remove all status classes
  stepEl.classList.remove('pending', 'active', 'done', 'error');
  stepEl.classList.add(status);

  // Update icon visibility
  const iconEl = stepEl.querySelector('.step-icon');
  const spinner = stepEl.querySelector('.step-spinner');
  const check = stepEl.querySelector('.step-check');
  const errorIcon = stepEl.querySelector('.step-error-icon');

  iconEl.style.display = status === 'pending' || status === 'active' ? 'block' : 'none';
  spinner.style.display = status === 'active' ? 'block' : 'none';
  check.style.display = status === 'done' ? 'flex' : 'none';
  errorIcon.style.display = status === 'error' ? 'flex' : 'none';

  // Update message
  if (message) {
    const descEl = stepEl.querySelector('.step-desc');
    descEl.textContent = message;
  }

  // Update time
  const timeEl = document.getElementById(`step-time-${stepId}`);
  if (status === 'active') {
    timeEl.textContent = 'En progreso...';
    timeEl.dataset.startTime = Date.now();
  } else if (status === 'done' && timeEl.dataset.startTime) {
    const elapsed = ((Date.now() - parseInt(timeEl.dataset.startTime)) / 1000).toFixed(1);
    timeEl.textContent = `Completado en ${elapsed}s`;
  } else if (status === 'error') {
    timeEl.textContent = 'Error';
  }
}

/**
 * Update overall progress bar
 * @param {number} percent - 0 to 100
 */
export function updatePipelineProgress(percent) {
  const fill = document.getElementById('pipeline-progress-fill');
  const text = document.getElementById('pipeline-progress-text');
  if (fill) fill.style.width = `${Math.min(100, Math.max(0, percent))}%`;
  if (text) text.textContent = `${Math.round(percent)}%`;
}

/**
 * Mark pipeline as completed
 */
export function completePipeline() {
  updatePipelineProgress(100);
  const subtitle = document.getElementById('pipeline-subtitle');
  if (subtitle) subtitle.textContent = '¡Procesamiento completado! 🎉';
}

/**
 * Mark pipeline as failed
 */
export function failPipeline(errorMessage) {
  const subtitle = document.getElementById('pipeline-subtitle');
  if (subtitle) {
    subtitle.textContent = `Error: ${errorMessage}`;
    subtitle.style.color = 'var(--color-error)';
  }
}
