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

app.get('/api/state', (_req, res) => res.json(state));

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
