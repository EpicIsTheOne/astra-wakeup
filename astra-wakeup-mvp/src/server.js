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

const missions = [
  'Ship one meaningful thing before noon.',
  'Do 20 focused minutes with zero distractions.',
  'Inbox to zero on the top 5 messages.',
  'Touch the hard task first, not the easy dopamine one.'
];

const profileModes = {
  gentle: ['flirty'],
  normal: ['flirty', 'gremlin'],
  bully: ['savage', 'gremlin'],
  nuclear: ['savage']
};

const punishmentIntervalsByProfile = {
  gentle: [10],
  normal: [6, 10],
  bully: [3, 6, 10],
  nuclear: [2, 4, 7, 10]
};

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

function loadPersonalMemory() {
  const p = path.resolve('data/personal-memory.json');
  try {
    return JSON.parse(fs.readFileSync(p, 'utf8'));
  } catch {
    return { notes: [] };
  }
}

function savePersonalMemory(mem) {
  const p = path.resolve('data/personal-memory.json');
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, JSON.stringify(mem, null, 2));
}

function pickMission() {
  return missions[Math.floor(Math.random() * missions.length)];
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

  const profile = state.wakeProfile || 'bully';
  const ladder = punishmentIntervalsByProfile[profile] || [cfg.punishmentMinutes];
  const idx = Math.min(state.pendingWakeCount || 0, ladder.length - 1);
  const min = ladder[idx];

  punishmentTimer = setTimeout(async () => {
    if (!state.pendingWake) return;
    try {
      await fireWakeup('punishment');
      console.log('[wake] punishment blast delivered');
    } catch (err) {
      console.error('[wake] punishment failed', err.message || err);
    }
  }, min * 60 * 1000);
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
  state.pendingWakeCount = reason === 'punishment' ? (state.pendingWakeCount || 0) + 1 : 0;
  saveState(state);

  // Only schedule one follow-up blast; avoid infinite punishment loops.
  if (reason !== 'punishment') schedulePunishment();

  return { ok: true, line, mode, messageId: msg.id };
}

app.get('/api/health', (_req, res) => {
  res.json({ ok: true, service: 'astra-wakeup-mvp', time: new Date().toISOString() });
});

app.get('/api/state', (_req, res) => res.json(state));

async function astraLineHandler(req, res) {
  const punishment = Boolean(req.body?.punishment);
  const wakeProfile = String(req.body?.wakeProfile || state.wakeProfile || 'bully');
  const modes = profileModes[wakeProfile] || profileModes.bully;
  const aiLine = await getDynamicWakeLine({ punishment });
  const mode = punishment ? 'savage' : modes[Math.floor(Math.random() * modes.length)];
  const line = aiLine || buildWakeLine(cfg.wakeUserName, mode);
  const mission = pickMission();
  res.json({ ok: true, line, mode, wakeProfile, mission, source: aiLine ? 'openai' : 'local' });
}

app.post('/api/wakeup/line', astraLineHandler);
app.post('/api/astra/line', astraLineHandler);

async function generateAstraReply(text) {
  if (!cfg.openaiApiKey) return "Nope. You're awake now, no excuses.";
  const mem = loadPersonalMemory();
  const memContext = (mem.notes || []).slice(-8).map((n) => `- ${n}`).join('\n');
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
            content: `You are Astra, a sassy assistant. Respond in 1-2 short sentences, playful teasing but helpful.\nKnown user memory:\n${memContext || '- none yet'}`
          },
          { role: 'user', content: text }
        ]
      })
    });
    const json = await chat.json();
    return json?.choices?.[0]?.message?.content?.trim() || "Cute excuse. Up. Now.";
  } catch {
    return "Nice try. Still waking up. Move.";
  }
}

async function astraWakeRespondHandler(req, res) {
  const text = String(req.body?.text || '').slice(0, 280);
  if (!text) return res.status(400).json({ ok: false, error: 'Missing text' });

  const reply = await generateAstraReply(`User is waking up and said: ${text}`);
  res.json({ ok: true, reply });
}

async function astraRespondHandler(req, res) {
  const text = String(req.body?.text || '').slice(0, 500);
  if (!text) return res.status(400).json({ ok: false, error: 'Missing text' });
  const reply = await generateAstraReply(text);
  res.json({ ok: true, reply });
}

app.post('/api/wakeup/respond', astraWakeRespondHandler);
app.post('/api/astra/wake-respond', astraWakeRespondHandler);

app.post('/api/chat/respond', astraRespondHandler);
app.post('/api/astra/respond', astraRespondHandler);
app.get('/api/astra/health', (_req, res) => {
  res.json({ ok: true, service: 'astra', time: new Date().toISOString() });
});

app.get('/api/astra/profile', (_req, res) => {
  res.json({ ok: true, wakeProfile: state.wakeProfile || 'bully' });
});

app.post('/api/astra/profile', (req, res) => {
  const allowed = ['gentle', 'normal', 'bully', 'nuclear'];
  const wakeProfile = String(req.body?.wakeProfile || '').toLowerCase();
  if (!allowed.includes(wakeProfile)) {
    return res.status(400).json({ ok: false, error: 'Invalid wakeProfile' });
  }
  state.wakeProfile = wakeProfile;
  saveState(state);
  res.json({ ok: true, wakeProfile });
});

app.post('/api/astra/memory', (req, res) => {
  const text = String(req.body?.text || '').trim().slice(0, 220);
  if (!text) return res.status(400).json({ ok: false, error: 'Missing text' });
  const mem = loadPersonalMemory();
  mem.notes = mem.notes || [];
  mem.notes.push(text);
  mem.notes = mem.notes.slice(-100);
  savePersonalMemory(mem);
  res.json({ ok: true, saved: text });
});

app.get('/api/astra/memory', (_req, res) => {
  const mem = loadPersonalMemory();
  res.json({ ok: true, notes: mem.notes || [] });
});

app.delete('/api/astra/memory', (req, res) => {
  const idx = Number(req.body?.index);
  const mem = loadPersonalMemory();
  mem.notes = mem.notes || [];

  if (Number.isInteger(idx) && idx >= 0 && idx < mem.notes.length) {
    const removed = mem.notes.splice(idx, 1);
    savePersonalMemory(mem);
    return res.json({ ok: true, removed: removed[0], notes: mem.notes });
  }

  if (req.body?.all === true) {
    mem.notes = [];
    savePersonalMemory(mem);
    return res.json({ ok: true, removed: 'all', notes: [] });
  }

  return res.status(400).json({ ok: false, error: 'Provide valid index or all=true' });
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
  state.pendingWakeCount = 0;
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
