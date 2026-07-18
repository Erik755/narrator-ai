/**
 * App Controller - Main application state machine and navigation
 */

import { renderSetup } from './components/setup.js';
import { renderUpload } from './components/upload.js';
import { renderConfigPanel } from './components/config-panel.js';
import { renderPipeline, updatePipelineStep, updatePipelineProgress, completePipeline, failPipeline } from './components/pipeline.js';
import { renderEditor } from './components/editor.js';
import { renderPreview } from './components/preview.js';
import { renderExport } from './components/export.js';
import { renderHistory } from './components/history.js';
import { getApiKey, saveProject } from './services/storage.js';
import { initGemini, isInitialized, analyzeVideo } from './services/gemini.js';
import { getAvailableVoices, generateAudio, previewScript, stopPreview } from './services/tts.js';
import { initFFmpeg, exportFinalVideo } from './services/ffmpeg.js';
import { generateSRT } from './utils/srt.js';
import { generateId } from './utils/helpers.js';

// App State
const state = {
  currentView: 'loading',
  videoFile: null,
  config: null,
  scriptData: null,
  audioBlob: null,
  videoBlob: null,
  videoBlobNoSubs: null,
  ffmpegReady: false,
};

/**
 * Initialize the application
 */
export function initApp() {
  const main = document.getElementById('app-main');
  
  // Check for API key
  const apiKey = getApiKey();
  if (apiKey) {
    initGemini(apiKey);
    navigateTo('upload');
  } else {
    navigateTo('setup');
  }

  // Set up navigation event listeners
  window.addEventListener('navigate', (e) => {
    const { view, data } = e.detail;
    navigateTo(view, data);
  });

  // Generate media event
  window.addEventListener('generate-media', (e) => {
    const { scriptData } = e.detail;
    state.scriptData = scriptData;
    processMedia();
  });

  // Header button listeners
  document.getElementById('btn-history')?.addEventListener('click', () => {
    navigateTo('history');
  });

  document.getElementById('btn-settings')?.addEventListener('click', () => {
    navigateTo('setup');
  });

  document.getElementById('logo-home')?.addEventListener('click', () => {
    navigateTo('upload');
  });

  // Pre-load FFmpeg in background
  preloadFFmpeg();
}

/**
 * Navigate to a view
 */
