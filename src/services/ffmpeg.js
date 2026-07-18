/**
 * ffmpeg.js — FFmpeg.wasm Video Processing Service
 *
 * Wraps @ffmpeg/ffmpeg and @ffmpeg/util for in-browser video editing.
 * Handles vertical conversion, subtitle burning, audio overlay, and full export.
 *
 * @module services/ffmpeg
 */

import { FFmpeg } from '@ffmpeg/ffmpeg';
import { fetchFile, toBlobURL } from '@ffmpeg/util';

/** @type {FFmpeg|null} */
let ffmpeg = null;

/** Whether FFmpeg core has been loaded */
let isLoaded = false;

/** CDN base URL for FFmpeg WASM files */
const FFMPEG_CDN = 'https://unpkg.com/@ffmpeg/core@0.12.6/dist/esm';

// ─── Initialization ───────────────────────────────────────────────

/**
 * Initialize and load FFmpeg.wasm.
 * Must be called once before any processing functions.
 *
 * @param {function(number): void} [onProgress] - Loading progress callback (0–1)
 * @returns {Promise<FFmpeg>}
 */
export async function initFFmpeg(onProgress) {
  if (isLoaded && ffmpeg) return ffmpeg;

  ffmpeg = new FFmpeg();

  // Wire up the log handler for debugging
  ffmpeg.on('log', ({ message }) => {
    if (import.meta.env?.DEV) {
      console.debug('[FFmpeg]', message);
    }
  });

  // Wire up progress handler
  ffmpeg.on('progress', ({ progress, time }) => {
    onProgress?.(Math.min(1, Math.max(0, progress)));
  });

  try {
    const coreURL = await toBlobURL(`${FFMPEG_CDN}/ffmpeg-core.js`, 'text/javascript');
    const wasmURL = await toBlobURL(`${FFMPEG_CDN}/ffmpeg-core.wasm`, 'application/wasm');

    await ffmpeg.load({ coreURL, wasmURL });
    isLoaded = true;
    return ffmpeg;
  } catch (err) {
    ffmpeg = null;
    isLoaded = false;
    throw new Error(
      `No se pudo cargar FFmpeg.wasm: ${err.message}. ` +
      `Asegúrate de tener conexión a internet y que tu navegador soporte WebAssembly.`
    );
  }
}

/**
 * Check if FFmpeg has been loaded.
 * @returns {boolean}
 */
export function isFFmpegLoaded() {
  return isLoaded && ffmpeg !== null;
}

/**
 * Terminate the FFmpeg instance and free resources.
 */
export function terminateFFmpeg() {
  if (ffmpeg) {
    ffmpeg.terminate();
    ffmpeg = null;
    isLoaded = false;
  }
}

// ─── File Management ──────────────────────────────────────────────

/**
 * Write a file to FFmpeg's virtual filesystem.
 * @param {string} name - Virtual filename
 * @param {File|Blob|Uint8Array|string} data - File data
 */
async function writeVFS(name, data) {
  ensureLoaded();
  if (data instanceof File || data instanceof Blob) {
    const buffer = await fetchFile(data);
    await ffmpeg.writeFile(name, buffer);
  } else if (data instanceof Uint8Array) {
    await ffmpeg.writeFile(name, data);
  } else if (typeof data === 'string') {
    const encoder = new TextEncoder();
    await ffmpeg.writeFile(name, encoder.encode(data));
  } else {
    throw new Error(`Unsupported data type for virtual FS write.`);
  }
}

/**
 * Read a file from FFmpeg's virtual filesystem.
 * @param {string} name
 * @returns {Promise<Uint8Array>}
 */
async function readVFS(name) {
  ensureLoaded();
  return ffmpeg.readFile(name);
}

/**
 * Delete a file from FFmpeg's virtual filesystem (best-effort).
 * @param {string} name
 */
async function deleteVFS(name) {
  try {
    await ffmpeg.deleteFile(name);
  } catch {
    // Ignore — file may not exist
  }
}

/**
 * Clean up multiple files from the virtual FS.
 * @param {string[]} files
 */
async function cleanupVFS(files) {
  for (const file of files) {
    await deleteVFS(file);
  }
}

/**
 * Ensure FFmpeg is loaded before running operations.
 * @throws {Error}
 */
function ensureLoaded() {
  if (!isLoaded || !ffmpeg) {
    throw new Error('FFmpeg no está cargado. Llama a initFFmpeg() primero.');
  }
}

// ─── Video Processing ─────────────────────────────────────────────

