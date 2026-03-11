import fs from 'node:fs';
import path from 'node:path';
import express from 'express';
import cron from 'node-cron';

import { cfg } from './config.js';
import { buildWakeLine } from './persona.js';
import { loadState, saveState } from './store.js';
import { openAiTts } from './tts/openai.js';
import { elevenLabsTts } from './tts/elevenlabs.js';
import { maybeMixWithSfx } from './audio.js';
import { sendDiscordDmAudio } from './discord.js';

const app = express();
app.use(express.json());
app.use(express.static('public'));

let state = loadState();
let snoozeTimer = null;
let punishmentTimer = null;

async function getDynamicWakeLine({ punishment = false } = {}) {
  if (!cfg.openaiApiKey) return null;

  const system = "You are Astra, a sassy anime-girl assistant waking up user Epic. Keep it short: 4-12 words. No emojis.";
  const user = punishment
    ? "Generate an aggressive wake-up line. Use words like 'wake up', 'now', 'dumbass' sometimes."
    : "Generate a playful wake-up line for morning.";

  try {
    const res = await fetch('https://api.openai.com/v1/chat/completions', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${cfg.openaiApiKey}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        model: 'gpt-4.1-mini',
        temperature: 0.9,
        messages: [
          { role: 'system', content: system },
          { role: 'user', content: user }
        ]
      })
    });

    if (!res.ok) return null;
    const json = await res.json();
    return json?.choices?.[0]?.message?.content?.trim() || null;
  } catch {
    return null;
  }
}

async function renderTts(text) {
  fs.mkdirSync('data', { recursive: true });
  const outPath = path.resolve('data/out-voice.mp3');
  if (cfg.ttsProvider === 'elevenlabs') {
    await elevenLabsTts({ text, outPath, apiKey: cfg.elevenlabsApiKey, voiceId: cfg.elevenlabsVoiceId });
  } else {
    await openAiTts({
      apiKey: cfg.openaiApiKey,
      model: cfg.openaiTtsModel,
      voice: cfg.openaiTtsVoice,
      text,
      outPath
    });
  }
  return maybeMixWithSfx(outPath);
}

function schedulePunishment() {
  if (!cfg.punishmentEnabled) return;
  if (punishmentTimer) clearTimeout(punishmentTimer);

  punishmentTimer = setTimeout(async () => {
    if (!state.pendingWake) return;
    try {
      await fireWakeup('punishment');
      console.log('[wake] punishment blast delivered');
    } catch (err) {
      console.error('[wake] punishment failed', err.message || err);
    }
  }, cfg.punishmentMinutes * 60 * 1000);
}

async function fireWakeup(reason = 'scheduled') {
  const mode = ['flirty', 'savage', 'gremlin'][Math.floor(Math.random() * 3)];
  const line = buildWakeLine(cfg.wakeUserName, mode);
  const clip = await renderTts(line);

  const caption = `Astra wake-up (${reason})\\n${line}`;
  const msg = await sendDiscordDmAudio({
    token: cfg.discordBotToken,
    userId: cfg.discordUserId,
    filePath: clip,
    caption
  });

  state.lastWakeAt = new Date().toISOString();
  state.pendingWake = true;
  saveState(state);

  // Only schedule one follow-up blast; avoid infinite punishment loops.
  if (reason !== 'punishment') schedulePunishment();

  return { ok: true, line, mode, messageId: msg.id };
}

app.get('/api/health', (_req, res) => {
  res.json({ ok: true, service: 'astra-wakeup-mvp', time: new Date().toISOString() });
});

app.get('/api/state', (_req, res) => res.json(state));

app.post('/api/wakeup/line', async (req, res) => {
  const punishment = Boolean(req.body?.punishment);
  const aiLine = await getDynamicWakeLine({ punishment });
  const mode = punishment ? 'savage' : ['flirty', 'savage', 'gremlin'][Math.floor(Math.random() * 3)];
  const line = aiLine || buildWakeLine(cfg.wakeUserName, mode);
  res.json({ ok: true, line, mode, source: aiLine ? 'openai' : 'local' });
});

app.post('/api/wakeup/respond', async (req, res) => {
  const text = String(req.body?.text || '').slice(0, 280);
  if (!text) return res.status(400).json({ ok: false, error: 'Missing text' });

  if (!cfg.openaiApiKey) {
    return res.json({ ok: true, reply: "Nope. You're awake now, no excuses." });
  }

  try {
    const chat = await fetch('https://api.openai.com/v1/chat/completions', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${cfg.openaiApiKey}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        model: 'gpt-4.1-mini',
        temperature: 0.9,
        messages: [
          {
            role: 'system',
            content: "You are Astra, a sassy wake-up assistant. User just replied while half-asleep. Respond in 1 short sentence, teasing but useful, and push them to get up."
          },
          { role: 'user', content: text }
        ]
      })
    });

    const json = await chat.json();
    const reply = json?.choices?.[0]?.message?.content?.trim() || "Cute excuse. Up. Now.";
    res.json({ ok: true, reply });
  } catch {
    res.json({ ok: true, reply: "Nice try. Still waking up. Move." });
  }
});

app.post('/api/wakeup/fire', async (_req, res) => {
  try {
    const result = await fireWakeup('manual');
    res.json(result);
  } catch (err) {
    res.status(500).json({ ok: false, error: String(err.message || err) });
  }
});

app.post('/api/wakeup/ack', (_req, res) => {
  state.streak += 1;
  state.lastAckAt = new Date().toISOString();
  state.pendingWake = false;
  if (punishmentTimer) clearTimeout(punishmentTimer);
  saveState(state);
  res.json({ ok: true, streak: state.streak });
});

app.post('/api/wakeup/snooze', (req, res) => {
  const minutes = Math.max(1, Number(req.body?.minutes || 10));
  if (snoozeTimer) clearTimeout(snoozeTimer);

  snoozeTimer = setTimeout(async () => {
    try { await fireWakeup(`snooze-${minutes}m`); } catch {}
  }, minutes * 60 * 1000);

  res.json({ ok: true, snoozedForMin: minutes });
});

cron.schedule(cfg.wakeCron, async () => {
  try {
    await fireWakeup('scheduled');
    console.log('[wake] delivered');
  } catch (err) {
    console.error('[wake] failed', err.message || err);
  }
}, { timezone: cfg.tz });

app.listen(cfg.port, () => {
  console.log(`Astra Wake-Up MVP running on :${cfg.port}`);
  console.log(`Schedule: "${cfg.wakeCron}" @ ${cfg.tz}`);
});
