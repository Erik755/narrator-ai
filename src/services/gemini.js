/**
 * gemini.js — Gemini API Integration Service
 * 
 * Provides video analysis and narration script generation via Google's Gemini API.
 * Uses the @google/genai package with the Gemini 2.5 Flash model.
 * 
 * @module services/gemini
 */

import { GoogleGenAI } from '@google/genai';
import { getAnalysisPrompt } from '../utils/prompts.js';

/** @type {GoogleGenAI|null} */
let genAI = null;

/** Maximum file size for inline data (20 MB) */
const MAX_INLINE_SIZE = 20 * 1024 * 1024;

/** Supported video MIME types */
const SUPPORTED_VIDEO_TYPES = [
  'video/mp4',
  'video/webm',
  'video/quicktime',
  'video/x-msvideo',
  'video/x-matroska',
  'video/mpeg',
];

/**
 * Initialize the Gemini client with an API key.
 * @param {string} apiKey - Google AI API key
 * @throws {Error} If apiKey is empty or invalid format
 */
export function initGemini(apiKey) {
  if (!apiKey || typeof apiKey !== 'string' || apiKey.trim().length === 0) {
    throw new Error('API key is required and must be a non-empty string.');
  }
  genAI = new GoogleGenAI({ apiKey: apiKey.trim() });
}

/**
 * Check if the Gemini client has been initialized.
 * @returns {boolean}
 */
export function isInitialized() {
  return genAI !== null;
}

/**
 * Destroy the current Gemini client instance.
 */
export function destroyGemini() {
  genAI = null;
}

/**
 * Analyze a video file and generate a narration script.
 *
 * @param {File} videoFile - Video file from an <input> element
 * @param {'profesional'|'gracioso'|'tierno'|'motivacional'|'informativo'|'sarcastico'} tone
 * @param {'es'|'en'} language
 * @param {function(string, string): void} [onProgress] - Progress callback (stage, message)
 * @returns {Promise<{title: string, script: string, segments: Array<{start: number, end: number, text: string}>, hashtags: string[], caption: string}>}
 */
export async function analyzeVideo(videoFile, tone = 'profesional', language = 'es', onProgress) {
  if (!genAI) {
    throw new Error('Gemini no está inicializado. Por favor, configura tu API Key.');
  }

  // Validate the file
  validateVideoFile(videoFile);

  onProgress?.('uploading', language === 'es' ? 'Preparando video...' : 'Preparing video...');

  // Convert file to inline data (base64)
  const fileData = await fileToGenerativePart(videoFile);

  onProgress?.('analyzing', language === 'es' ? 'Analizando contenido del video con IA...' : 'Analyzing video content with AI...');

  const prompt = getAnalysisPrompt(tone, language);

  let response;
  try {
    response = await genAI.models.generateContent({
      model: 'gemini-2.5-flash',
      contents: [
        {
          role: 'user',
          parts: [
            { inlineData: fileData },
            { text: prompt },
          ],
        },
      ],
      config: {
        responseMimeType: 'application/json',
        temperature: 0.8,
        topP: 0.95,
        maxOutputTokens: 4096,
      },
    });
  } catch (err) {
    throw wrapApiError(err, language);
  }

  onProgress?.('parsing', language === 'es' ? 'Procesando respuesta...' : 'Processing response...');

  const parsed = parseAIResponse(response, language);

  // Validate and sanitize the parsed result
  const result = sanitizeResult(parsed, language);

  onProgress?.('done', language === 'es' ? '¡Análisis completo!' : 'Analysis complete!');

  return result;
}

/**
 * Validate that the provided file is a supported video.
 * @param {File} file
 * @throws {Error} On invalid file
 */
function validateVideoFile(file) {
  if (!file || !(file instanceof File)) {
    throw new Error('Se requiere un archivo de video válido.');
  }
  if (!SUPPORTED_VIDEO_TYPES.includes(file.type) && !file.name.match(/\.(mp4|webm|mov|avi|mkv|mpeg)$/i)) {
    throw new Error(
      `Formato de video no soportado: "${file.type || 'desconocido'}". ` +
      `Formatos soportados: MP4, WebM, MOV, AVI, MKV, MPEG.`
    );
  }
  if (file.size > MAX_INLINE_SIZE) {
    const sizeMB = (file.size / (1024 * 1024)).toFixed(1);
    throw new Error(
      `El video es demasiado grande (${sizeMB} MB). El límite máximo es 20 MB. ` +
      `Intenta comprimir el video o recortarlo.`
    );
  }
  if (file.size === 0) {
    throw new Error('El archivo de video está vacío.');
  }
}

/**
 * Convert a File object to a Gemini-compatible inline data part.
 * @param {File} file
 * @returns {Promise<{mimeType: string, data: string}>}
 */
function fileToGenerativePart(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();

    reader.onload = () => {
      const base64 = /** @type {string} */ (reader.result).split(',')[1];
      if (!base64 || base64.length === 0) {
        reject(new Error('No se pudo leer el contenido del archivo de video.'));
        return;
      }
      resolve({
        mimeType: file.type || 'video/mp4',
        data: base64,
      });
    };

    reader.onerror = () => {
      reject(new Error(`Error al leer el archivo: ${reader.error?.message || 'desconocido'}`));
    };

    reader.onabort = () => {
      reject(new Error('La lectura del archivo fue cancelada.'));
    };

    reader.readAsDataURL(file);
  });
}

