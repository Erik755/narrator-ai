/**
 * tts.js — Text-to-Speech Service
 *
 * Uses the Web Speech API (SpeechSynthesis) for free browser-native TTS.
 * Provides voice listing, text preview, and audio recording/export.
 *
 * Recording strategy:
 *  1. Primary: Capture desktop audio via getUserMedia({ audio: true }) while TTS plays
 *  2. Fallback: Generate a WAV blob with silence matching segment durations
 *     (user can record externally using the live TTS preview)
 *
 * @module services/tts
 */

const synth = window.speechSynthesis;

/** @type {SpeechSynthesisUtterance|null} */
let currentUtterance = null;

/** Whether we are currently speaking */
let isSpeaking = false;

/** Abort controller for cancellable operations */
let abortController = null;

// ─── Voice Management ─────────────────────────────────────────────

/**
 * Get available speech synthesis voices.
 * Voices load asynchronously in some browsers, so this returns a Promise.
 *
 * @param {string} [langFilter] - Optional BCP-47 language prefix filter (e.g. 'es', 'en')
 * @returns {Promise<SpeechSynthesisVoice[]>}
 */
export function getAvailableVoices(langFilter) {
  return new Promise((resolve) => {
    const tryGet = () => {
      let voices = synth.getVoices();
      if (langFilter) {
        const prefix = langFilter.toLowerCase();
        voices = voices.filter(v => v.lang.toLowerCase().startsWith(prefix));
      }
      return voices;
    };

    // Some browsers return voices synchronously
    const voices = tryGet();
    if (voices.length > 0) {
      resolve(voices);
      return;
    }

    // Chrome loads voices asynchronously
    let resolved = false;
    const onVoicesChanged = () => {
      if (resolved) return;
      resolved = true;
      synth.removeEventListener('voiceschanged', onVoicesChanged);
      resolve(tryGet());
    };

    synth.addEventListener('voiceschanged', onVoicesChanged);

    // Safety timeout: resolve with whatever we have after 3 seconds
    setTimeout(() => {
      if (!resolved) {
        resolved = true;
        synth.removeEventListener('voiceschanged', onVoicesChanged);
        resolve(tryGet());
      }
    }, 3000);
  });
}

/**
 * Find a voice by its name (exact or partial match).
 * @param {string} voiceName
 * @returns {Promise<SpeechSynthesisVoice|null>}
 */
export async function findVoice(voiceName) {
  if (!voiceName) return null;
  const voices = await getAvailableVoices();
  return (
    voices.find(v => v.name === voiceName) ||
    voices.find(v => v.name.toLowerCase().includes(voiceName.toLowerCase())) ||
    null
  );
}

/**
 * Get a recommended default voice for a given language.
 * Prefers high-quality voices (Google, Microsoft, Apple).
 *
 * @param {'es'|'en'} lang
 * @returns {Promise<SpeechSynthesisVoice|null>}
 */
export async function getDefaultVoice(lang = 'es') {
  const voices = await getAvailableVoices(lang);
  if (voices.length === 0) return null;

  // Prefer premium voices
  const premium = voices.find(v =>
    /google|microsoft|apple|neural|enhanced|premium/i.test(v.name)
  );
  if (premium) return premium;

  // Prefer local voices over remote
  const local = voices.find(v => v.localService);
  if (local) return local;

  return voices[0];
}

// ─── Preview / Playback ───────────────────────────────────────────

/**
 * Speak a text string aloud using the browser's TTS engine.
 *
 * @param {string} text - Text to speak
 * @param {object} [options]
 * @param {string} [options.voiceName] - Voice name to use
 * @param {SpeechSynthesisVoice} [options.voice] - Direct voice object
 * @param {number} [options.rate=1] - Speech rate (0.1–10)
 * @param {number} [options.pitch=1] - Pitch (0–2)
 * @param {number} [options.volume=1] - Volume (0–1)
 * @param {function(): void} [options.onEnd] - Called when speech finishes
 * @param {function(SpeechSynthesisErrorEvent): void} [options.onError] - Called on error
 * @returns {Promise<void>} Resolves when speech ends
 */
