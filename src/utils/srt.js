/**
 * srt.js — SRT Subtitle Generator & Parser
 *
 * Generates and parses SRT (SubRip Text) subtitle files.
 * SRT is the most widely supported subtitle format.
 *
 * SRT format:
 *   1
 *   00:00:00,000 --> 00:00:03,500
 *   First subtitle line
 *
 *   2
 *   00:00:03,500 --> 00:00:07,000
 *   Second subtitle line
 *
 * @module utils/srt
 */

/**
 * Generate an SRT-formatted string from an array of timed segments.
 *
 * @param {Array<{start: number, end: number, text: string}>} segments
 *   Each segment must have `start` (seconds), `end` (seconds), and `text`.
 * @param {object} [options]
 * @param {number} [options.maxCharsPerLine=42] - Maximum characters per subtitle line
 * @param {number} [options.maxLines=2] - Maximum lines per subtitle block
 * @returns {string} SRT-formatted subtitle content
 */
export function generateSRT(segments, options = {}) {
  if (!Array.isArray(segments) || segments.length === 0) {
    return '';
  }

  const maxChars = options.maxCharsPerLine || 42;
  const maxLines = options.maxLines || 2;

  const srtBlocks = [];

  // Sort segments by start time
  const sorted = [...segments].sort((a, b) => a.start - b.start);

  sorted.forEach((segment, index) => {
    const { start, end, text } = segment;

    // Validate times
    const safeStart = Math.max(0, Number(start) || 0);
    const safeEnd = Math.max(safeStart + 0.1, Number(end) || safeStart + 1);

    // Skip empty segments
    if (!text || text.trim().length === 0) return;

    // Wrap text to fit within character limits
    const wrappedText = wrapSubtitleText(text.trim(), maxChars, maxLines);

    const block = [
      `${srtBlocks.length + 1}`,
      `${formatTimestamp(safeStart)} --> ${formatTimestamp(safeEnd)}`,
      wrappedText,
    ].join('\n');

    srtBlocks.push(block);
  });

  return srtBlocks.join('\n\n') + '\n';
}

/**
 * Parse an SRT-formatted string back into an array of timed segments.
 *
 * @param {string} srtText - SRT file content
 * @returns {Array<{index: number, start: number, end: number, text: string}>}
 */
export function parseSRT(srtText) {
  if (!srtText || typeof srtText !== 'string' || srtText.trim().length === 0) {
    return [];
  }

  const segments = [];

  // Split by double newlines (or more) to get blocks
  // Normalize line endings first
  const normalized = srtText.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
  const blocks = normalized.trim().split(/\n\s*\n/);

  for (const block of blocks) {
    const lines = block.trim().split('\n');
    if (lines.length < 2) continue;

    // First line should be the index number (but we'll be lenient)
    let timeLineIndex = 0;

    // Find the timestamp line (contains "-->")
    for (let i = 0; i < lines.length; i++) {
      if (lines[i].includes('-->')) {
        timeLineIndex = i;
        break;
      }
    }

    const timeLine = lines[timeLineIndex];
    if (!timeLine || !timeLine.includes('-->')) continue;

    // Parse timestamps
    const timeMatch = timeLine.match(
      /(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})/
    );
    if (!timeMatch) continue;

    const start = parseTimeParts(
      parseInt(timeMatch[1]),
      parseInt(timeMatch[2]),
      parseInt(timeMatch[3]),
      parseInt(timeMatch[4].padEnd(3, '0'))
    );

    const end = parseTimeParts(
      parseInt(timeMatch[5]),
      parseInt(timeMatch[6]),
      parseInt(timeMatch[7]),
      parseInt(timeMatch[8].padEnd(3, '0'))
    );

    // Everything after the timestamp line is text
    const text = lines
      .slice(timeLineIndex + 1)
      .join('\n')
      .trim();

    // Parse index (if present as the line before the timestamp)
    let index = segments.length + 1;
    if (timeLineIndex > 0) {
      const parsedIndex = parseInt(lines[timeLineIndex - 1].trim());
      if (!isNaN(parsedIndex)) index = parsedIndex;
    }

    if (text) {
      segments.push({ index, start, end, text });
    }
  }

  return segments;
}

/**
 * Format a time value in seconds to SRT timestamp format: HH:MM:SS,mmm
 *
 * @param {number} seconds - Time in seconds (can be fractional)
 * @returns {string} Formatted timestamp (e.g., "00:01:23,456")
 */