/**
 * Parse the AI response text into a structured JSON object.
 * Handles raw JSON, markdown-wrapped JSON, and partial responses.
 *
 * @param {object} response - Gemini API response
 * @param {string} language
 * @returns {object}
 */
function parseAIResponse(response, language) {
  let text;
  try {
    text = response.text;
  } catch {
    throw new Error(
      language === 'es'
        ? 'La IA no generó una respuesta. Intenta de nuevo.'
        : 'The AI did not generate a response. Please try again.'
    );
  }

  if (!text || text.trim().length === 0) {
    throw new Error(
      language === 'es'
        ? 'La respuesta de la IA está vacía. Intenta con otro video o tono.'
        : 'The AI response is empty. Try a different video or tone.'
    );
  }

  // Attempt 1: Direct JSON parse
  try {
    return JSON.parse(text);
  } catch {
    // Continue to next attempt
  }

  // Attempt 2: Extract from markdown code block ```json ... ```
  const jsonBlockMatch = text.match(/```(?:json)?\s*\n?([\s\S]*?)\n?\s*```/);
  if (jsonBlockMatch) {
    try {
      return JSON.parse(jsonBlockMatch[1].trim());
    } catch {
      // Continue
    }
  }

  // Attempt 3: Find first { ... } block
  const braceStart = text.indexOf('{');
  const braceEnd = text.lastIndexOf('}');
  if (braceStart !== -1 && braceEnd > braceStart) {
    try {
      return JSON.parse(text.slice(braceStart, braceEnd + 1));
    } catch {
      // Continue
    }
  }

  // Attempt 4: Try to fix common JSON issues (trailing commas, single quotes)
  if (braceStart !== -1 && braceEnd > braceStart) {
    let cleaned = text.slice(braceStart, braceEnd + 1);
    cleaned = cleaned.replace(/,\s*([}\]])/g, '$1'); // trailing commas
    cleaned = cleaned.replace(/'/g, '"');              // single to double quotes
    try {
      return JSON.parse(cleaned);
    } catch {
      // Give up
    }
  }

  throw new Error(
    language === 'es'
      ? 'No se pudo interpretar la respuesta de la IA. Intenta de nuevo.'
      : 'Could not parse the AI response. Please try again.'
  );
}

/**
 * Validate and sanitize the parsed result to ensure all required fields exist.
 * @param {object} raw
 * @param {string} language
 * @returns {{title: string, script: string, segments: Array<{start: number, end: number, text: string}>, hashtags: string[], caption: string}}
 */
function sanitizeResult(raw, language) {
  const result = {
    title: typeof raw.title === 'string' && raw.title.trim()
      ? raw.title.trim()
      : (language === 'es' ? 'Video sin título' : 'Untitled Video'),

    script: typeof raw.script === 'string' && raw.script.trim()
      ? raw.script.trim()
      : '',

    segments: [],

    hashtags: [],

    caption: typeof raw.caption === 'string' && raw.caption.trim()
      ? raw.caption.trim().slice(0, 200)
      : '',
  };

  // Sanitize segments
  if (Array.isArray(raw.segments) && raw.segments.length > 0) {
    result.segments = raw.segments
      .filter(seg => seg && typeof seg.text === 'string' && seg.text.trim())
      .map(seg => ({
        start: typeof seg.start === 'number' ? Math.max(0, seg.start) : 0,
        end: typeof seg.end === 'number' ? Math.max(0, seg.end) : 0,
        text: seg.text.trim(),
      }))
      .filter(seg => seg.end > seg.start)
      .sort((a, b) => a.start - b.start);
  }

  // If no segments were parsed but we have a script, create a single segment
  if (result.segments.length === 0 && result.script) {
    result.segments = [{ start: 0, end: 10, text: result.script }];
  }

  // Sanitize hashtags
  if (Array.isArray(raw.hashtags)) {
    result.hashtags = raw.hashtags
      .filter(h => typeof h === 'string' && h.trim())
      .map(h => {
        const tag = h.trim();
        return tag.startsWith('#') ? tag : `#${tag}`;
      })
      .slice(0, 10);
  }

  // Build script from segments if script is empty
  if (!result.script && result.segments.length > 0) {
    result.script = result.segments.map(s => s.text).join(' ');
  }

  return result;
}

/**
 * Wrap a raw Gemini API error into a user-friendly error.
 * @param {Error} err
 * @param {string} language
 * @returns {Error}
 */