/**
 * Convert a video to vertical (9:16) aspect ratio.
 *
 * @param {File|Blob|Uint8Array} videoData - Input video data
 * @param {'crop'|'pad'|'blur'} [mode='crop'] - Conversion mode
 *   - 'crop': Crop to center 9:16 region (cuts sides)
 *   - 'pad': Add black bars (letterbox) to make 9:16
 *   - 'blur': Blurred background fill (most visually appealing)
 * @param {function(number): void} [onProgress]
 * @returns {Promise<Blob>} Vertical video as MP4 Blob
 */
export async function convertToVertical(videoData, mode = 'crop', onProgress) {
  ensureLoaded();

  const inputFile = 'input_vert.mp4';
  const outputFile = 'output_vert.mp4';
  const tempFiles = [inputFile, outputFile];

  try {
    if (onProgress) {
      ffmpeg.on('progress', ({ progress }) => onProgress(progress));
    }

    await writeVFS(inputFile, videoData);

    let filterComplex;
    switch (mode) {
      case 'pad':
        // Scales to fit within 1080x1920 and adds black padding
        filterComplex = [
          '-vf',
          'scale=1080:1920:force_original_aspect_ratio=decrease,pad=1080:1920:(ow-iw)/2:(oh-ih)/2:black',
        ];
        break;

      case 'blur':
        // Blurred background + sharp foreground layered
        filterComplex = [
          '-filter_complex',
          '[0:v]scale=1080:1920,boxblur=20:20[bg];' +
          '[0:v]scale=1080:1920:force_original_aspect_ratio=decrease[fg];' +
          '[bg][fg]overlay=(W-w)/2:(H-h)/2',
        ];
        break;

      case 'crop':
      default:
        // Crop center to 9:16
        filterComplex = [
          '-vf',
          'crop=ih*9/16:ih:(iw-ih*9/16)/2:0,scale=1080:1920',
        ];
        break;
    }

    await ffmpeg.exec([
      '-i', inputFile,
      ...filterComplex,
      '-c:v', 'libx264',
      '-preset', 'fast',
      '-crf', '23',
      '-c:a', 'aac',
      '-b:a', '128k',
      '-movflags', '+faststart',
      '-y',
      outputFile,
    ]);

    const outputData = await readVFS(outputFile);
    return new Blob([outputData.buffer], { type: 'video/mp4' });
  } finally {
    await cleanupVFS(tempFiles);
  }
}

/**
 * Burn SRT subtitles into a video using the drawtext filter.
 * The drawtext filter is more reliably available in FFmpeg.wasm than the subtitles filter.
 *
 * @param {File|Blob|Uint8Array} videoData - Input video
 * @param {string} srtContent - SRT subtitle file content
 * @param {object} [style] - Subtitle styling options
 * @param {number} [style.fontSize=24]
 * @param {string} [style.fontColor='white']
 * @param {string} [style.bgColor='black@0.6']
 * @param {string} [style.position='bottom'] - 'top', 'center', 'bottom'
 * @param {function(number): void} [onProgress]
 * @returns {Promise<Blob>} Video with burned subtitles as MP4 Blob
 */