function navigateTo(view, data) {
  const main = document.getElementById('app-main');
  state.currentView = view;

  // Clear main content
  main.innerHTML = '';

  switch (view) {
    case 'setup':
      renderSetup(main, () => navigateTo('upload'));
      break;

    case 'upload':
      renderUpload(main, (file) => {
        state.videoFile = file;
        navigateTo('config');
      });
      break;

    case 'config':
      if (!state.videoFile) {
        navigateTo('upload');
        return;
      }
      renderConfigPanel(main, state.videoFile, (config) => {
        state.config = config;
        startProcessing();
      });
      break;

    case 'pipeline':
      renderPipeline(main);
      break;

    case 'editor':
      if (!state.scriptData) {
        navigateTo('upload');
        return;
      }
      renderEditor(main, state.scriptData, () => {
        // Regenerate: go back to pipeline
        startProcessing();
      });
      break;

    case 'preview':
      renderPreview(main, {
        videoBlob: state.videoBlob,
        videoBlobNoSubs: state.videoBlobNoSubs,
        audioBlob: state.audioBlob,
        scriptData: state.scriptData,
      });
      // Render export panel inside preview
      const exportSection = document.getElementById('export-section');
      if (exportSection) {
        renderExport(exportSection, {
          videoBlob: state.videoBlob,
          videoBlobNoSubs: state.videoBlobNoSubs,
          audioBlob: state.audioBlob,
          scriptData: state.scriptData,
        });
      }
      break;

    case 'history':
      renderHistory(main);
      break;

    default:
      navigateTo('upload');
  }

  // Scroll to top
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

/**
 * Start the AI processing pipeline
 */
async function startProcessing() {
  navigateTo('pipeline');

  try {
    // Step 1: Upload & Analyze video
    updatePipelineStep('upload', 'active', 'Preparando video...');
    updatePipelineProgress(5);

    updatePipelineStep('upload', 'done', 'Video listo');
    updatePipelineStep('analyze', 'active', 'Analizando contenido con IA...');
    updatePipelineProgress(15);

    // Call Gemini API
    const scriptData = await analyzeVideo(
      state.videoFile,
      state.config.tone,
      state.config.language,
      (step, msg) => {
        if (step === 'uploading') updatePipelineProgress(20);
        if (step === 'analyzing') updatePipelineProgress(35);
      }
    );

    state.scriptData = scriptData;
    updatePipelineStep('analyze', 'done', 'Análisis completo');
    updatePipelineProgress(50);

    // Show editor for script review
    completePipeline();
    
    // Brief pause to show completion, then navigate to editor
    await sleep(800);
    navigateTo('editor');

  } catch (error) {
    console.error('Processing error:', error);
    updatePipelineStep(
      state.currentStep || 'analyze',
      'error',
      `Error: ${error.message}`
    );
    failPipeline(error.message);
    showToast(`Error: ${error.message}`, 'error');
  }
}

/**
 * Process media (audio + video) after script is approved
 */
async function processMedia() {
  navigateTo('pipeline');

  try {
    // Step 1 & 2 already done
    updatePipelineStep('upload', 'done', 'Video listo');
    updatePipelineStep('analyze', 'done', 'Guion generado');
    updatePipelineProgress(50);

    // Step 3: Generate TTS audio
    updatePipelineStep('tts', 'active', 'Generando narración con voz...');
    updatePipelineProgress(55);

    const voices = await getAvailableVoices();
    // Pick best voice for language
    const langCode = state.config.language === 'es' ? 'es' : 'en';
    const preferredVoice = voices.find(v => 
      v.lang.startsWith(langCode) && (v.name.includes('Microsoft') || v.name.includes('Google'))
    ) || voices.find(v => v.lang.startsWith(langCode)) || voices[0];

    state.audioBlob = await generateAudio(
      state.scriptData.segments,
      preferredVoice?.name,
      0.9
    );

    updatePipelineStep('tts', 'done', 'Audio generado');
    updatePipelineProgress(70);

    // Step 4: Process video with FFmpeg
    updatePipelineStep('video', 'active', 'Procesando video...');
    updatePipelineProgress(75);

    if (!state.ffmpegReady) {
      updatePipelineStep('video', 'active', 'Cargando procesador de video...');
      await initFFmpeg((progress) => {
        updatePipelineProgress(75 + progress * 5);
      });
      state.ffmpegReady = true;
    }

    updatePipelineStep('video', 'active', 'Creando video vertical con subtítulos...');
    const srtContent = generateSRT(state.scriptData.segments);
    const aspectRatio = state.config.aspectRatio || '9:16';

    // Generate video WITH subtitles
    try {
      const result = await exportFinalVideo(
        state.videoFile,
        state.audioBlob,
        srtContent,
        {
          aspectRatio,
          includeSubtitles: true,
          onProgress: (p) => updatePipelineProgress(80 + p * 10),
        }
      );
      state.videoBlob = result;
    } catch (ffmpegError) {
      console.warn('FFmpeg video processing failed, providing audio-only:', ffmpegError);
      state.videoBlob = null;
    }

    // Generate video WITHOUT subtitles
    try {
      const resultNoSubs = await exportFinalVideo(
        state.videoFile,
        state.audioBlob,
        null,
        {
          aspectRatio,
          includeSubtitles: false,
          onProgress: (p) => updatePipelineProgress(90 + p * 10),
        }
      );
      state.videoBlobNoSubs = resultNoSubs;
    } catch (ffmpegError) {
      console.warn('FFmpeg video (no subs) failed:', ffmpegError);
      state.videoBlobNoSubs = null;
    }

    updatePipelineStep('video', 'done', 'Video procesado');
    updatePipelineProgress(100);
    completePipeline();

    // Save to history
    saveProject({
      id: generateId(),
      title: state.scriptData.title,
      script: state.scriptData.script,
      tone: state.config.tone,
      language: state.config.language,
      platform: state.config.platform,
      hashtags: state.scriptData.hashtags,
      caption: state.scriptData.caption,
      createdAt: new Date().toISOString(),
    });

    // Navigate to preview
    await sleep(1000);
    navigateTo('preview');

  } catch (error) {
    console.error('Media processing error:', error);
    failPipeline(error.message);
    showToast(`Error en procesamiento: ${error.message}`, 'error');

    // Even if video failed, if we have script+audio, show preview
    if (state.scriptData && state.audioBlob) {
      await sleep(2000);
      navigateTo('preview');
    }
  }
}

/**
 * Pre-load FFmpeg.wasm in background
 */
async function preloadFFmpeg() {
  try {
    await initFFmpeg();
    state.ffmpegReady = true;
    console.log('FFmpeg pre-loaded successfully');
  } catch (e) {
    console.warn('FFmpeg pre-load failed, will retry later:', e.message);
  }
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function showToast(message, type = 'info') {
  const container = document.getElementById('toast-container');
  if (!container) return;

  const toast = document.createElement('div');
  toast.className = `toast toast-${type} animate-slideIn`;
  toast.innerHTML = `
    <span class="toast-icon">${type === 'error' ? '❌' : type === 'warning' ? '⚠️' : type === 'success' ? '✅' : 'ℹ️'}</span>
    <span class="toast-message">${message}</span>
    <button class="toast-close" onclick="this.parentElement.remove()">&times;</button>
  `;
  container.appendChild(toast);

  setTimeout(() => {
    toast.classList.add('toast-exit');
    setTimeout(() => toast.remove(), 300);
  }, 6000);
}
