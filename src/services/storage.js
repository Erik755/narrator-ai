/**
 * storage.js — localStorage Manager
 *
 * Provides persistent storage for NarradorAI settings, projects, and API keys.
 * All keys use the 'narratorai_' prefix to avoid collisions with other apps.
 *
 * @module services/storage
 */

const PREFIX = 'narratorai_';

/** Storage keys */
const KEYS = {
  API_KEY:  `${PREFIX}api_key`,
  PROJECTS: `${PREFIX}projects`,
  SETTINGS: `${PREFIX}settings`,
};

// ─── Safe localStorage Access ─────────────────────────────────────

/**
 * Safely read from localStorage with JSON parsing.
 * @param {string} key
 * @param {*} fallback - Default value if key doesn't exist or parse fails
 * @returns {*}
 */
function readJSON(key, fallback = null) {
  try {
    const raw = localStorage.getItem(key);
    if (raw === null || raw === undefined) return fallback;
    return JSON.parse(raw);
  } catch {
    return fallback;
  }
}

/**
 * Safely write to localStorage with JSON serialization.
 * @param {string} key
 * @param {*} value
 * @returns {boolean} True if write succeeded
 */
function writeJSON(key, value) {
  try {
    localStorage.setItem(key, JSON.stringify(value));
    return true;
  } catch (err) {
    // localStorage might be full or blocked
    console.warn(`[NarradorAI Storage] Failed to write key "${key}":`, err.message);
    return false;
  }
}

/**
 * Remove a key from localStorage.
 * @param {string} key
 */
function removeKey(key) {
  try {
    localStorage.removeItem(key);
  } catch {
    // Ignore
  }
}

// ─── API Key Management ───────────────────────────────────────────

/**
 * Save the Gemini API key.
 * The key is lightly obfuscated (base64) — not truly secure, but prevents
 * casual shoulder-surfing in DevTools.
 *
 * @param {string} key - The API key to save
 * @returns {boolean} True if saved successfully
 */
export function saveApiKey(key) {
  if (!key || typeof key !== 'string' || key.trim().length === 0) return false;
  const encoded = btoa(key.trim());
  return writeJSON(KEYS.API_KEY, encoded);
}

/**
 * Retrieve the saved API key.
 * @returns {string|null} The API key or null if not set
 */
export function getApiKey() {
  const encoded = readJSON(KEYS.API_KEY, null);
  if (!encoded || typeof encoded !== 'string') return null;
  try {
    return atob(encoded);
  } catch {
    return null;
  }
}

/**
 * Check if an API key has been saved.
 * @returns {boolean}
 */
export function hasApiKey() {
  return getApiKey() !== null;
}

/**
 * Remove the saved API key.
 */
export function clearApiKey() {
  removeKey(KEYS.API_KEY);
}

// ─── Project Management ───────────────────────────────────────────

/**
 * @typedef {object} Project
 * @property {string} id - Unique project ID
 * @property {string} title - Project title
 * @property {string} [videoName] - Original video filename
 * @property {number} [videoSize] - Original video file size in bytes
 * @property {string} tone - Narration tone used
 * @property {string} language - Language used (es/en)
 * @property {string} script - Generated narration script
 * @property {Array<{start: number, end: number, text: string}>} segments - Timed segments
 * @property {string[]} hashtags - Generated hashtags
 * @property {string} caption - Generated social media caption
 * @property {string} [srt] - Generated SRT subtitle content
 * @property {string} [voiceName] - TTS voice name used
 * @property {number} [voiceRate] - TTS speech rate used
 * @property {number} createdAt - Creation timestamp (ms)
 * @property {number} updatedAt - Last update timestamp (ms)
 */

/**
 * Get all saved projects, sorted by most recent first.
 * @returns {Project[]}
 */
export function getProjects() {
  const projects = readJSON(KEYS.PROJECTS, []);
  if (!Array.isArray(projects)) return [];
  return projects.sort((a, b) => (b.updatedAt || b.createdAt || 0) - (a.updatedAt || a.createdAt || 0));
}

/**
 * Get a single project by its ID.
 * @param {string} id
 * @returns {Project|null}
 */
export function getProject(id) {
  const projects = getProjects();
  return projects.find(p => p.id === id) || null;
}

/**
 * Save a project. If a project with the same ID exists, it will be updated.
 * If the project has no ID, one will be generated.
 *
 * @param {Project} project
 * @returns {Project} The saved project with ID and timestamps
 */
export function saveProject(project) {
  if (!project || typeof project !== 'object') {
    throw new Error('Project must be a valid object.');
  }

  const projects = readJSON(KEYS.PROJECTS, []);
  const now = Date.now();

  // Ensure project has an ID
  if (!project.id) {
    project.id = generateStorageId();
  }

  project.updatedAt = now;

  const existingIndex = projects.findIndex(p => p.id === project.id);
  if (existingIndex !== -1) {
    // Update existing project, preserve createdAt
    project.createdAt = projects[existingIndex].createdAt || now;
    projects[existingIndex] = project;
  } else {
    // New project
    project.createdAt = now;
    projects.unshift(project); // Add to beginning
  }

  // Limit to 50 projects to prevent localStorage bloat
  const trimmed = projects.slice(0, 50);

  if (!writeJSON(KEYS.PROJECTS, trimmed)) {
    throw new Error(
      'No se pudo guardar el proyecto. El almacenamiento local podría estar lleno. ' +
      'Intenta eliminar proyectos antiguos.'
    );
  }

  return project;
}

/**
 * Delete a project by its ID.
 * @param {string} id
 * @returns {boolean} True if a project was deleted
 */
