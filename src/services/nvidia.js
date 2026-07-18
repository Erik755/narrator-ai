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
async function extractFrames(videoFile, maxFrames = 8) {
  return new Promise((resolve, reject) => {
    const video = document.createElement('video');
    const url = URL.createObjectURL(videoFile);
    video.src = url;
    video.muted = true;
    
    video.onloadedmetadata = async () => {
      const duration = video.duration;
      const interval = duration / maxFrames;
      const frames = [];
      const canvas = document.createElement('canvas');
      const ctx = canvas.getContext('2d');
      
      // Target resolution (reduce size to save tokens/bandwidth)
      const MAX_DIM = 512;
      let width = video.videoWidth;
      let height = video.videoHeight;
      if (width > height) {
        if (width > MAX_DIM) { height *= MAX_DIM / width; width = MAX_DIM; }
      } else {
        if (height > MAX_DIM) { width *= MAX_DIM / height; height = MAX_DIM; }
      }
      canvas.width = Math.floor(width);
      canvas.height = Math.floor(height);

      for (let i = 0; i < maxFrames; i++) {
        const time = Math.min(i * interval + (interval / 2), duration - 0.1);
        video.currentTime = time;
        
        await new Promise(r => {
          video.onseeked = () => {
            ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
            // Get base64 without prefix for OpenAI payload format
            const dataUrl = canvas.toDataURL('image/jpeg', 0.7);
            frames.push(dataUrl);
            r();
          };
        });
      }
      URL.revokeObjectURL(url);
      resolve(frames);
    };
    video.onerror = reject;
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

  const response = await fetch('https://integrate.api.nvidia.com/v1/chat/completions', {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${apiKey}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      model: 'nvidia/cosmos3-nano-reasoner',
      messages: [
        {
          role: 'user',
          content: content
        }
      ],
      temperature: 0.7,
      max_tokens: 2048,
    })
  });

  if (!response.ok) {
    const err = await response.text();
    throw new Error(`NVIDIA API Error: ${response.status} ${err}`);
  }

  const data = await response.json();
  const text = data.choices[0].message.content;
  
  onProgress?.('done', 'Análisis completo');
  
  try {
    return JSON.parse(text);
  } catch (e) {
    const jsonMatch = text.match(/```json\n?([\s\S]*?)\n?```/);
    if (jsonMatch) return JSON.parse(jsonMatch[1]);
    
    // Attempt fallback parsing if the model didn't return perfect JSON
    const fallbackMatch = text.match(/\{[\s\S]*\}/);
    if (fallbackMatch) return JSON.parse(fallbackMatch[0]);
    
    throw new Error('Failed to parse AI response as JSON');
  }
}

export async function validateApiKey(key) {
  try {
    const response = await fetch('https://integrate.api.nvidia.com/v1/chat/completions', {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${key}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        model: 'nvidia/cosmos3-nano-reasoner',
        messages: [{ role: 'user', content: 'Say "ok"' }],
        max_tokens: 10
      })
    });
    return response.ok;
  } catch (e) {
    return false;
  }
}
