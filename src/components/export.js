/**
 * Export Component - Download panel with multiple format options
 */

import { downloadBlob } from '../utils/helpers.js';
import { generateSRT } from '../utils/srt.js';

export function renderExport(container, { videoBlob, videoBlobNoSubs, audioBlob, scriptData }) {
  const { title, script, segments, hashtags, caption } = scriptData;
  const safeTitle = (title || 'NarradorAI').replace(/[^a-zA-Z0-9áéíóúñ ]/g, '').trim().replace(/\s+/g, '_');
  const dateStr = new Date().toISOString().slice(0, 10);

  const exports = [
    {
      id: 'script',
      icon: '📝',
      title: 'Guion',
      desc: 'Texto completo de la narración',
      format: '.txt',
      available: !!script,
      color: 'var(--color-primary)',
    },
    {
      id: 'subtitles',
      icon: '📄',
      title: 'Subtítulos',
      desc: 'Archivo de subtítulos',
      format: '.srt',
      available: segments && segments.length > 0,
      color: 'var(--color-accent)',
    },
    {
      id: 'audio',
      icon: '🎵',
      title: 'Audio',
      desc: 'Narración de voz',
      format: '.wav',
      available: !!audioBlob,
      color: 'var(--color-success)',
    },
    {
      id: 'video-subs',
      icon: '🎬',
      title: 'Video + Subs',
      desc: 'Video vertical con subtítulos',
      format: '.mp4',
      available: !!videoBlob,
      color: 'var(--color-warning)',
    },
    {
      id: 'video-nosubs',
      icon: '🎥',
      title: 'Video sin Subs',
      desc: 'Video vertical sin subtítulos',
      format: '.mp4',
      available: !!videoBlobNoSubs,
      color: 'var(--color-pink)',
    },
  ];

  container.innerHTML = `
    <div class="export-panel animate-fadeInUp">
      <h3 class="export-title">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
        Descargar tu Contenido
      </h3>
      <div class="export-grid">
        ${exports.map(exp => `
          <div class="export-card glass-panel ${!exp.available ? 'disabled' : ''}" id="export-${exp.id}">
            <div class="export-card-header">
              <span class="export-icon">${exp.icon}</span>
              <span class="export-format badge" style="--badge-color: ${exp.color}">${exp.format}</span>
            </div>
            <h4 class="export-card-title">${exp.title}</h4>
            <p class="export-card-desc">${exp.desc}</p>
            <button class="btn btn-primary btn-sm export-btn" data-export="${exp.id}" type="button" ${!exp.available ? 'disabled' : ''}>
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
              ${exp.available ? 'Descargar' : 'No disponible'}
            </button>
          </div>
        `).join('')}
      </div>
    </div>
  `;

  // Download handlers
  container.addEventListener('click', (e) => {
    const btn = e.target.closest('[data-export]');
    if (!btn || btn.disabled) return;

    const exportId = btn.dataset.export;
    const filename = `NarradorAI_${safeTitle}_${dateStr}`;

    switch (exportId) {
      case 'script': {
        let content = `${title}\n${'='.repeat(title.length)}\n\n${script}`;
        if (hashtags?.length) content += `\n\nHashtags: ${hashtags.join(' ')}`;
        if (caption) content += `\nCaption: ${caption}`;
        const blob = new Blob([content], { type: 'text/plain;charset=utf-8' });
        downloadBlob(blob, `${filename}.txt`);
        showDownloadFeedback(btn);
        break;
      }
      case 'subtitles': {
        const srtContent = generateSRT(segments);
        const blob = new Blob([srtContent], { type: 'text/plain;charset=utf-8' });
        downloadBlob(blob, `${filename}.srt`);
        showDownloadFeedback(btn);
        break;
      }
      case 'audio': {
        if (audioBlob) {
          downloadBlob(audioBlob, `${filename}.wav`);
          showDownloadFeedback(btn);
        }
        break;
      }
      case 'video-subs': {
        if (videoBlob) {
          downloadBlob(videoBlob, `${filename}_subs.mp4`);
          showDownloadFeedback(btn);
        }
        break;
      }
      case 'video-nosubs': {
        if (videoBlobNoSubs) {
          downloadBlob(videoBlobNoSubs, `${filename}.mp4`);
          showDownloadFeedback(btn);
        }
        break;
      }
    }
  });
}

function showDownloadFeedback(btn) {
  const original = btn.innerHTML;
  btn.innerHTML = '✅ Descargado';
  btn.disabled = true;
  setTimeout(() => {
    btn.innerHTML = original;
    btn.disabled = false;
  }, 2000);
}
