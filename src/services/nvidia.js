/**
 * nvidia.js - NVIDIA NIM Integration Service
 * 
 * Provides video analysis via nvidia/cosmos3-nano-reasoner
 * 
 * @module services/nvidia
 */

import { getAnalysisPrompt } from '../utils/prompts.js';

let apiKey = null;

export function initNvidia(key) {
  apiKey = key;
}

export function isInitialized() {
  return apiKey !== null;
}

/**
 * Extract frames from a video file using native HTML5 Video/Canvas
 * @param {File} videoFile 
 * @param {number} maxFrames 
 * @returns {Promise<string[]>} Array of base64 JPEG images
 */
async function extractFrames(videoFile, maxFrames = 4) {
  return new Promise((resolve, reject) => {
    const video = document.createElement('video');
    const url = URL.createObjectURL(videoFile);
    video.src = url;
    video.muted = true;
    
    video.onloadedmetadata = async () => {
      const duration = video.duration;
      // We will create a 2x2 collage (4 frames)
      const interval = duration / 4;
      
      const MAX_DIM = 512;
      let width = video.videoWidth;
      let height = video.videoHeight;
      if (width > height) {
        if (width > MAX_DIM) { height *= MAX_DIM / width; width = MAX_DIM; }
      } else {
        if (height > MAX_DIM) { width *= MAX_DIM / height; height = MAX_DIM; }
      }
      
      const frameWidth = Math.floor(width);
      const frameHeight = Math.floor(height);
      
      const canvas = document.createElement('canvas');
      // 2x2 grid
      canvas.width = frameWidth * 2;
      canvas.height = frameHeight * 2;
      const ctx = canvas.getContext('2d');
      
      const positions = [
        [0, 0],
        [frameWidth, 0],
        [0, frameHeight],
        [frameWidth, frameHeight]
      ];

      for (let i = 0; i < 4; i++) {
        const time = Math.min(i * interval + (interval / 2), duration - 0.1);
        video.currentTime = time;
        
        await new Promise(r => {
          video.onseeked = () => {
            const [x, y] = positions[i];
            ctx.drawImage(video, x, y, frameWidth, frameHeight);
            r();
          };
        });
      }
      URL.revokeObjectURL(url);
      
      // Return a single image containing the 4-frame collage
      const dataUrl = canvas.toDataURL('image/jpeg', 0.8);
      resolve([dataUrl]);
    };
    video.onerror = () => reject(new Error('El archivo no es un video válido o está corrupto.'));
  });
}

/**
 * Analyze a video file and generate narration script using NVIDIA NIM
 */
export async function analyzeVideo(videoFile, tone, language, onProgress) {
  if (!apiKey) throw new Error('NVIDIA API key not initialized.');
  
  onProgress?.('uploading', 'Extrayendo cuadros del video...');
  const frames = await extractFrames(videoFile, 8);
  
  onProgress?.('analyzing', 'Analizando contenido con NVIDIA NIM...');
  const prompt = getAnalysisPrompt(tone, language);
  
  // Format for OpenAI-compatible vision endpoint
  const content = [
    { type: "text", text: prompt }
  ];
  
  for (const frame of frames) {
    content.push({
      type: "image_url",
      image_url: { url: frame }
    });
  }

  const response = await fetch('/api/nvidia', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      apiKey,
      model: 'meta/llama-3.2-11b-vision-instruct',
      messages: [
        {
          role: 'user',
          content: content
        }
      ],
      temperature: 0.2, // Lower temp for more reliable JSON
      max_tokens: 1024,
    })
  });

  if (!response.ok) {
    const err = await response.text();
    throw new Error(`NVIDIA API Error: ${response.status} ${err}`);
  }

  const data = await response.json();
  let text = data.choices[0].message.content;
  
  onProgress?.('done', 'Análisis completo');
  
  // Helper to aggressively clean bad JSON from LLMs
  const sanitizeJSON = (str) => {
    // Remove markdown blocks
    str = str.replace(/```json\n?/g, '').replace(/```\n?/g, '');
    // Fix unquoted hashtags in arrays (e.g., ["#hashtag1", #hashtag2] -> ["#hashtag1", "#hashtag2"])
    str = str.replace(/,\s*(#[a-zA-Z0-9_]+)/g, ', "$1"');
    str = str.replace(/\[\s*(#[a-zA-Z0-9_]+)/g, '["$1"');
    // Fix trailing commas
    str = str.replace(/,\s*([\]}])/g, '$1');
    return str.trim();
  };

  try {
    text = sanitizeJSON(text);
    const jsonMatch = text.match(/\{[\s\S]*\}/);
    if (jsonMatch) {
      return JSON.parse(jsonMatch[0]);
    }
    return JSON.parse(text);
  } catch (e) {
    console.error("Original text:", data.choices[0].message.content);
    console.error("Sanitized text:", text);
    throw new Error('El modelo de IA no generó un formato JSON válido.');
  }
}

export async function validateApiKey(key) {
  try {
    const response = await fetch('/api/nvidia', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        apiKey: key,
        model: 'meta/llama-3.3-70b-instruct',
        messages: [{ role: 'user', content: 'Say "ok"' }],
        max_tokens: 10
      })
    });
    return response.ok;
  } catch (e) {
    return false;
  }
}
