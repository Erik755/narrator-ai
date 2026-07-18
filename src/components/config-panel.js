/**
 * Config Panel Component - Tone, language, format, and platform selection
 */

const TONES = [
  { id: 'profesional', emoji: '🎯', label: 'Profesional', desc: 'Formal e informativo' },
  { id: 'gracioso', emoji: '😂', label: 'Gracioso', desc: 'Divertido y entretenido' },
  { id: 'tierno', emoji: '🥰', label: 'Tierno', desc: 'Dulce y emotivo' },
  { id: 'motivacional', emoji: '💪', label: 'Motivacional', desc: 'Inspirador y energético' },
  { id: 'informativo', emoji: '📚', label: 'Informativo', desc: 'Educativo y detallado' },
  { id: 'sarcastico', emoji: '😏', label: 'Sarcástico', desc: 'Irónico y mordaz' },
];

const PLATFORMS = [
  { id: 'tiktok', label: 'TikTok', icon: '📱', ratio: '9:16' },
  { id: 'reels', label: 'Instagram Reels', icon: '📷', ratio: '9:16' },
  { id: 'shorts', label: 'YouTube Shorts', icon: '▶️', ratio: '9:16' },
  { id: 'square', label: 'Instagram Post', icon: '⬜', ratio: '1:1' },
  { id: 'portrait', label: 'Retrato (4:5)', icon: '📐', ratio: '4:5' },
];

const LANGUAGES = [
  { id: 'es', label: 'Español', flag: '🇪🇸' },
  { id: 'en', label: 'English', flag: '🇺🇸' },
];