export function deleteProject(id) {
  if (!id) return false;
  const projects = readJSON(KEYS.PROJECTS, []);
  const filtered = projects.filter(p => p.id !== id);
  if (filtered.length === projects.length) return false; // Nothing deleted
  writeJSON(KEYS.PROJECTS, filtered);
  return true;
}

/**
 * Delete all projects.
 * @returns {boolean} True if successful
 */
export function clearAllProjects() {
  return writeJSON(KEYS.PROJECTS, []);
}

/**
 * Get the total number of saved projects.
 * @returns {number}
 */
export function getProjectCount() {
  const projects = readJSON(KEYS.PROJECTS, []);
  return Array.isArray(projects) ? projects.length : 0;
}

// ─── Settings Management ──────────────────────────────────────────

/**
 * @typedef {object} AppSettings
 * @property {string} [language='es'] - UI and default generation language
 * @property {string} [defaultTone='profesional'] - Default narration tone
 * @property {string} [voiceName] - Preferred TTS voice name
 * @property {number} [voiceRate=1] - TTS speech rate
 * @property {number} [voicePitch=1] - TTS pitch
 * @property {string} [verticalMode='crop'] - Default vertical conversion mode
 * @property {boolean} [burnSubtitles=true] - Auto-burn subtitles on export
 * @property {boolean} [keepOriginalAudio=false] - Mix original audio with narration
 * @property {number} [narrationVolume=1.0] - Narration audio volume
 * @property {number} [originalVolume=0.1] - Original audio volume when mixing
 * @property {number} [subtitleFontSize=24] - Subtitle font size
 * @property {string} [subtitlePosition='bottom'] - Subtitle position
 * @property {string} [theme='dark'] - UI theme
 */

/** Default settings */
const DEFAULT_SETTINGS = {
  language: 'es',
  defaultTone: 'profesional',
  voiceName: '',
  voiceRate: 1,
  voicePitch: 1,
  verticalMode: 'crop',
  burnSubtitles: true,
  keepOriginalAudio: false,
  narrationVolume: 1.0,
  originalVolume: 0.1,
  subtitleFontSize: 24,
  subtitlePosition: 'bottom',
  theme: 'dark',
};

/**
 * Get all application settings, merged with defaults.
 * @returns {AppSettings}
 */
export function getSettings() {
  const saved = readJSON(KEYS.SETTINGS, {});
  return { ...DEFAULT_SETTINGS, ...(typeof saved === 'object' && saved !== null ? saved : {}) };
}

/**
 * Save application settings. Merges with existing settings.
 * @param {Partial<AppSettings>} settings
 * @returns {AppSettings} The complete merged settings
 */
export function saveSettings(settings) {
  if (!settings || typeof settings !== 'object') {
    throw new Error('Settings must be a valid object.');
  }
  const current = getSettings();
  const merged = { ...current, ...settings };
  writeJSON(KEYS.SETTINGS, merged);
  return merged;
}

/**
 * Reset settings to defaults.
 * @returns {AppSettings}
 */
export function resetSettings() {
  writeJSON(KEYS.SETTINGS, DEFAULT_SETTINGS);
  return { ...DEFAULT_SETTINGS };
}

/**
 * Get a single setting value.
 * @param {string} key - Setting key
 * @returns {*}
 */
export function getSetting(key) {
  const settings = getSettings();
  return settings[key];
}

/**
 * Update a single setting value.
 * @param {string} key
 * @param {*} value
 */
export function setSetting(key, value) {
  const settings = getSettings();
  settings[key] = value;
  writeJSON(KEYS.SETTINGS, settings);
}

// ─── Data Management ──────────────────────────────────────────────

/**
 * Get the approximate total size of NarradorAI data in localStorage (bytes).
 * @returns {number}
 */
export function getStorageSize() {
  let total = 0;
  for (const key in KEYS) {
    const raw = localStorage.getItem(KEYS[key]);
    if (raw) total += raw.length * 2; // UTF-16 = ~2 bytes per char
  }
  return total;
}

/**
 * Clear ALL NarradorAI data from localStorage.
 */
export function clearAllData() {
  for (const key of Object.values(KEYS)) {
    removeKey(key);
  }
}

/**
 * Export all NarradorAI data as a JSON string (for backup).
 * @returns {string}
 */
export function exportData() {
  return JSON.stringify({
    version: 1,
    exportedAt: new Date().toISOString(),
    projects: getProjects(),
    settings: getSettings(),
    // API key intentionally excluded from exports
  }, null, 2);
}

/**
 * Import NarradorAI data from a JSON string (restore from backup).
 * @param {string} jsonString
 * @returns {{projects: number, settings: boolean}} Import summary
 */
export function importData(jsonString) {
  let data;
  try {
    data = JSON.parse(jsonString);
  } catch {
    throw new Error('El archivo de importación no contiene JSON válido.');
  }

  let projectsImported = 0;
  let settingsImported = false;

  if (Array.isArray(data.projects)) {
    const existing = getProjects();
    const existingIds = new Set(existing.map(p => p.id));
    const newProjects = data.projects.filter(p => p && p.id && !existingIds.has(p.id));
    const merged = [...existing, ...newProjects].slice(0, 50);
    writeJSON(KEYS.PROJECTS, merged);
    projectsImported = newProjects.length;
  }

  if (data.settings && typeof data.settings === 'object') {
    saveSettings(data.settings);
    settingsImported = true;
  }

  return { projects: projectsImported, settings: settingsImported };
}

// ─── Internal Helpers ─────────────────────────────────────────────

/**
 * Generate a unique ID for storage items.
 * @returns {string}
 */
function generateStorageId() {
  const timestamp = Date.now().toString(36);
  const random = Math.random().toString(36).substring(2, 8);
  return `${timestamp}-${random}`;
}
