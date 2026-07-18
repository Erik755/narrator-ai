/**
 * Preview Component - Video and audio preview with playback controls
 */

export function renderPreview(container, { videoBlob, videoBlobNoSubs, audioBlob, scriptData }) {
  const videoUrl = videoBlob ? URL.createObjectURL(videoBlob) : null;
  const videoNoSubsUrl = videoBlobNoSubs ? URL.createObjectURL(videoBlobNoSubs) : null;
  const audioUrl = audioBlob ? URL.createObjectURL(audioBlob) : null;

  container.innerHTML = `
    <section class="view preview-view animate-fadeInUp" id="preview-view">
      <div class="preview-header">
        <h2 class="section-title">✅ ¡Contenido Listo!</h2>
        <p class="section-subtitle">Previsualiza tu contenido antes de descargarlo</p>
      </div>

      <div class="preview-layout">
        <!-- Video Preview -->
        <div class="preview-video-section">
          <div class="preview-container glass-panel">
            <div class="preview-toggle-bar">
              <button class="preview-tab active" id="tab-with-subs" type="button">Con subtítulos</button>
              <button class="preview-tab" id="tab-without-subs" type="button">Sin subtítulos</button>
            </div>
            <div class="preview-video-wrapper">
              ${videoUrl ? `
                <video class="preview-video" id="preview-video" controls playsinline>
                  <source src="${videoUrl}" type="video/mp4" />
                </video>
              ` : `
                <div class="preview-placeholder">
                  <span>🎬</span>
                  <p>Video procesado no disponible</p>
                </div>
              `}
            </div>
          </div>

          <!-- Audio Player -->
          ${audioUrl ? `
            <div class="audio-player glass-panel">
              <div class="audio-header">
                <span class="audio-label">🗣️ Audio de Narración</span>
              </div>
              <audio id="preview-audio" controls style="width:100%">
                <source src="${audioUrl}" type="audio/wav" />
              </audio>
            </div>
          ` : ''}
        </div>

        <!-- Script & Social -->
        <div class="preview-info-section">
          <!-- Script Preview -->
          <div class="preview-script glass-panel">
            <h4 class="preview-section-label">📝 Guion</h4>
            <p class="preview-script-text">${escapeHtml(scriptData.script)}</p>
          </div>

          <!-- Social Info -->
          ${scriptData.hashtags?.length > 0 ? `
            <div class="preview-social glass-panel">
              <h4 class="preview-section-label">#️⃣ Hashtags</h4>
              <div class="hashtags-row">
                ${scriptData.hashtags.map(h => `<span class="hashtag-badge">${h}</span>`).join('')}
              </div>
              ${scriptData.caption ? `
                <h4 class="preview-section-label" style="margin-top: var(--space-3)">✍️ Caption</h4>
                <p class="preview-caption-text">${escapeHtml(scriptData.caption)}</p>
              ` : ''}
            </div>
          ` : ''}
        </div>
      </div>

      <!-- Export Section -->
      <div class="preview-export" id="export-section">
        <!-- Export component will be rendered here -->
      </div>

      <!-- New Project Button -->
      <div class="preview-actions">
        <button class="btn btn-secondary" id="btn-new-project" type="button">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
          Nuevo Proyecto
        </button>
      </div>
    </section>
  `;

  // Tab switching (with/without subtitles)
  const tabWith = document.getElementById('tab-with-subs');
  const tabWithout = document.getElementById('tab-without-subs');
  const videoEl = document.getElementById('preview-video');

  if (tabWith && tabWithout && videoEl && videoUrl && videoNoSubsUrl) {
    tabWith.addEventListener('click', () => {
      tabWith.classList.add('active');
      tabWithout.classList.remove('active');
      videoEl.src = videoUrl;
      videoEl.load();
    });

    tabWithout.addEventListener('click', () => {
      tabWithout.classList.add('active');
      tabWith.classList.remove('active');
      videoEl.src = videoNoSubsUrl;
      videoEl.load();
    });
  }

  // New project
  document.getElementById('btn-new-project').addEventListener('click', () => {
    window.dispatchEvent(new CustomEvent('navigate', { detail: { view: 'upload' } }));
  });
}

function escapeHtml(text) {
  if (!text) return '';
  const div = document.createElement('div');
  div.textContent = text;
  return div.innerHTML;
}