export function renderConfigPanel(container, videoFile, onStartProcessing) {
  let selectedTone = 'profesional';
  let selectedPlatform = 'tiktok';
  let selectedLanguage = 'es';

  container.innerHTML = `
    <section class="view config-view animate-fadeInUp" id="config-view">
      <div class="config-header">
        <button class="btn btn-ghost btn-sm" id="btn-back-upload" type="button">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="19" y1="12" x2="5" y2="12"/><polyline points="12 19 5 12 12 5"/></svg>
          Volver
        </button>
        <h2 class="section-title">Configura tu Contenido</h2>
        <p class="section-subtitle">Elige el estilo de narración para tu video</p>
      </div>

      <!-- Video Preview Mini -->
      <div class="config-video-preview glass-panel" id="config-video-preview">
        <video class="config-thumb" id="config-thumb" muted></video>
        <div class="config-video-info">
          <p class="config-filename" id="config-filename"></p>
        </div>
      </div>

      <!-- Tone Selection -->
      <div class="config-section">
        <h3 class="config-label">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z"/></svg>
          Tono de la Narración
        </h3>
        <div class="tone-grid" id="tone-grid">
          ${TONES.map(t => `
            <button class="tone-chip ${t.id === selectedTone ? 'active' : ''}" data-tone="${t.id}" type="button" aria-pressed="${t.id === selectedTone}">
              <span class="tone-emoji">${t.emoji}</span>
              <span class="tone-label">${t.label}</span>
              <span class="tone-desc">${t.desc}</span>
            </button>
          `).join('')}
        </div>
      </div>

      <!-- Language Selection -->
      <div class="config-section">
        <h3 class="config-label">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="2" y1="12" x2="22" y2="12"/><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/></svg>
          Idioma del Contenido
        </h3>
        <div class="language-chips" id="language-chips">
          ${LANGUAGES.map(l => `
            <button class="lang-chip ${l.id === selectedLanguage ? 'active' : ''}" data-lang="${l.id}" type="button" aria-pressed="${l.id === selectedLanguage}">
              <span class="lang-flag">${l.flag}</span>
              <span class="lang-label">${l.label}</span>
            </button>
          `).join('')}
        </div>
      </div>

      <!-- Platform Selection -->
      <div class="config-section">
        <h3 class="config-label">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="3" width="20" height="14" rx="2" ry="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/></svg>
          Plataforma de Destino
        </h3>
        <div class="platform-chips" id="platform-chips">
          ${PLATFORMS.map(p => `
            <button class="platform-chip ${p.id === selectedPlatform ? 'active' : ''}" data-platform="${p.id}" type="button" aria-pressed="${p.id === selectedPlatform}">
              <span class="platform-icon">${p.icon}</span>
              <span class="platform-label">${p.label}</span>
              <span class="platform-ratio">${p.ratio}</span>
            </button>
          `).join('')}
        </div>
      </div>

      <!-- Options -->
      <div class="config-section">
        <h3 class="config-label">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 20h9"/><path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"/></svg>
          Opciones Adicionales
        </h3>
        <div class="options-list">
          <label class="option-toggle">
            <input type="checkbox" id="opt-hashtags" checked />
            <span class="toggle-slider"></span>
            <span class="option-text">Generar hashtags</span>
          </label>
          <label class="option-toggle">
            <input type="checkbox" id="opt-caption" checked />
            <span class="toggle-slider"></span>
            <span class="option-text">Generar caption para redes</span>
          </label>
          <label class="option-toggle">
            <input type="checkbox" id="opt-subtitles" checked />
            <span class="toggle-slider"></span>
            <span class="option-text">Incluir subtítulos en video</span>
          </label>
        </div>
      </div>

      <!-- Start Button -->
      <div class="config-actions">
        <button class="btn btn-primary btn-lg btn-glow" id="btn-start-processing" type="button">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M13 2L3 14h9l-1 8 10-12h-9l1-8z"/></svg>
          Generar Narración con IA
        </button>
      </div>
    </section>
  `;

  // Load video thumbnail
  const thumb = document.getElementById('config-thumb');
  const filename = document.getElementById('config-filename');
  if (videoFile) {
    const url = URL.createObjectURL(videoFile);
    thumb.src = url;
    thumb.load();
    thumb.addEventListener('loadedmetadata', () => {
      thumb.currentTime = thumb.duration * 0.25;
    }, { once: true });
    filename.textContent = videoFile.name;
  }

  // Tone selection
  document.getElementById('tone-grid').addEventListener('click', (e) => {
    const chip = e.target.closest('.tone-chip');
    if (!chip) return;
    selectedTone = chip.dataset.tone;
    document.querySelectorAll('.tone-chip').forEach(c => {
      c.classList.toggle('active', c.dataset.tone === selectedTone);
      c.setAttribute('aria-pressed', c.dataset.tone === selectedTone);
    });
  });

  // Language selection
  document.getElementById('language-chips').addEventListener('click', (e) => {
    const chip = e.target.closest('.lang-chip');
    if (!chip) return;
    selectedLanguage = chip.dataset.lang;
    document.querySelectorAll('.lang-chip').forEach(c => {
      c.classList.toggle('active', c.dataset.lang === selectedLanguage);
      c.setAttribute('aria-pressed', c.dataset.lang === selectedLanguage);
    });
  });

  // Platform selection
  document.getElementById('platform-chips').addEventListener('click', (e) => {
    const chip = e.target.closest('.platform-chip');
    if (!chip) return;
    selectedPlatform = chip.dataset.platform;
    document.querySelectorAll('.platform-chip').forEach(c => {
      c.classList.toggle('active', c.dataset.platform === selectedPlatform);
      c.setAttribute('aria-pressed', c.dataset.platform === selectedPlatform);
    });
  });

  // Back button
  document.getElementById('btn-back-upload').addEventListener('click', () => {
    // Re-render upload view - handled by app.js
    window.dispatchEvent(new CustomEvent('navigate', { detail: { view: 'upload' } }));
  });

  // Start processing
  document.getElementById('btn-start-processing').addEventListener('click', () => {
    const config = {
      tone: selectedTone,
      language: selectedLanguage,
      platform: selectedPlatform,
      aspectRatio: PLATFORMS.find(p => p.id === selectedPlatform)?.ratio || '9:16',
      includeHashtags: document.getElementById('opt-hashtags').checked,
      includeCaption: document.getElementById('opt-caption').checked,
      includeSubtitles: document.getElementById('opt-subtitles').checked,
    };
    onStartProcessing(config);
  });
}

export { TONES, PLATFORMS, LANGUAGES };
