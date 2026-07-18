/**
 * History Component - Project history from localStorage
 */

import { getProjects, deleteProject } from '../services/storage.js';
import { formatDuration } from '../utils/helpers.js';

export function renderHistory(container) {
  const projects = getProjects();

  container.innerHTML = `
    <section class="view history-view animate-fadeInUp" id="history-view">
      <div class="history-header">
        <button class="btn btn-ghost btn-sm" id="btn-back-home" type="button">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="19" y1="12" x2="5" y2="12"/><polyline points="12 19 5 12 12 5"/></svg>
          Volver
        </button>
        <h2 class="section-title">📜 Historial de Proyectos</h2>
        <p class="section-subtitle">${projects.length} proyecto${projects.length !== 1 ? 's' : ''} guardado${projects.length !== 1 ? 's' : ''}</p>
      </div>

      ${projects.length === 0 ? `
        <div class="history-empty glass-panel">
          <span class="empty-icon">📭</span>
          <h3 class="empty-title">Sin proyectos aún</h3>
          <p class="empty-desc">Los proyectos que generes aparecerán aquí</p>
          <button class="btn btn-primary" id="btn-start-new" type="button">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
            Crear Nuevo Proyecto
          </button>
        </div>
      ` : `
        <div class="history-grid">
          ${projects.map(p => `
            <div class="history-card glass-panel" data-project-id="${p.id}">
              <div class="history-card-header">
                <span class="history-tone-badge badge badge-tone-${p.tone}">${getToneEmoji(p.tone)} ${p.tone}</span>
                <button class="btn btn-ghost btn-sm history-delete" data-delete-id="${p.id}" title="Eliminar" aria-label="Eliminar proyecto">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>
                </button>
              </div>
              <h4 class="history-card-title">${escapeHtml(p.title || 'Sin título')}</h4>
              <p class="history-card-script">${escapeHtml((p.script || '').slice(0, 120))}${(p.script || '').length > 120 ? '...' : ''}</p>
              <div class="history-card-footer">
                <span class="history-date">${formatDate(p.createdAt)}</span>
                <span class="history-lang">${p.language === 'es' ? '🇪🇸' : '🇺🇸'}</span>
              </div>
            </div>
          `).join('')}
        </div>
      `}
    </section>
  `;

  // Back button
  document.getElementById('btn-back-home').addEventListener('click', () => {
    window.dispatchEvent(new CustomEvent('navigate', { detail: { view: 'upload' } }));
  });

  // Start new project
  const btnNew = document.getElementById('btn-start-new');
  if (btnNew) {
    btnNew.addEventListener('click', () => {
      window.dispatchEvent(new CustomEvent('navigate', { detail: { view: 'upload' } }));
    });
  }

  // Delete project
  container.addEventListener('click', (e) => {
    const deleteBtn = e.target.closest('[data-delete-id]');
    if (!deleteBtn) return;

    const id = deleteBtn.dataset.deleteId;
    if (confirm('¿Eliminar este proyecto del historial?')) {
      deleteProject(id);
      renderHistory(container); // Re-render
    }
  });
}

function getToneEmoji(tone) {
  const emojis = {
    profesional: '🎯',
    gracioso: '😂',
    tierno: '🥰',
    motivacional: '💪',
    informativo: '📚',
    sarcastico: '😏',
  };
  return emojis[tone] || '🎯';
}

function formatDate(isoString) {
  if (!isoString) return '';
  const d = new Date(isoString);
  return d.toLocaleDateString('es-ES', { day: 'numeric', month: 'short', year: 'numeric' });
}

function escapeHtml(text) {
  if (!text) return '';
  const div = document.createElement('div');
  div.textContent = text;
  return div.innerHTML;
}