export async function addSubtitles(videoData, srtContent, style = {}, onProgress) {
  ensureLoaded();

  const inputFile = 'input_subs.mp4';
  const subtitleFile = 'subtitles.srt';
  const outputFile = 'output_subs.mp4';
  const tempFiles = [inputFile, subtitleFile, outputFile];

  const fontSize = style.fontSize || 24;
  const fontColor = style.fontColor || 'white';
  const bgColor = style.bgColor || 'black@0.6';
  const position = style.position || 'bottom';

  try {
    if (onProgress) {
      ffmpeg.on('progress', ({ progress }) => onProgress(progress));
    }

    await writeVFS(inputFile, videoData);
    await writeVFS(subtitleFile, srtContent);

    // Calculate Y position
    let yExpr;
    switch (position) {
      case 'top':
        yExpr = 'h*0.08';
        break;
      case 'center':
        yExpr = '(h-text_h)/2';
        break;
      case 'bottom':
      default:
        yExpr = 'h-text_h-h*0.08';
        break;
    }

    // Use subtitles filter (works in most FFmpeg.wasm builds)
    await ffmpeg.exec([
      '-i', inputFile,
      '-vf', `subtitles=${subtitleFile}:force_style='FontSize=${fontSize},PrimaryColour=&H00FFFFFF,OutlineColour=&H00000000,Outline=2,Shadow=1,MarginV=30'`,
      '-c:v', 'libx264',
      '-preset', 'fast',
      '-crf', '23',
      '-c:a', 'copy',
      '-movflags', '+faststart',
      '-y',
      outputFile,
    ]);

    const outputData = await readVFS(outputFile);
    return new Blob([outputData.buffer], { type: 'video/mp4' });
  } catch (subtitleFilterError) {
    // Fallback: try using drawtext filter for each segment
    try {
      const segments = parseSRTtoSegments(srtContent);
      const drawtextFilters = segments.map(seg => {
        const escapedText = seg.text
          .replace(/\\/g, '\\\\')
          .replace(/'/g, "'\\\\\\''")
          .replace(/:/g, '\\:')
          .replace(/\n/g, '\\n');

        return `drawtext=text='${escapedText}':fontsize=${fontSize}:fontcolor=${fontColor}:` +
          `borderw=2:bordercolor=black:x=(w-text_w)/2:y=${yExpr}:` +
          `enable='between(t,${seg.start},${seg.end})'`;
      });

      const filterStr = drawtextFilters.join(',');

      await writeVFS(inputFile, videoData);
      await ffmpeg.exec([
        '-i', inputFile,
        '-vf', filterStr,
        '-c:v', 'libx264',
        '-preset', 'fast',
        '-crf', '23',
        '-c:a', 'copy',
        '-movflags', '+faststart',
        '-y',
        outputFile,
      ]);

      const outputData = await readVFS(outputFile);
      return new Blob([outputData.buffer], { type: 'video/mp4' });
    } finally {
      await cleanupVFS(tempFiles);
    }
  } finally {
    await cleanupVFS(tempFiles);
  }
}

/**
 * Replace or overlay the audio track of a video.
 *
 * @param {File|Blob|Uint8Array} videoData - Input video
 * @param {File|Blob|Uint8Array} audioData - Audio to overlay (WAV, MP3, WebM, OGG)
 * @param {object} [options]
 * @param {boolean} [options.keepOriginal=false] - Mix with original audio
 * @param {number} [options.audioVolume=1.0] - Volume of the new audio (0–2)
 * @param {number} [options.originalVolume=0.1] - Volume of original audio when mixing
 * @param {function(number): void} [onProgress]
 * @returns {Promise<Blob>} Video with new audio as MP4 Blob
 */
export async function overlayAudio(videoData, audioData, options = {}, onProgress) {
  ensureLoaded();

  const inputVideo = 'input_audio.mp4';
  const inputAudio = 'input_audio_track.wav';
  const outputFile = 'output_audio.mp4';
  const tempFiles = [inputVideo, inputAudio, outputFile];

  const keepOriginal = options.keepOriginal ?? false;
  const audioVol = options.audioVolume ?? 1.0;
  const origVol = options.originalVolume ?? 0.1;

  try {
    if (onProgress) {
      ffmpeg.on('progress', ({ progress }) => onProgress(progress));
    }

    await writeVFS(inputVideo, videoData);
    await writeVFS(inputAudio, audioData);

    let audioArgs;
    if (keepOriginal) {
      // Mix original audio with new narration
      audioArgs = [
        '-filter_complex',
        `[0:a]volume=${origVol}[a0];[1:a]volume=${audioVol}[a1];[a0][a1]amix=inputs=2:duration=first:dropout_transition=2[aout]`,
        '-map', '0:v',
        '-map', '[aout]',
      ];
    } else {
      // Replace audio entirely
      audioArgs = [
        '-map', '0:v',
        '-map', '1:a',
        '-shortest',
      ];
    }

    await ffmpeg.exec([
      '-i', inputVideo,
      '-i', inputAudio,
      ...audioArgs,
      '-c:v', 'copy',
      '-c:a', 'aac',
      '-b:a', '192k',
      '-movflags', '+faststart',
      '-y',
      outputFile,
    ]);

    const outputData = await readVFS(outputFile);
    return new Blob([outputData.buffer], { type: 'video/mp4' });
  } finally {
    await cleanupVFS(tempFiles);
  }
}

/**
 * Full export pipeline: vertical conversion + subtitles + audio overlay.
 * Runs operations sequentially to minimize memory usage.
 *
 * @param {File|Blob|Uint8Array} videoData - Original video
 * @param {object} options
 * @param {File|Blob|Uint8Array} [options.audioData] - Narration audio
 * @param {string} [options.srtContent] - SRT subtitle content
 * @param {boolean} [options.convertVertical=false] - Convert to 9:16
 * @param {'crop'|'pad'|'blur'} [options.verticalMode='crop']
 * @param {boolean} [options.burnSubtitles=false] - Burn subtitles into video
 * @param {object} [options.subtitleStyle] - Subtitle styling
 * @param {boolean} [options.replaceAudio=false] - Replace audio track
 * @param {boolean} [options.keepOriginalAudio=false] - Mix with original audio
 * @param {number} [options.narrationVolume=1.0]
 * @param {number} [options.originalVolume=0.1]
 * @param {function(string, number): void} [options.onProgress] - (stage, progress 0–1)
 * @returns {Promise<Blob>} Final processed video as MP4 Blob
 */
export async function exportFinalVideo(videoData, options = {}) {
  ensureLoaded();

  let currentVideo = videoData;
  const stages = [];

  if (options.convertVertical) stages.push('vertical');
  if (options.burnSubtitles && options.srtContent) stages.push('subtitles');
  if (options.replaceAudio && options.audioData) stages.push('audio');

  // If no processing is requested, just pass the video through
  if (stages.length === 0) {
    if (videoData instanceof Blob) return videoData;
    return new Blob([videoData], { type: 'video/mp4' });
  }

  const totalStages = stages.length;
  let completedStages = 0;

  const stageProgress = (stage) => (progress) => {
    const overall = (completedStages + progress) / totalStages;
    options.onProgress?.(stage, overall);
  };

  try {
    // Stage 1: Vertical conversion
    if (stages.includes('vertical')) {
      options.onProgress?.('vertical', completedStages / totalStages);
      currentVideo = await convertToVertical(
        currentVideo,
        options.verticalMode || 'crop',
        stageProgress('vertical')
      );
      completedStages++;
    }

    // Stage 2: Burn subtitles
    if (stages.includes('subtitles')) {
      options.onProgress?.('subtitles', completedStages / totalStages);
      currentVideo = await addSubtitles(
        currentVideo,
        options.srtContent,
        options.subtitleStyle || {},
        stageProgress('subtitles')
      );
      completedStages++;
    }

    // Stage 3: Audio overlay
    if (stages.includes('audio')) {
      options.onProgress?.('audio', completedStages / totalStages);
      currentVideo = await overlayAudio(
        currentVideo,
        options.audioData,
        {
          keepOriginal: options.keepOriginalAudio ?? false,
          audioVolume: options.narrationVolume ?? 1.0,
          originalVolume: options.originalVolume ?? 0.1,
        },
        stageProgress('audio')
      );
      completedStages++;
    }

    options.onProgress?.('done', 1);
    return currentVideo;
  } catch (err) {
    throw new Error(`Error en el pipeline de exportación (etapa: ${stages[completedStages] || 'unknown'}): ${err.message}`);
  }
}

/**
 * Get video metadata (duration, resolution) from a video file.
 *
 * @param {File|Blob|Uint8Array} videoData
 * @returns {Promise<{duration: number, width: number, height: number}>}
 */
export async function getVideoInfo(videoData) {
  // Use an HTML5 video element for metadata extraction (faster than FFmpeg)
  return new Promise((resolve, reject) => {
    const video = document.createElement('video');
    video.preload = 'metadata';

    const blob = videoData instanceof Blob
      ? videoData
      : new Blob([videoData], { type: 'video/mp4' });

    const url = URL.createObjectURL(blob);

    video.onloadedmetadata = () => {
      const info = {
        duration: video.duration,
        width: video.videoWidth,
        height: video.videoHeight,
      };
      URL.revokeObjectURL(url);
      video.remove();
      resolve(info);
    };

    video.onerror = () => {
      URL.revokeObjectURL(url);
      video.remove();
      reject(new Error('No se pudo leer la información del video.'));
    };

    // Timeout after 10 seconds
    setTimeout(() => {
      URL.revokeObjectURL(url);
      video.remove();
      reject(new Error('Tiempo de espera agotado al leer el video.'));
    }, 10000);

    video.src = url;
  });
}

// ─── Internal Helpers ─────────────────────────────────────────────

/**
 * Parse SRT content into timed segments for drawtext fallback.
 * @param {string} srtContent
 * @returns {Array<{start: number, end: number, text: string}>}
 */
function parseSRTtoSegments(srtContent) {
  const segments = [];
  const blocks = srtContent.trim().split(/\n\s*\n/);

  for (const block of blocks) {
    const lines = block.trim().split('\n');
    if (lines.length < 3) continue;

    const timeLine = lines[1];
    const timeMatch = timeLine.match(
      /(\d{2}):(\d{2}):(\d{2})[,.](\d{3})\s*-->\s*(\d{2}):(\d{2}):(\d{2})[,.](\d{3})/
    );
    if (!timeMatch) continue;

    const start = parseInt(timeMatch[1]) * 3600 +
                  parseInt(timeMatch[2]) * 60 +
                  parseInt(timeMatch[3]) +
                  parseInt(timeMatch[4]) / 1000;

    const end = parseInt(timeMatch[5]) * 3600 +
                parseInt(timeMatch[6]) * 60 +
                parseInt(timeMatch[7]) +
                parseInt(timeMatch[8]) / 1000;

    const text = lines.slice(2).join('\n').trim();

    if (text) {
      segments.push({ start, end, text });
    }
  }

  return segments;
}
