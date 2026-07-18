/**
 * Upload Component - Video upload with drag & drop
 */

import { formatFileSize, formatDuration } from '../utils/helpers.js';

const ACCEPTED_FORMATS = ['video/mp4', 'video/webm', 'video/quicktime', 'video/x-msvideo', 'video/x-matroska'];
const MAX_FILE_SIZE = 100 * 1024 * 1024; // 100MB recommended limit

export function renderUpload(container, onVideoSelected) {
  container.innerHTML = `
    <section class="view upload-view animate-fadeInUp" id="upload-view">
      <div class="upload-header">
        <h2 class="section-title">Sube tu Video</h2>
        <p class="section-subtitle">Arrastra un video o haz clic para seleccionar. La IA analizará el contenido y generará la narración.</p>
      </div>

      <div class="upload-zone glass-panel" id="upload-zone" role="button" tabindex="0" aria-label="Subir video">
        <input type="file" id="video-input" accept="video/*" hidden />
        
        <div class="upload-zone-content" id="upload-zone-content">
          <div class="upload-icon-wrapper">
            <svg class="upload-icon" width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
              <polyline points="17 8 12 3 7 8"/>
              <line x1="12" y1="3" x2="12" y2="15"/>
            </svg>
          </div>
          <p class="upload-text-main">Arrastra tu video aquí</p>
          <p class="upload-text-secondary">o haz clic para seleccionar</p>
          <div class="upload-formats">
            <span class="format-badge">MP4</span>
            <span class="format-badge">WebM</span>
            <span class="format-badge">MOV</span>
            <span class="format-badge">AVI</span>
          </div>
          <p class="upload-limit">Recomendado: videos de menos de 100MB para mejor rendimiento</p>
        </div>

        <!-- Preview after file selection -->
        <div class="upload-preview" id="upload-preview" style="display:none">
          <video class="preview-thumbnail" id="preview-video" muted></video>
          <div class="preview-overlay">
            <button class="btn btn-ghost btn-sm" id="btn-change-video" type="button">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M23 4v6h-6"/><path d="M1 20v-6h6"/><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/></svg>
              Cambiar video
            </button>
          </div>
          <div class="preview-info" id="preview-info"></div>
        </div>
      </div>

      <!-- Action button (shown after video selection) -->
      <div class="upload-actions" id="upload-actions" style="display:none">
        <button class="btn btn-primary btn-lg" id="btn-continue" type="button">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="5 3 19 12 5 21 5 3"/></svg>
          Continuar — Configurar Narración
        </button>
      </div>
    </section>
  `;

  const zone = document.getElementById('upload-zone');
  const fileInput = document.getElementById('video-input');
  const zoneContent = document.getElementById('upload-zone-content');
  const preview = document.getElementById('upload-preview');
  const previewVideo = document.getElementById('preview-video');
  const previewInfo = document.getElementById('preview-info');
  const actions = document.getElementById('upload-actions');
  const btnContinue = document.getElementById('btn-continue');
  const btnChange = document.getElementById('btn-change-video');

  let selectedFile = null;

  // Click to upload
  zone.addEventListener('click', (e) => {
    if (e.target.closest('#btn-change-video')) return;
    fileInput.click();
  });

  // Keyboard accessibility
  zone.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      fileInput.click();
    }
  });

  // File input change
  fileInput.addEventListener('change', (e) => {
    if (e.target.files.length > 0) {
      handleFile(e.target.files[0]);
    }
  });

  // Drag & Drop
  zone.addEventListener('dragover', (e) => {
    e.preventDefault();
    zone.classList.add('drag-over');
  });

  zone.addEventListener('dragleave', (e) => {
    e.preventDefault();
    zone.classList.remove('drag-over');
  });

  zone.addEventListener('drop', (e) => {
    e.preventDefault();
    zone.classList.remove('drag-over');
    const file = e.dataTransfer.files[0];
    if (file) handleFile(file);
  });

  // Change video button
  btnChange.addEventListener('click', (e) => {
    e.stopPropagation();
    fileInput.value = '';
    fileInput.click();
  });

  // Continue button
  btnContinue.addEventListener('click', () => {
    if (selectedFile) {
      onVideoSelected(selectedFile);
    }
  });

  function handleFile(file) {
    // Validate file type
    if (!file.type.startsWith('video/')) {
      showToast('Por favor selecciona un archivo de video válido.', 'error');
      return;
    }

    // Warn about large files
    if (file.size > MAX_FILE_SIZE) {
      showToast('El video es grande (>' + formatFileSize(MAX_FILE_SIZE) + '). El procesamiento puede ser lento.', 'warning');
    }

    selectedFile = file;

    // Show preview
    const videoURL = URL.createObjectURL(file);
    previewVideo.src = videoURL;
    previewVideo.load();

    previewVideo.addEventListener('loadedmetadata', () => {
      const duration = previewVideo.duration;
      previewInfo.innerHTML = `
        <div class="file-info">
          <div class="file-info-item">
            <span class="file-info-label">Archivo</span>
            <span class="file-info-value">${file.name}</span>
          </div>
          <div class="file-info-item">
            <span class="file-info-label">Tamaño</span>
            <span class="file-info-value">${formatFileSize(file.size)}</span>
          </div>
          <div class="file-info-item">
            <span class="file-info-label">Duración</span>
            <span class="file-info-value">${formatDuration(duration)}</span>
          </div>
          <div class="file-info-item">
            <span class="file-info-label">Resolución</span>
            <span class="file-info-value">${previewVideo.videoWidth}×${previewVideo.videoHeight}</span>
          </div>
        </div>
      `;

      // Seek to 25% for a good thumbnail
      previewVideo.currentTime = duration * 0.25;
    }, { once: true });

    // Switch to preview mode
    zoneContent.style.display = 'none';
    preview.style.display = 'flex';
    actions.style.display = 'flex';
    zone.classList.add('has-file');

    // Animate in
    actions.classList.add('animate-fadeInUp');
  }
}

function showToast(message, type = 'info') {
  const container = document.getElementById('toast-container');
  if (!container) return;

  const toast = document.createElement('div');
  toast.className = `toast toast-${type} animate-slideIn`;
  toast.innerHTML = `
    <span class="toast-icon">${type === 'error' ? '❌' : type === 'warning' ? '⚠️' : 'ℹ️'}</span>
    <span class="toast-message">${message}</span>
    <button class="toast-close" onclick="this.parentElement.remove()">&times;</button>
  `;
  container.appendChild(toast);

  setTimeout(() => {
    toast.classList.add('toast-exit');
    setTimeout(() => toast.remove(), 300);
  }, 5000);
}
