/**
 * helpers.js — Utility Functions
 *
 * General-purpose helpers used throughout the NarradorAI application.
 *
 * @module utils/helpers
 */

// ─── Formatting ───────────────────────────────────────────────────

/**
 * Format a file size in bytes to a human-readable string.
 *
 * @param {number} bytes - File size in bytes
 * @param {number} [decimals=1] - Number of decimal places
 * @returns {string} Formatted size (e.g., "4.2 MB")
 */
export function formatFileSize(bytes) {
  if (typeof bytes !== 'number' || isNaN(bytes) || bytes < 0) return '0 B';
  if (bytes === 0) return '0 B';

  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  const k = 1024;
  const i = Math.min(Math.floor(Math.log(bytes) / Math.log(k)), units.length - 1);
  const size = bytes / Math.pow(k, i);

  // No decimals for bytes, 1 decimal for KB, 2 for larger
  const decimals = i === 0 ? 0 : i === 1 ? 1 : 2;
  return `${size.toFixed(decimals)} ${units[i]}`;
}

/**
 * Format a duration in seconds to a human-readable string.
 *
 * @param {number} seconds - Duration in seconds
 * @param {boolean} [showMs=false] - Include milliseconds
 * @returns {string} Formatted duration (e.g., "1:23" or "01:23:45")
 */