export function previewScript(text, options = {}) {
  return new Promise(async (resolve, reject) => {
    if (!synth) {
      reject(new Error('Tu navegador no soporta síntesis de voz (Web Speech API).'));
      return;
    }

    // Stop any current speech
    stopPreview();

    if (!text || text.trim().length === 0) {
      resolve();
      return;
    }

    const utterance = new SpeechSynthesisUtterance(text);

    // Set voice
    if (options.voice) {
      utterance.voice = options.voice;
    } else if (options.voiceName) {
      const voice = await findVoice(options.voiceName);
      if (voice) utterance.voice = voice;
    }

    utterance.rate = clamp(options.rate ?? 1, 0.1, 10);
    utterance.pitch = clamp(options.pitch ?? 1, 0, 2);
    utterance.volume = clamp(options.volume ?? 1, 0, 1);

    utterance.onend = () => {
      isSpeaking = false;
      currentUtterance = null;
      options.onEnd?.();
      resolve();
    };

    utterance.onerror = (event) => {
      isSpeaking = false;
      currentUtterance = null;
      if (event.error === 'canceled' || event.error === 'interrupted') {
        resolve(); // User-initiated stop is not an error
        return;
      }
      const err = new Error(`Error de voz: ${event.error}`);
      options.onError?.(event);
      reject(err);
    };

    currentUtterance = utterance;
    isSpeaking = true;

    // Chrome has a bug where long utterances stop after ~15 seconds.
    // Workaround: resume every 10 seconds.
    const resumeInterval = setInterval(() => {
      if (!isSpeaking) {
        clearInterval(resumeInterval);
        return;
      }
      synth.pause();
      synth.resume();
    }, 10000);

    utterance.addEventListener('end', () => clearInterval(resumeInterval), { once: true });
    utterance.addEventListener('error', () => clearInterval(resumeInterval), { once: true });

    synth.speak(utterance);
  });
}

/**
 * Preview multiple segments sequentially.
 *
 * @param {Array<{start: number, end: number, text: string}>} segments
 * @param {object} [options] - Same options as previewScript
 * @param {function(number, number): void} [options.onSegmentStart] - (index, total)
 * @returns {Promise<void>}
 */
export async function previewSegments(segments, options = {}) {
  if (!segments || segments.length === 0) return;

  abortController = new AbortController();
  const total = segments.length;

  for (let i = 0; i < total; i++) {
    if (abortController.signal.aborted) break;

    options.onSegmentStart?.(i, total);
    await previewScript(segments[i].text, options);

    // Add a small pause between segments
    if (i < total - 1 && !abortController.signal.aborted) {
      await sleep(300);
    }
  }

  abortController = null;
}

/**
 * Stop any currently playing speech.
 */
export function stopPreview() {
  if (abortController) {
    abortController.abort();
    abortController = null;
  }
  synth.cancel();
  isSpeaking = false;
  currentUtterance = null;
}

/**
 * Pause the current speech.
 */
export function pausePreview() {
  synth.pause();
}

/**
 * Resume paused speech.
 */
export function resumePreview() {
  synth.resume();
}

/**
 * Check if TTS is currently speaking.
 * @returns {boolean}
 */
export function getIsSpeaking() {
  return synth.speaking || isSpeaking;
}

// ─── Audio Recording & Export ─────────────────────────────────────

/**
 * Record TTS audio output by capturing system/desktop audio while TTS plays.
 * Falls back to generating a silent WAV if audio capture is unavailable.
 *
 * @param {Array<{start: number, end: number, text: string}>} segments
 * @param {object} [options]
 * @param {string} [options.voiceName]
 * @param {SpeechSynthesisVoice} [options.voice]
 * @param {number} [options.rate=1]
 * @param {number} [options.pitch=1]
 * @param {function(string): void} [options.onStatus] - Status callback
 * @returns {Promise<{blob: Blob, duration: number, method: 'capture'|'generated'}>}
 */