export function formatTimestamp(seconds) {
  if (typeof seconds !== 'number' || isNaN(seconds) || seconds < 0) {
    return '00:00:00,000';
  }

  const totalMs = Math.round(seconds * 1000);
  const ms = totalMs % 1000;
  const totalSecs = Math.floor(totalMs / 1000);
  const s = totalSecs % 60;
  const totalMins = Math.floor(totalSecs / 60);
  const m = totalMins % 60;
  const h = Math.floor(totalMins / 60);

  return (
    String(h).padStart(2, '0') + ':' +
    String(m).padStart(2, '0') + ':' +
    String(s).padStart(2, '0') + ',' +
    String(ms).padStart(3, '0')
  );
}

/**
 * Parse timestamp parts into seconds.
 * @param {number} hours
 * @param {number} minutes
 * @param {number} seconds
 * @param {number} milliseconds
 * @returns {number}
 */
function parseTimeParts(hours, minutes, seconds, milliseconds) {
  return hours * 3600 + minutes * 60 + seconds + milliseconds / 1000;
}

/**
 * Wrap subtitle text to fit within character limits.
 * Breaks text at word boundaries for readability.
 *
 * @param {string} text
 * @param {number} maxChars - Max characters per line
 * @param {number} maxLines - Max number of lines
 * @returns {string}
 */
function wrapSubtitleText(text, maxChars, maxLines) {
  // If text already fits in one line, return as-is
  if (text.length <= maxChars) return text;

  // If text already has manual line breaks, respect them
  if (text.includes('\n')) {
    return text
      .split('\n')
      .slice(0, maxLines)
      .map(line => line.length > maxChars ? line.slice(0, maxChars - 1) + '…' : line)
      .join('\n');
  }

  const words = text.split(/\s+/);
  const lines = [];
  let currentLine = '';

  for (const word of words) {
    if (lines.length >= maxLines) break;

    const testLine = currentLine ? `${currentLine} ${word}` : word;

    if (testLine.length <= maxChars) {
      currentLine = testLine;
    } else {
      if (currentLine) {
        lines.push(currentLine);
        currentLine = word;
      } else {
        // Single word exceeds max — truncate
        lines.push(word.slice(0, maxChars - 1) + '…');
        currentLine = '';
      }
    }
  }

  // Push remaining text
  if (currentLine && lines.length < maxLines) {
    lines.push(currentLine);
  } else if (currentLine) {
    // Remaining text doesn't fit — append to last line with ellipsis
    const lastLine = lines[lines.length - 1];
    if (lastLine.length + 3 <= maxChars) {
      lines[lines.length - 1] = lastLine + '…';
    }
  }

  return lines.join('\n');
}

/**
 * Validate SRT content for common issues.
 *
 * @param {string} srtText
 * @returns {{valid: boolean, errors: string[], segments: number}}
 */
export function validateSRT(srtText) {
  const errors = [];
  const segments = parseSRT(srtText);

  if (segments.length === 0) {
    errors.push('No se encontraron subtítulos válidos en el archivo.');
    return { valid: false, errors, segments: 0 };
  }

  for (let i = 0; i < segments.length; i++) {
    const seg = segments[i];

    if (seg.end <= seg.start) {
      errors.push(`Segmento ${seg.index}: El tiempo de fin (${seg.end}s) es anterior o igual al inicio (${seg.start}s).`);
    }

    if (seg.end - seg.start > 15) {
      errors.push(`Segmento ${seg.index}: Duración excesiva (${(seg.end - seg.start).toFixed(1)}s). Se recomienda máximo 8 segundos.`);
    }

    if (i > 0 && seg.start < segments[i - 1].end) {
      errors.push(`Segmento ${seg.index}: Se solapa con el segmento anterior.`);
    }
  }

  return {
    valid: errors.length === 0,
    errors,
    segments: segments.length,
  };
}

/**
 * Shift all subtitle timestamps by a given offset.
 *
 * @param {string} srtText - Original SRT content
 * @param {number} offsetSeconds - Seconds to shift (positive = later, negative = earlier)
 * @returns {string} New SRT content with shifted timestamps
 */
export function shiftTimestamps(srtText, offsetSeconds) {
  const segments = parseSRT(srtText);
  const shifted = segments.map(seg => ({
    ...seg,
    start: Math.max(0, seg.start + offsetSeconds),
    end: Math.max(0.1, seg.end + offsetSeconds),
  }));
  return generateSRT(shifted);
}

/**
 * Merge multiple SRT contents into one, adjusting timestamps.
 *
 * @param {string[]} srtTexts - Array of SRT content strings
 * @returns {string} Merged SRT content
 */
export function mergeSRT(srtTexts) {
  const allSegments = [];
  for (const srt of srtTexts) {
    allSegments.push(...parseSRT(srt));
  }
  allSegments.sort((a, b) => a.start - b.start);
  return generateSRT(allSegments);
}