function wrapApiError(err, language) {
  const msg = err.message || '';
  const isEs = language === 'es';

  if (msg.includes('API_KEY_INVALID') || msg.includes('PERMISSION_DENIED') || msg.includes('401')) {
    return new Error(
      isEs
        ? 'API Key inválida o sin permisos. Verifica tu clave en Google AI Studio.'
        : 'Invalid API Key or insufficient permissions. Check your key in Google AI Studio.'
    );
  }

  if (msg.includes('RESOURCE_EXHAUSTED') || msg.includes('429')) {
    return new Error(
      isEs
        ? 'Se ha excedido el límite de solicitudes. Espera un momento e intenta de nuevo.'
        : 'Rate limit exceeded. Please wait a moment and try again.'
    );
  }

  if (msg.includes('SAFETY') || msg.includes('blocked')) {
    return new Error(
      isEs
        ? 'El contenido del video fue bloqueado por los filtros de seguridad de la IA.'
        : 'The video content was blocked by AI safety filters.'
    );
  }

  if (msg.includes('DEADLINE_EXCEEDED') || msg.includes('timeout') || msg.includes('504')) {
    return new Error(
      isEs
        ? 'El análisis tardó demasiado. Intenta con un video más corto.'
        : 'Analysis took too long. Try with a shorter video.'
    );
  }

  if (msg.includes('NetworkError') || msg.includes('Failed to fetch') || msg.includes('ERR_NETWORK')) {
    return new Error(
      isEs
        ? 'Error de conexión. Verifica tu conexión a internet e intenta de nuevo.'
        : 'Connection error. Check your internet connection and try again.'
    );
  }

  return new Error(
    isEs
      ? `Error al analizar el video: ${msg}`
      : `Error analyzing video: ${msg}`
  );
}

/**
 * Validate an API key by making a minimal test request.
 * @param {string} apiKey
 * @returns {Promise<boolean>} True if the key is valid
 */
export async function validateApiKey(apiKey) {
  if (!apiKey || typeof apiKey !== 'string' || apiKey.trim().length < 10) {
    return false;
  }

  try {
    const testAI = new GoogleGenAI({ apiKey: apiKey.trim() });
    const response = await testAI.models.generateContent({
      model: 'gemini-2.5-flash',
      contents: [{ role: 'user', parts: [{ text: 'Respond with exactly: OK' }] }],
      config: {
        maxOutputTokens: 10,
        temperature: 0,
      },
    });
    const text = response.text;
    return typeof text === 'string' && text.trim().length > 0;
  } catch {
    return false;
  }
}

/**
 * Re-generate the narration for an existing set of segments with a new tone.
 * Useful when the user wants to change the tone without re-uploading the video.
 *
 * @param {string} originalScript - The previously generated script
 * @param {'profesional'|'gracioso'|'tierno'|'motivacional'|'informativo'|'sarcastico'} newTone
 * @param {'es'|'en'} language
 * @param {Array<{start: number, end: number}>} timings - Segment timing boundaries
 * @returns {Promise<{title: string, script: string, segments: Array<{start: number, end: number, text: string}>, hashtags: string[], caption: string}>}
 */
export async function regenerateWithTone(originalScript, newTone, language, timings) {
  if (!genAI) {
    throw new Error('Gemini no está inicializado. Por favor, configura tu API Key.');
  }

  const isEs = language === 'es';
  const toneLabels = {
    profesional: isEs ? 'profesional' : 'professional',
    gracioso: isEs ? 'gracioso y divertido' : 'funny and humorous',
    tierno: isEs ? 'tierno y emotivo' : 'sweet and emotional',
    motivacional: isEs ? 'motivacional y energético' : 'motivational and energetic',
    informativo: isEs ? 'informativo y educativo' : 'informative and educational',
    sarcastico: isEs ? 'sarcástico e irónico' : 'sarcastic and ironic',
  };

  const timingsJson = JSON.stringify(timings);
  const toneLabel = toneLabels[newTone] || toneLabels.profesional;

  const prompt = isEs
    ? `Reescribe el siguiente guion de narración con un tono ${toneLabel}. 
Mantén la misma estructura temporal de segmentos: ${timingsJson}

Guion original:
"${originalScript}"

Responde ÚNICAMENTE con JSON válido:
{
  "title": "Nuevo título",
  "script": "Guion completo reescrito",
  "segments": [{"start": 0, "end": 3, "text": "..."}],
  "hashtags": ["#tag1", "#tag2", "#tag3", "#tag4", "#tag5"],
  "caption": "Caption para redes sociales (máx 150 chars)"
}`
    : `Rewrite the following narration script with a ${toneLabel} tone.
Keep the same segment timing structure: ${timingsJson}

Original script:
"${originalScript}"

Respond ONLY with valid JSON:
{
  "title": "New title",
  "script": "Full rewritten script",
  "segments": [{"start": 0, "end": 3, "text": "..."}],
  "hashtags": ["#tag1", "#tag2", "#tag3", "#tag4", "#tag5"],
  "caption": "Social media caption (max 150 chars)"
}`;

  const response = await genAI.models.generateContent({
    model: 'gemini-2.5-flash',
    contents: [{ role: 'user', parts: [{ text: prompt }] }],
    config: {
      responseMimeType: 'application/json',
      temperature: 0.85,
      maxOutputTokens: 4096,
    },
  });

  const parsed = parseAIResponse(response, language);
  return sanitizeResult(parsed, language);
}