export async function generateAudio(segments, options = {}) {
  if (!segments || segments.length === 0) {
    throw new Error('No hay segmentos de texto para generar audio.');
  }

  // Attempt 1: Try capturing audio via getUserMedia (displayMedia for desktop audio)
  try {
    options.onStatus?.('Intentando capturar audio del sistema...');
    const result = await captureAudioFromTTS(segments, options);
    return { ...result, method: 'capture' };
  } catch {
    // getUserMedia not available or denied — fall through to generated audio
  }

  // Attempt 2: Generate a synthetic audio WAV with timed tones for each segment
  options.onStatus?.('Generando audio de referencia...');
  const result = await generateSyntheticAudio(segments, options);
  return { ...result, method: 'generated' };
}

/**
 * Capture real TTS audio using getDisplayMedia (screen sharing with audio).
 * The user will be prompted to share a browser tab with audio enabled.
 *
 * @param {Array<{start: number, end: number, text: string}>} segments
 * @param {object} options
 * @returns {Promise<{blob: Blob, duration: number}>}
 */
async function captureAudioFromTTS(segments, options) {
  // Request display media with audio (to capture TTS output)
  const stream = await navigator.mediaDevices.getDisplayMedia({
    video: false,
    audio: true,
  });

  const audioTracks = stream.getAudioTracks();
  if (audioTracks.length === 0) {
    stream.getTracks().forEach(t => t.stop());
    throw new Error('No audio track available');
  }

  // Only keep audio tracks
  const audioStream = new MediaStream(audioTracks);

  // Stop any video tracks
  stream.getVideoTracks().forEach(t => t.stop());

  const mediaRecorder = new MediaRecorder(audioStream, {
    mimeType: getSupportedMimeType(),
  });

  const chunks = [];
  mediaRecorder.ondataavailable = (e) => {
    if (e.data.size > 0) chunks.push(e.data);
  };

  return new Promise((resolve, reject) => {
    mediaRecorder.onstop = () => {
      audioStream.getTracks().forEach(t => t.stop());
      const mimeType = mediaRecorder.mimeType || 'audio/webm';
      const blob = new Blob(chunks, { type: mimeType });
      const lastSeg = segments[segments.length - 1];
      resolve({ blob, duration: lastSeg.end });
    };

    mediaRecorder.onerror = (e) => {
      audioStream.getTracks().forEach(t => t.stop());
      reject(e.error || new Error('Recording failed'));
    };

    mediaRecorder.start();

    // Now speak all segments
    previewSegments(segments, {
      ...options,
      onEnd: undefined,
      onError: undefined,
    })
      .then(() => sleep(500))
      .then(() => mediaRecorder.stop())
      .catch(() => mediaRecorder.stop());
  });
}

/**
 * Generate a synthetic WAV audio file with click tones at segment boundaries.
 * This serves as a timing reference for external audio recording.
 *
 * @param {Array<{start: number, end: number, text: string}>} segments
 * @param {object} options
 * @returns {Promise<{blob: Blob, duration: number}>}
 */
async function generateSyntheticAudio(segments, options) {
  const lastSegment = segments[segments.length - 1];
  const totalDuration = lastSegment.end + 0.5; // small padding
  const sampleRate = 44100;
  const numSamples = Math.ceil(totalDuration * sampleRate);

  const audioCtx = new (window.AudioContext || window.webkitAudioContext)({ sampleRate });
  const buffer = audioCtx.createBuffer(1, numSamples, sampleRate);
  const channelData = buffer.getChannelData(0);

  // Generate a soft click/beep at the start of each segment
  for (const seg of segments) {
    const beepStart = Math.floor(seg.start * sampleRate);
    const beepDuration = Math.min(Math.floor(0.1 * sampleRate), numSamples - beepStart);
    const frequency = 880; // A5 note

    for (let i = 0; i < beepDuration; i++) {
      const t = i / sampleRate;
      const envelope = Math.exp(-t * 30); // Quick fade out
      const idx = beepStart + i;
      if (idx < numSamples) {
        channelData[idx] = Math.sin(2 * Math.PI * frequency * t) * 0.3 * envelope;
      }
    }
  }

  // Encode as WAV
  const wavBlob = audioBufferToWav(buffer);
  await audioCtx.close();

  return { blob: wavBlob, duration: totalDuration };
}

