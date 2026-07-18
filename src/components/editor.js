/**
 * Editor Component - Script editor with editing, copy, regenerate
 */

export function renderEditor(container, scriptData, onRegenerateRequest) {
  const { title, script, segments, hashtags, caption } = scriptData;

  // Calculate word count and estimated duration
  const wordCount = script.split(/\s+/).filter(Boolean).length;
  const estimatedDuration = Math.ceil(wordCount / 2.5); // ~150 words/min = 2.5 words/sec

  container.innerHTML = `
    <section class="view editor-view animate-fadeInUp" id="editor-view">
      <div class="editor-header">
        <h2 class="section-title">📝 Tu Guion</h2>
        <p class="section-subtitle">Revisa y edita el guion generado por IA antes de crear el audio</p>
      </div>

      <!-- Title -->
      <div class="editor-title-group glass-panel">
        <label class="label" for="editor-title">Título del Contenido</label>
        <input type="text" id="editor-title" class="input" value="${escapeHtml(title)}" placeholder="Título de tu contenido" />
      </div>

      <!-- Script Editor -->
      <div class="editor-container glass-panel">
        <div class="editor-toolbar">
          <div class="toolbar-left">
            <span class="toolbar-label">Guion de Narración</span>
          </div>
          <div class="toolbar-right">
            <button class="btn btn-ghost btn-sm" id="btn-copy-script" type="button" title="Copiar guion">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
              Copiar
            </button>
            <button class="btn btn-ghost btn-sm" id="btn-regenerate" type="button" title="Regenerar guion">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M23 4v6h-6"/><path d="M1 20v-6h6"/><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/></svg>
              Regenerar
            </button>
          </div>
        </div>
        <textarea class="editor-textarea" id="editor-textarea" rows="8" spellcheck="true">${escapeHtml(script)}</textarea>
        <div class="editor-footer">
          <span class="word-count" id="word-count">${wordCount} palabras · ~${estimatedDuration}s de audio</span>
        </div>
      </div>

      <!-- Segments Timeline -->
      <div class="editor-segments glass-panel">
        <div class="segments-header">
          <span class="toolbar-label">Segmentos con Timestamps</span>
          <span class="segments-count">${segments.length} segmentos</span>
        </div>
        <div class="segments-list" id="segments-list">
          ${segments.map((seg, i) => `
            <div class="segment-item" data-index="${i}">
              <div class="segment-time">
                <span class="segment-start">${formatTime(seg.start)}</span>
                <span class="segment-arrow">→</span>
                <span class="segment-end">${formatTime(seg.end)}</span>
              </div>
              <p class="segment-text">${escapeHtml(seg.text)}</p>
            </div>
          `).join('')}
        </div>
      </div>

      <!-- Hashtags & Caption -->
      ${hashtags && hashtags.length > 0 ? `
        <div class="editor-social glass-panel">
          <div class="social-section">
            <span class="toolbar-label">Hashtags</span>
            <div class="hashtags-row" id="hashtags-row">
              ${hashtags.map(h => `<span class="hashtag-badge">${h}</span>`).join('')}
            </div>
            <button class="btn btn-ghost btn-sm" id="btn-copy-hashtags" type="button">Copiar hashtags</button>
          </div>
          ${caption ? `
            <div class="social-section">
              <span class="toolbar-label">Caption</span>
              <p class="caption-text" id="caption-text">${escapeHtml(caption)}</p>
              <button class="btn btn-ghost btn-sm" id="btn-copy-caption" type="button">Copiar caption</button>
            </div>
          ` : ''}
        </div>
      ` : ''}

      <!-- Actions -->
      <div class="editor-actions">
        <button class="btn btn-secondary" id="btn-back-config" type="button">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="19" y1="12" x2="5" y2="12"/><polyline points="12 19 5 12 12 5"/></svg>
          Volver
        </button>
        <button class="btn btn-primary btn-lg btn-glow" id="btn-generate-audio" type="button">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"/><path d="M19.07 4.93a10 10 0 0 1 0 14.14M15.54 8.46a5 5 0 0 1 0 7.07"/></svg>
          Generar Audio y Video
        </button>
      </div>
    </section>
  `;

  // --- Event Handlers ---

  // Copy script
  document.getElementById('btn-copy-script').addEventListener('click', async () => {
    const text = document.getElementById('editor-textarea').value;
    await navigator.clipboard.writeText(text);
    showBtnFeedback('btn-copy-script', '✅ Copiado');
  });

  // Copy hashtags
  const btnHashtags = document.getElementById('btn-copy-hashtags');
  if (btnHashtags) {
    btnHashtags.addEventListener('click', async () => {
      await navigator.clipboard.writeText(hashtags.join(' '));
      showBtnFeedback('btn-copy-hashtags', '✅ Copiado');
    });
  }

  // Copy caption
  const btnCaption = document.getElementById('btn-copy-caption');
  if (btnCaption) {
    btnCaption.addEventListener('click', async () => {
      const text = document.getElementById('caption-text').textContent;
      await navigator.clipboard.writeText(text);
      showBtnFeedback('btn-copy-caption', '✅ Copiado');
    });
  }

  // Regenerate
  document.getElementById('btn-regenerate').addEventListener('click', () => {
    if (confirm('¿Regenerar el guion? Se perderá el texto actual.')) {
      onRegenerateRequest?.();
    }
  });

  // Word count update
  document.getElementById('editor-textarea').addEventListener('input', (e) => {
    const words = e.target.value.split(/\s+/).filter(Boolean).length;
    const dur = Math.ceil(words / 2.5);
    document.getElementById('word-count').textContent = `${words} palabras · ~${dur}s de audio`;
  });

  // Back button
  document.getElementById('btn-back-config').addEventListener('click', () => {
    window.dispatchEvent(new CustomEvent('navigate', { detail: { view: 'config' } }));
  });

  // Generate audio & video
  document.getElementById('btn-generate-audio').addEventListener('click', () => {
    // Get potentially edited script
    const editedScript = document.getElementById('editor-textarea').value;
    const editedTitle = document.getElementById('editor-title').value;

    const updatedData = {
      ...scriptData,
      title: editedTitle,
      script: editedScript,
    };

    window.dispatchEvent(new CustomEvent('generate-media', { detail: { scriptData: updatedData } }));
  });
}

function formatTime(seconds) {
  const m = Math.floor(seconds / 60);
  const s = Math.floor(seconds % 60);
  return `${m}:${s.toString().padStart(2, '0')}`;
}

function escapeHtml(text) {
  if (!text) return '';
  const div = document.createElement('div');
  div.textContent = text;
  return div.innerHTML;
}

function showBtnFeedback(btnId, text) {
  const btn = document.getElementById(btnId);
  if (!btn) return;
  const original = btn.innerHTML;
  btn.textContent = text;
  btn.disabled = true;
  setTimeout(() => {
    btn.innerHTML = original;
    btn.disabled = false;
  }, 1500);
}