export function formatDuration(seconds, showMs = false) {
  if (typeof seconds !== 'number' || isNaN(seconds) || seconds < 0) return '0:00';

  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor(seconds % 60);
  const ms = Math.round((seconds % 1) * 1000);

  let result;
  if (h > 0) {
    result = `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  } else {
    result = `${m}:${String(s).padStart(2, '0')}`;
  }

  if (showMs) {
    result += `.${String(ms).padStart(3, '0')}`;
  }

  return result;
}

/**
 * Format a timestamp for display in UI (short form).
 *
 * @param {number} seconds
 * @returns {string} e.g., "0:03", "1:45"
 */
export function formatTimecode(seconds) {
  return formatDuration(seconds, false);
}

/**
 * Format a Date object or timestamp to a locale-friendly string.
 *
 * @param {Date|number} date - Date object or timestamp in ms
 * @param {'short'|'long'|'relative'} [format='short']
 * @returns {string}
 */
export function formatDate(date, format = 'short') {
  const d = date instanceof Date ? date : new Date(date);
  if (isNaN(d.getTime())) return '—';

  if (format === 'relative') {
    return getRelativeTime(d);
  }

  const options = format === 'long'
    ? { year: 'numeric', month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit' }
    : { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' };

  return d.toLocaleDateString('es-ES', options);
}

/**
 * Get a relative time string (e.g., "hace 3 minutos", "ayer").
 * @param {Date} date
 * @returns {string}
 */
function getRelativeTime(date) {
  const now = Date.now();
  const diffMs = now - date.getTime();
  const diffSecs = Math.floor(diffMs / 1000);
  const diffMins = Math.floor(diffSecs / 60);
  const diffHours = Math.floor(diffMins / 60);
  const diffDays = Math.floor(diffHours / 24);

  if (diffSecs < 60) return 'hace un momento';
  if (diffMins < 60) return `hace ${diffMins} min`;
  if (diffHours < 24) return `hace ${diffHours}h`;
  if (diffDays === 1) return 'ayer';
  if (diffDays < 7) return `hace ${diffDays} días`;
  if (diffDays < 30) return `hace ${Math.floor(diffDays / 7)} semanas`;
  return formatDate(date, 'short');
}

// ─── ID Generation ────────────────────────────────────────────────

/**
 * Generate a unique ID string.
 * Uses timestamp + random for uniqueness without external dependencies.
 *
 * @param {number} [length=8] - Length of the random suffix
 * @returns {string} Unique ID (e.g., "m5k9a2b1-x7f3g2")
 */
export function generateId(length = 8) {
  const timestamp = Date.now().toString(36);
  const chars = 'abcdefghijklmnopqrstuvwxyz0123456789';
  let random = '';
  const cryptoRandom = getCryptoRandom(length);
  for (let i = 0; i < length; i++) {
    random += chars[cryptoRandom[i] % chars.length];
  }
  return `${timestamp}-${random}`;
}

/**
 * Get cryptographically secure random bytes, with Math.random fallback.
 * @param {number} count
 * @returns {Uint8Array}
 */
function getCryptoRandom(count) {
  try {
    return crypto.getRandomValues(new Uint8Array(count));
  } catch {
    const arr = new Uint8Array(count);
    for (let i = 0; i < count; i++) {
      arr[i] = Math.floor(Math.random() * 256);
    }
    return arr;
  }
}

// ─── Function Utilities ───────────────────────────────────────────

/**
 * Create a debounced version of a function.
 * The function will only execute after `delay` ms of no calls.
 *
 * @param {Function} fn - Function to debounce
 * @param {number} delay - Delay in milliseconds
 * @returns {Function & {cancel: Function, flush: Function}}
 */
export function debounce(fn, delay) {
  let timeoutId = null;
  let lastArgs = null;
  let lastThis = null;

  function debounced(...args) {
    lastArgs = args;
    lastThis = this;

    if (timeoutId !== null) {
      clearTimeout(timeoutId);
    }

    timeoutId = setTimeout(() => {
      timeoutId = null;
      fn.apply(lastThis, lastArgs);
      lastArgs = null;
      lastThis = null;
    }, delay);
  }

  debounced.cancel = () => {
    if (timeoutId !== null) {
      clearTimeout(timeoutId);
      timeoutId = null;
    }
    lastArgs = null;
    lastThis = null;
  };

  debounced.flush = () => {
    if (timeoutId !== null) {
      clearTimeout(timeoutId);
      timeoutId = null;
      if (lastArgs !== null) {
        fn.apply(lastThis, lastArgs);
      }
      lastArgs = null;
      lastThis = null;
    }
  };

  return debounced;
}

/**
 * Create a throttled version of a function.
 * The function will execute at most once per `limit` ms.
 *
 * @param {Function} fn - Function to throttle
 * @param {number} limit - Minimum interval in milliseconds
 * @returns {Function & {cancel: Function}}
 */
export function throttle(fn, limit) {
  let inThrottle = false;
  let lastArgs = null;
  let lastThis = null;
  let timeoutId = null;

  function throttled(...args) {
    if (inThrottle) {
      lastArgs = args;
      lastThis = this;
      return;
    }

    fn.apply(this, args);
    inThrottle = true;

    timeoutId = setTimeout(() => {
      inThrottle = false;
      if (lastArgs !== null) {
        throttled.apply(lastThis, lastArgs);
        lastArgs = null;
        lastThis = null;
      }
    }, limit);
  }

  throttled.cancel = () => {
    if (timeoutId !== null) {
      clearTimeout(timeoutId);
      timeoutId = null;
    }
    inThrottle = false;
    lastArgs = null;
    lastThis = null;
  };

  return throttled;
}

// ─── DOM Utilities ────────────────────────────────────────────────

/**
 * Download a Blob as a file in the browser.
 *
 * @param {Blob} blob - The data to download
 * @param {string} filename - Suggested filename
 */
export function downloadBlob(blob, filename) {
  if (!(blob instanceof Blob)) {
    throw new Error('downloadBlob requires a Blob instance.');
  }

  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename || 'download';
  anchor.style.display = 'none';

  document.body.appendChild(anchor);
  anchor.click();

  // Cleanup after a short delay to ensure the download starts
  setTimeout(() => {
    document.body.removeChild(anchor);
    URL.revokeObjectURL(url);
  }, 100);
}

/**
 * Download a text string as a file.
 *
 * @param {string} text - Text content
 * @param {string} filename - Filename with extension
 * @param {string} [mimeType='text/plain'] - MIME type
 */
export function downloadText(text, filename, mimeType = 'text/plain') {
  const blob = new Blob([text], { type: `${mimeType};charset=utf-8` });
  downloadBlob(blob, filename);
}

/**
 * Create a DOM element with attributes and children.
 * A lightweight alternative to JSX for vanilla JS projects.
 *
 * @param {string} tag - HTML tag name
 * @param {object} [attrs={}] - Attributes, properties, and event handlers
 *   - Attributes starting with 'on' are added as event listeners
 *   - 'class' or 'className' sets the className
 *   - 'style' can be a string or an object
 *   - 'dataset' sets data-* attributes
 *   - 'html' sets innerHTML (use with caution)
 *   - Other keys are set as attributes
 * @param {...(string|Node|Array)} children - Child nodes or text content
 * @returns {HTMLElement}
 */
export function createElement(tag, attrs = {}, ...children) {
  const el = document.createElement(tag);

  for (const [key, value] of Object.entries(attrs)) {
    if (value === null || value === undefined || value === false) continue;

    if (key === 'class' || key === 'className') {
      el.className = Array.isArray(value) ? value.filter(Boolean).join(' ') : value;
    } else if (key === 'style' && typeof value === 'object') {
      Object.assign(el.style, value);
    } else if (key === 'dataset' && typeof value === 'object') {
      for (const [dKey, dVal] of Object.entries(value)) {
        el.dataset[dKey] = dVal;
      }
    } else if (key === 'html') {
      el.innerHTML = value;
    } else if (key.startsWith('on') && typeof value === 'function') {
      const event = key.slice(2).toLowerCase();
      el.addEventListener(event, value);
    } else if (key === 'ref' && typeof value === 'function') {
      // React-style ref callback
      value(el);
    } else if (value === true) {
      el.setAttribute(key, '');
    } else {
      el.setAttribute(key, String(value));
    }
  }

  appendChildren(el, children);
  return el;
}

/**
 * Recursively append children to an element.
 * @param {HTMLElement} parent
 * @param {Array} children
 */
function appendChildren(parent, children) {
  for (const child of children) {
    if (child === null || child === undefined || child === false) continue;
    if (Array.isArray(child)) {
      appendChildren(parent, child);
    } else if (child instanceof Node) {
      parent.appendChild(child);
    } else {
      parent.appendChild(document.createTextNode(String(child)));
    }
  }
}

// ─── Toast Notifications ──────────────────────────────────────────

/** @type {HTMLElement|null} */
let toastContainer = null;

/**
 * Display a toast notification.
 *
 * @param {string} message - Message to display
 * @param {'info'|'success'|'warning'|'error'} [type='info'] - Toast type
 * @param {number} [durationMs=4000] - Auto-dismiss duration in ms (0 = persistent)
 * @returns {HTMLElement} The toast element (can be removed manually)
 */
export function showToast(message, type = 'info', durationMs = 4000) {
  if (!toastContainer) {
    toastContainer = createElement('div', {
      id: 'narratorai-toasts',
      style: {
        position: 'fixed',
        bottom: '24px',
        right: '24px',
        zIndex: '99999',
        display: 'flex',
        flexDirection: 'column',
        gap: '8px',
        pointerEvents: 'none',
        maxWidth: '400px',
      },
    });
    document.body.appendChild(toastContainer);
  }

  const icons = {
    info: 'ℹ️',
    success: '✅',
    warning: '⚠️',
    error: '❌',
  };

  const bgColors = {
    info: 'rgba(59, 130, 246, 0.95)',
    success: 'rgba(34, 197, 94, 0.95)',
    warning: 'rgba(234, 179, 8, 0.95)',
    error: 'rgba(239, 68, 68, 0.95)',
  };

  const toast = createElement('div', {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: '10px',
      padding: '12px 20px',
      borderRadius: '12px',
      background: bgColors[type] || bgColors.info,
      color: '#fff',
      fontSize: '14px',
      fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
      fontWeight: '500',
      boxShadow: '0 8px 32px rgba(0, 0, 0, 0.3)',
      backdropFilter: 'blur(8px)',
      pointerEvents: 'auto',
      cursor: 'pointer',
      transform: 'translateX(120%)',
      transition: 'transform 0.35s cubic-bezier(0.21, 1.02, 0.73, 1), opacity 0.3s ease',
      opacity: '0',
      lineHeight: '1.4',
    },
    onClick: () => removeToast(toast),
  },
    createElement('span', { style: { fontSize: '18px', flexShrink: '0' } }, icons[type] || icons.info),
    createElement('span', {}, message)
  );

  toastContainer.appendChild(toast);

  // Trigger enter animation
  requestAnimationFrame(() => {
    requestAnimationFrame(() => {
      toast.style.transform = 'translateX(0)';
      toast.style.opacity = '1';
    });
  });

  // Auto-dismiss
  if (durationMs > 0) {
    setTimeout(() => removeToast(toast), durationMs);
  }

  return toast;
}

/**
 * Remove a toast element with an exit animation.
 * @param {HTMLElement} toast
 */
function removeToast(toast) {
  if (!toast || !toast.parentNode) return;

  toast.style.transform = 'translateX(120%)';
  toast.style.opacity = '0';

  setTimeout(() => {
    toast.remove();
    // Clean up container if empty
    if (toastContainer && toastContainer.children.length === 0) {
      toastContainer.remove();
      toastContainer = null;
    }
  }, 350);
}

// ─── Clipboard ────────────────────────────────────────────────────

/**
 * Copy text to the clipboard with fallback for older browsers.
 *
 * @param {string} text
 * @returns {Promise<boolean>} True if copy succeeded
 */
export async function copyToClipboard(text) {
  try {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    // Fall through to fallback
  }

  // Fallback: hidden textarea
  try {
    const textarea = document.createElement('textarea');
    textarea.value = text;
    textarea.style.cssText = 'position:fixed;left:-9999px;top:-9999px;opacity:0';
    document.body.appendChild(textarea);
    textarea.focus();
    textarea.select();
    const ok = document.execCommand('copy');
    document.body.removeChild(textarea);
    return ok;
  } catch {
    return false;
  }
}

// ─── Miscellaneous ────────────────────────────────────────────────

/**
 * Wait for a specified number of milliseconds.
 *
 * @param {number} ms
 * @returns {Promise<void>}
 */
export function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

/**
 * Clamp a number between a minimum and maximum value.
 *
 * @param {number} value
 * @param {number} min
 * @param {number} max
 * @returns {number}
 */
export function clamp(value, min, max) {
  return Math.min(max, Math.max(min, value));
}

/**
 * Check if a value is a non-empty string.
 * @param {*} val
 * @returns {boolean}
 */
export function isNonEmptyString(val) {
  return typeof val === 'string' && val.trim().length > 0;
}

/**
 * Truncate a string to a maximum length with an ellipsis.
 *
 * @param {string} str
 * @param {number} maxLength
 * @returns {string}
 */
export function truncate(str, maxLength) {
  if (!str || str.length <= maxLength) return str || '';
  return str.slice(0, maxLength - 1) + '…';
}