/**
 * Convert an AudioBuffer to a WAV Blob.
 * @param {AudioBuffer} audioBuffer
 * @returns {Blob}
 */
function audioBufferToWav(audioBuffer) {
  const numChannels = audioBuffer.numberOfChannels;
  const sampleRate = audioBuffer.sampleRate;
  const format = 1; // PCM
  const bitsPerSample = 16;

  // Interleave channels
  let interleaved;
  if (numChannels === 1) {
    interleaved = audioBuffer.getChannelData(0);
  } else {
    const length = audioBuffer.length * numChannels;
    interleaved = new Float32Array(length);
    for (let ch = 0; ch < numChannels; ch++) {
      const channelData = audioBuffer.getChannelData(ch);
      for (let i = 0; i < audioBuffer.length; i++) {
        interleaved[i * numChannels + ch] = channelData[i];
      }
    }
  }

  const dataLength = interleaved.length * (bitsPerSample / 8);
  const headerLength = 44;
  const totalLength = headerLength + dataLength;
  const arrayBuffer = new ArrayBuffer(totalLength);
  const view = new DataView(arrayBuffer);

  // WAV header
  writeString(view, 0, 'RIFF');
  view.setUint32(4, totalLength - 8, true);
  writeString(view, 8, 'WAVE');
  writeString(view, 12, 'fmt ');
  view.setUint32(16, 16, true);                          // chunk size
  view.setUint16(20, format, true);                       // PCM
  view.setUint16(22, numChannels, true);
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * numChannels * (bitsPerSample / 8), true); // byte rate
  view.setUint16(32, numChannels * (bitsPerSample / 8), true);             // block align
  view.setUint16(34, bitsPerSample, true);
  writeString(view, 36, 'data');
  view.setUint32(40, dataLength, true);

  // Convert float32 samples to int16
  let offset = 44;
  for (let i = 0; i < interleaved.length; i++) {
    const sample = Math.max(-1, Math.min(1, interleaved[i]));
    const int16 = sample < 0 ? sample * 0x8000 : sample * 0x7FFF;
    view.setInt16(offset, int16, true);
    offset += 2;
  }

  return new Blob([arrayBuffer], { type: 'audio/wav' });
}

/**
 * Write an ASCII string into a DataView.
 * @param {DataView} view
 * @param {number} offset
 * @param {string} str
 */
function writeString(view, offset, str) {
  for (let i = 0; i < str.length; i++) {
    view.setUint8(offset + i, str.charCodeAt(i));
  }
}

// ─── Utilities ────────────────────────────────────────────────────

/**
 * Get a supported MediaRecorder MIME type.
 * @returns {string}
 */
function getSupportedMimeType() {
  const types = [
    'audio/webm;codecs=opus',
    'audio/webm',
    'audio/ogg;codecs=opus',
    'audio/ogg',
    'audio/mp4',
  ];
  for (const type of types) {
    if (MediaRecorder.isTypeSupported(type)) return type;
  }
  return '';
}

/**
 * Clamp a number between min and max.
 * @param {number} val
 * @param {number} min
 * @param {number} max
 * @returns {number}
 */
function clamp(val, min, max) {
  return Math.min(max, Math.max(min, val));
}

/**
 * Sleep for a given number of milliseconds.
 * @param {number} ms
 * @returns {Promise<void>}
 */
function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

/**
 * Check if the Web Speech API is supported in this browser.
 * @returns {boolean}
 */
export function isTTSSupported() {
  return 'speechSynthesis' in window && 'SpeechSynthesisUtterance' in window;
}

/**
 * Estimate the speaking duration of a text string.
 * Average speaking rate is ~150 words per minute at rate=1.
 *
 * @param {string} text
 * @param {number} [rate=1]
 * @returns {number} Estimated duration in seconds
 */
export function estimateDuration(text, rate = 1) {
  if (!text) return 0;
  const words = text.trim().split(/\s+/).length;
  const wpm = 150 * rate;
  return Math.max(1, (words / wpm) * 60);
}
