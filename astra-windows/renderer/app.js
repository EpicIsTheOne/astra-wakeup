const DEFAULT_BASE_URL = 'https://techexplore.us';
const DEFAULT_AGENT = 'orchestrator';
const APP_VERSION = '0.1.0';

const state = {
  baseUrl: localStorage.getItem('astra.baseUrl') || DEFAULT_BASE_URL,
  agent: localStorage.getItem('astra.agent') || DEFAULT_AGENT,
  connected: false,
  latestRelease: null,
  includePrereleases: localStorage.getItem('astra.includePrereleases') === 'true',
  currentVersion: APP_VERSION,
  appPlatform: 'unknown',
  geminiConfig: null,
  call: {
    active: false,
    status: 'idle',
    sessionId: null,
    socket: null,
    mediaStream: null,
    audioContext: null,
    inputAudioContext: null,
    processor: null,
    sourceNode: null,
    assistantAudioQueue: [],
    playing: false,
    lastAssistantText: '',
    lastUserText: ''
  },
  chatMessages: [
    { role: 'assistant', text: "Desktop Astra online. Try not to embarrass yourself in front of the expensive UI." }
  ],
  memory: [],
  organizer: { reminders: [], tasks: [] },
  calendar: [],
  analytics: null,
  recognition: null,
  listening: false,
  activeView: localStorage.getItem('astra.activeView') || 'chat',
  busy: {
    connect: false,
    chat: false,
    memory: false,
    organizer: false,
    calendar: false,
    analytics: false,
    updates: false,
    call: false
  }
};

const isPanel = Boolean(window.astraDesktop?.isPanel || location.hash === '#panel');
const root = document.getElementById('app');

function commandCenterBase(baseUrl) {
  const trimmed = String(baseUrl || '').trim().replace(/\/$/, '');
  if (!trimmed) return '';
  if (trimmed.includes('/commandcenter')) return trimmed.split('/commandcenter')[0] + '/commandcenter';
  if (trimmed.includes('/missioncontrol')) return trimmed.split('/missioncontrol')[0] + '/commandcenter';
  if (trimmed.includes('/aichat')) return trimmed.split('/aichat')[0] + '/commandcenter';
  if (trimmed.includes('/api/')) return trimmed.split('/api/')[0] + '/commandcenter';
  return `${trimmed}/commandcenter`;
}

function astraApiBase(baseUrl) {
  const trimmed = String(baseUrl || '').trim().replace(/\/$/, '');
  if (!trimmed) return '';
  if (trimmed.endsWith('/api/astra')) return trimmed;
  if (trimmed.includes('/api/')) return trimmed.split('/api/')[0] + '/api/astra';
  return `${trimmed}/api/astra`;
}

function wsUrl(baseUrl) {
  const cc = commandCenterBase(baseUrl);
  if (cc.startsWith('https://')) return cc.replace('https://', 'wss://') + '/ws';
  if (cc.startsWith('http://')) return cc.replace('http://', 'ws://') + '/ws';
  return cc + '/ws';
}

function callApiBase(baseUrl) {
  return commandCenterBase(baseUrl);
}

async function getJson(url, init = {}) {
  const response = await fetch(url, {
    ...init,
    headers: {
      Accept: 'application/json',
      ...(init.body ? { 'Content-Type': 'application/json' } : {}),
      ...(init.headers || {})
    }
  });
  const text = await response.text();
  let json = {};
  try { json = text ? JSON.parse(text) : {}; } catch {}
  if (!response.ok) throw new Error(json.error || `HTTP ${response.status}`);
  return json;
}

function setBusy(key, value) {
  state.busy[key] = value;
  render();
}

function saveSettings() {
  localStorage.setItem('astra.baseUrl', state.baseUrl);
  localStorage.setItem('astra.agent', state.agent);
  localStorage.setItem('astra.includePrereleases', String(state.includePrereleases));
}

function setActiveView(view) {
  state.activeView = view;
  localStorage.setItem('astra.activeView', view);
  render();
}

function toast(message) {
  const el = document.createElement('div');
  el.className = 'toast';
  el.textContent = message;
  document.body.appendChild(el);
  setTimeout(() => el.remove(), 2600);
}

function speak(text) {
  if (!('speechSynthesis' in window) || !text) return;
  window.speechSynthesis.cancel();
  const utter = new SpeechSynthesisUtterance(text);
  utter.rate = 1;
  utter.pitch = 1.05;
  utter.volume = 1;
  speechSynthesis.speak(utter);
}

function formatDateTime(ms) {
  if (!ms) return '-';
  return new Date(ms).toLocaleString();
}

function importanceLabel(level) {
  return ['Normal', 'Important', 'Critical'][Math.max(0, Math.min(2, Number(level || 1) - 1))];
}

function annoyanceLabel(level) {
  return ['Gentle', 'Pushy', 'Chaotic'][Math.max(0, Math.min(2, Number(level || 1) - 1))];
}

function timeUntil(ms) {
  if (!ms) return 'unknown';
  const diff = ms - Date.now();
  const abs = Math.abs(diff);
  const mins = Math.round(abs / 60000);
  if (mins < 1) return diff >= 0 ? 'now' : 'just passed';
  if (mins < 60) return diff >= 0 ? `in ${mins}m` : `${mins}m ago`;
  const hours = Math.round(mins / 60);
  if (hours < 48) return diff >= 0 ? `in ${hours}h` : `${hours}h ago`;
  const days = Math.round(hours / 24);
  return diff >= 0 ? `in ${days}d` : `${days}d ago`;
}

function normalizeVersion(version) {
  return String(version || '')
    .trim()
    .replace(/^v/i, '')
    .split(/[+-]/)[0]
    .split('.')
    .map((part) => Number.parseInt(part, 10) || 0);
}

function compareVersions(a, b) {
  const left = normalizeVersion(a);
  const right = normalizeVersion(b);
  const len = Math.max(left.length, right.length);
  for (let i = 0; i < len; i += 1) {
    const diff = (left[i] || 0) - (right[i] || 0);
    if (diff !== 0) return diff;
  }
  return 0;
}

function releaseWindowsAsset(release) {
  const assets = release?.assets || [];
  return assets.find((asset) => /Astra-Windows-.*x64\.exe$/i.test(asset.name || '')) || null;
}

function arrayBufferToBase64(buffer) {
  let binary = '';
  const bytes = new Uint8Array(buffer);
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunk));
  }
  return btoa(binary);
}

function base64ToArrayBuffer(base64) {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  return bytes.buffer;
}

function pcm16ToFloat32(pcm16ArrayBuffer) {
  const source = new Int16Array(pcm16ArrayBuffer);
  const out = new Float32Array(source.length);
  for (let i = 0; i < source.length; i += 1) out[i] = Math.max(-1, Math.min(1, source[i] / 32768));
  return out;
}

function floatTo16BitPCM(float32Array) {
  const out = new Int16Array(float32Array.length);
  for (let i = 0; i < float32Array.length; i += 1) {
    const sample = Math.max(-1, Math.min(1, float32Array[i] || 0));
    out[i] = sample < 0 ? sample * 0x8000 : sample * 0x7fff;
  }
  return out.buffer;
}

function parseSampleRateFromMimeType(mimeType) {
  const value = String(mimeType || '').toLowerCase().trim();
  const match = value.match(/rate=(\d+)/);
  const rate = match ? Number.parseInt(match[1], 10) : NaN;
  if (!Number.isFinite(rate)) return 24000;
  return Math.max(8000, Math.min(48000, rate));
}

function updateStatusText() {
  if (!state.latestRelease) return 'No release details loaded yet.';
  const asset = releaseWindowsAsset(state.latestRelease);
  const newer = compareVersions(state.latestRelease.tag_name, state.currentVersion) > 0;
  const channelLabel = state.includePrereleases ? 'stable + prerelease' : 'stable only';
  if (!asset) return `Release channel ${channelLabel} found no Windows installer asset yet.`;
  return newer
    ? `Update available on ${channelLabel}: ${state.latestRelease.tag_name} is newer than ${state.currentVersion}.`
    : `You are on ${state.currentVersion}. Latest ${channelLabel} Windows asset is ${state.latestRelease.tag_name}.`;
}

function reminderCount() {
  return (state.organizer.reminders || []).filter((item) => item.enabled).length;
}

function pendingTaskCount() {
  return (state.organizer.tasks || []).filter((item) => !item.done).length;
}

function nextReminder() {
  return (state.organizer.reminders || []).filter((item) => item.enabled && item.scheduledTimeMillis >= Date.now()).sort((a, b) => a.scheduledTimeMillis - b.scheduledTimeMillis)[0] || null;
}

async function connectBridge() {
  setBusy('connect', true);
  try {
    const status = await getJson(`${commandCenterBase(state.baseUrl)}/api/status`);
    state.connected = Boolean(status?.bridge?.connected);
    if (!state.connected) throw new Error('Bridge reachable, but not connected');
    toast('Connected through Command Center bridge');
  } catch (error) {
    state.connected = false;
    toast(`Connection failed: ${error.message}`);
  } finally {
    setBusy('connect', false);
  }
}

async function sendChatMessage(text) {
  const clean = String(text || '').trim();
  if (!clean || state.busy.chat) return;
  state.chatMessages.push({ role: 'user', text: clean });
  setBusy('chat', true);

  try {
    const socket = new WebSocket(wsUrl(state.baseUrl));
    const reply = await new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        socket.close();
        reject(new Error('bridge reply timeout'));
      }, 45000);

      socket.onopen = async () => {
        try {
          await getJson(`${commandCenterBase(state.baseUrl)}/api/chat/direct`, {
            method: 'POST',
            body: JSON.stringify({ text: clean, agent: state.agent })
          });
        } catch (error) {
          clearTimeout(timer);
          socket.close();
          reject(error);
        }
      };

      socket.onmessage = (event) => {
        let json = null;
        try { json = JSON.parse(event.data); } catch { return; }
        const data = json?.data || {};
        if (data.source !== 'direct-chat' || !data.chat) return;
        if (data.agent && data.agent !== state.agent) return;
        if (json.type === 'agent:responding') {
          clearTimeout(timer);
          socket.close();
          resolve(data.message || '(empty reply)');
        }
        if (json.type === 'agent:error') {
          clearTimeout(timer);
          socket.close();
          reject(new Error(data.message || 'bridge agent error'));
        }
      };

      socket.onerror = () => {
        clearTimeout(timer);
        reject(new Error('websocket error'));
      };
    });

    state.chatMessages.push({ role: 'assistant', text: reply });
    speak(reply);
  } catch (error) {
    state.chatMessages.push({ role: 'assistant', text: `Connection tantrum: ${error.message}` });
  } finally {
    setBusy('chat', false);
  }
}

async function loadMemory() {
  setBusy('memory', true);
  try {
    const json = await getJson(`${astraApiBase(state.baseUrl)}/memory`);
    state.memory = json.notes || [];
  } catch (error) {
    toast(`Memory load failed: ${error.message}`);
  } finally {
    setBusy('memory', false);
  }
}

async function addMemory(text, category) {
  setBusy('memory', true);
  try {
    await getJson(`${astraApiBase(state.baseUrl)}/memory`, {
      method: 'POST',
      body: JSON.stringify({ text, category })
    });
    await loadMemory();
  } catch (error) {
    toast(`Memory save failed: ${error.message}`);
    setBusy('memory', false);
  }
}

async function deleteMemory(index) {
  setBusy('memory', true);
  try {
    await getJson(`${astraApiBase(state.baseUrl)}/memory`, {
      method: 'DELETE',
      body: JSON.stringify({ index })
    });
    await loadMemory();
  } catch (error) {
    toast(`Memory delete failed: ${error.message}`);
    setBusy('memory', false);
  }
}

async function loadOrganizer() {
  setBusy('organizer', true);
  try {
    state.organizer = await getJson(`${astraApiBase(state.baseUrl)}/organizer`);
  } catch (error) {
    toast(`Organizer load failed: ${error.message}`);
  } finally {
    setBusy('organizer', false);
  }
}

async function saveReminder(payload) {
  setBusy('organizer', true);
  try {
    await getJson(`${astraApiBase(state.baseUrl)}/organizer/reminders`, {
      method: 'POST',
      body: JSON.stringify(payload)
    });
    await loadOrganizer();
  } catch (error) {
    toast(`Reminder save failed: ${error.message}`);
    setBusy('organizer', false);
  }
}

async function saveTask(payload) {
  setBusy('organizer', true);
  try {
    await getJson(`${astraApiBase(state.baseUrl)}/organizer/tasks`, {
      method: 'POST',
      body: JSON.stringify(payload)
    });
    await loadOrganizer();
  } catch (error) {
    toast(`Task save failed: ${error.message}`);
    setBusy('organizer', false);
  }
}

async function deleteReminder(id) {
  setBusy('organizer', true);
  try {
    await getJson(`${astraApiBase(state.baseUrl)}/organizer/reminders/${encodeURIComponent(id)}`, { method: 'DELETE' });
    await loadOrganizer();
  } catch (error) {
    toast(`Reminder delete failed: ${error.message}`);
    setBusy('organizer', false);
  }
}

async function deleteTask(id) {
  setBusy('organizer', true);
  try {
    await getJson(`${astraApiBase(state.baseUrl)}/organizer/tasks/${encodeURIComponent(id)}`, { method: 'DELETE' });
    await loadOrganizer();
  } catch (error) {
    toast(`Task delete failed: ${error.message}`);
    setBusy('organizer', false);
  }
}

async function loadCalendar() {
  setBusy('calendar', true);
  try {
    const json = await getJson(`${astraApiBase(state.baseUrl)}/calendar`);
    state.calendar = json.jobs || [];
  } catch (error) {
    toast(`Calendar load failed: ${error.message}`);
  } finally {
    setBusy('calendar', false);
  }
}

async function createCron(form) {
  setBusy('calendar', true);
  try {
    await getJson(`${astraApiBase(state.baseUrl)}/cron/create`, {
      method: 'POST',
      body: JSON.stringify(form)
    });
    await loadCalendar();
  } catch (error) {
    toast(`Cron create failed: ${error.message}`);
    setBusy('calendar', false);
  }
}

async function toggleCron(id, enabled) {
  setBusy('calendar', true);
  try {
    await getJson(`${astraApiBase(state.baseUrl)}/cron/toggle`, {
      method: 'POST',
      body: JSON.stringify({ id, enabled })
    });
    await loadCalendar();
  } catch (error) {
    toast(`Cron toggle failed: ${error.message}`);
    setBusy('calendar', false);
  }
}

async function runCron(id) {
  try {
    await getJson(`${astraApiBase(state.baseUrl)}/cron/run`, {
      method: 'POST',
      body: JSON.stringify({ id })
    });
    toast('Cron run queued');
  } catch (error) {
    toast(`Cron run failed: ${error.message}`);
  }
}

async function loadAnalytics() {
  setBusy('analytics', true);
  try {
    state.analytics = await getJson(`${astraApiBase(state.baseUrl)}/analytics`);
  } catch (error) {
    toast(`Analytics load failed: ${error.message}`);
  } finally {
    setBusy('analytics', false);
  }
}

async function checkForUpdates() {
  setBusy('updates', true);
  try {
    const releases = await getJson('https://api.github.com/repos/EpicIsTheOne/Astra/releases');
    const candidates = Array.isArray(releases)
      ? releases.filter((release) => state.includePrereleases || !release.prerelease)
      : [];
    const windowsRelease = candidates.find((release) => releaseWindowsAsset(release))
      || (state.includePrereleases ? releases.find((release) => releaseWindowsAsset(release)) : null)
      || candidates[0]
      || null;
    state.latestRelease = windowsRelease;
  } catch (error) {
    toast(`Update check failed: ${error.message}`);
  } finally {
    setBusy('updates', false);
  }
}

async function loadGeminiConfig() {
  try {
    const json = await getJson(`${callApiBase(state.baseUrl)}/api/live/config`);
    state.geminiConfig = json.config || json || null;
  } catch (error) {
    state.geminiConfig = { ok: false, error: error.message, model: 'gemini-3.1-flash-live-preview', transport: 'websocket-proxy-pending' };
  }
  render();
}

async function startCall() {
  if (state.call.active || state.busy.call) return;
  setBusy('call', true);
  state.call.status = 'connecting…';
  render();
  try {
    const started = await getJson(`${callApiBase(state.baseUrl)}/api/call/start`, {
      method: 'POST',
      body: JSON.stringify({ agent: state.agent })
    });
    const session = started.session;
    if (!started.ok || !session?.id) throw new Error(started.error || 'call start failed');
    state.call.active = true;
    state.call.sessionId = session.id;
    state.call.status = 'live 🎙️';
    state.chatMessages.push({ role: 'assistant', text: 'Call connected. Talk to me.' });
    speak('Call connected. Talk to me.');
    connectCallSocket();
    await startMicrophoneStreaming();
  } catch (error) {
    state.call.active = false;
    state.call.sessionId = null;
    state.call.status = 'call failed';
    state.chatMessages.push({ role: 'assistant', text: `Couldn't start the live call session: ${error.message}` });
  } finally {
    setBusy('call', false);
  }
}

async function endCall(announce = true) {
  const sessionId = state.call.sessionId;
  try {
    if (sessionId) {
      await getJson(`${callApiBase(state.baseUrl)}/api/call/${encodeURIComponent(sessionId)}/end`, {
        method: 'POST',
        body: JSON.stringify({})
      });
    }
  } catch {}
  teardownCallRuntime();
  state.call.active = false;
  state.call.sessionId = null;
  state.call.status = 'idle';
  if (announce) speak('Call ended.');
  render();
}

function teardownCallRuntime() {
  try { state.call.socket?.close(); } catch {}
  state.call.socket = null;
  try { state.call.processor?.disconnect(); } catch {}
  try { state.call.sourceNode?.disconnect(); } catch {}
  try { state.call.inputAudioContext?.close(); } catch {}
  try { state.call.audioContext?.close(); } catch {}
  state.call.processor = null;
  state.call.sourceNode = null;
  state.call.inputAudioContext = null;
  state.call.audioContext = null;
  if (state.call.mediaStream) {
    state.call.mediaStream.getTracks().forEach((track) => track.stop());
  }
  state.call.mediaStream = null;
  state.call.assistantAudioQueue = [];
  state.call.playing = false;
}

function connectCallSocket() {
  const socket = new WebSocket(wsUrl(state.baseUrl));
  state.call.socket = socket;
  socket.onmessage = (event) => {
    let json;
    try { json = JSON.parse(event.data); } catch { return; }
    const type = json?.type || '';
    const data = json?.data || {};
    const eventSessionId = data.sessionId || data.id || '';
    if (eventSessionId && eventSessionId !== state.call.sessionId) return;
    if (!(type.startsWith('call:') || type === 'live_task:update')) return;
    handleCallEvent(type, data);
  };
  socket.onclose = () => {
    if (state.call.active) {
      state.call.status = 'reconnecting…';
      render();
      setTimeout(() => {
        if (state.call.active && state.call.sessionId) connectCallSocket();
      }, 1000);
    }
  };
}

function handleCallEvent(type, data) {
  switch (type) {
    case 'call:session.started':
      state.call.status = 'ready';
      break;
    case 'call:session.state':
      state.call.status = data.state || 'live';
      break;
    case 'call:transcript.partial':
      state.call.status = 'listening…';
      break;
    case 'call:transcript.final':
      state.call.status = 'thinking…';
      break;
    case 'call:response.text': {
      const text = String(data.text || '').trim();
      if (text) {
        state.call.lastAssistantText = text;
        state.chatMessages.push({ role: 'assistant', text });
      }
      state.call.status = 'live 🎙️';
      break;
    }
    case 'call:response.audio': {
      const chunk = String(data.pcm16Base64 || '').trim();
      const mimeType = String(data.mimeType || 'audio/pcm;rate=24000').trim();
      if (chunk) enqueueAssistantAudio(chunk, mimeType);
      state.call.status = 'live 🎙️';
      break;
    }
    case 'call:error': {
      const message = String(data.message || 'Unknown live call error').trim();
      state.chatMessages.push({ role: 'assistant', text: `Live call issue: ${message}` });
      state.call.status = 'call issue';
      break;
    }
    case 'call:session.ended':
      teardownCallRuntime();
      state.call.active = false;
      state.call.sessionId = null;
      state.call.status = 'ended';
      break;
    case 'live_task:update': {
      const summary = String(data.summary || '').trim();
      if (summary && ['completed', 'needs_input'].includes(data.status)) {
        state.chatMessages.push({ role: 'assistant', text: data.status === 'needs_input' ? `I need your input to continue: ${summary}` : `Background update: ${summary}` });
      }
      break;
    }
    default:
      break;
  }
  render();
}

async function startMicrophoneStreaming() {
  const stream = await navigator.mediaDevices.getUserMedia({
    audio: {
      channelCount: 1,
      echoCancellation: true,
      noiseSuppression: true,
      autoGainControl: true
    }
  });
  const inputAudioContext = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: 16000 });
  await inputAudioContext.resume();
  const source = inputAudioContext.createMediaStreamSource(stream);
  const processor = inputAudioContext.createScriptProcessor(4096, 1, 1);
  processor.onaudioprocess = (event) => {
    if (!state.call.active || !state.call.sessionId) return;
    const input = event.inputBuffer.getChannelData(0);
    const pcm16 = floatTo16BitPCM(input);
    const pcm16Base64 = arrayBufferToBase64(pcm16);
    fetch(`${callApiBase(state.baseUrl)}/api/call/${encodeURIComponent(state.call.sessionId)}/audio`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ pcm16Base64, mimeType: 'audio/pcm;rate=16000' })
    }).catch(() => null);
  };
  source.connect(processor);
  processor.connect(inputAudioContext.destination);
  state.call.mediaStream = stream;
  state.call.inputAudioContext = inputAudioContext;
  state.call.sourceNode = source;
  state.call.processor = processor;
}

function enqueueAssistantAudio(base64, mimeType = 'audio/pcm;rate=24000') {
  state.call.assistantAudioQueue.push({ base64, sampleRate: parseSampleRateFromMimeType(mimeType) });
  if (!state.call.playing) playNextAssistantAudio();
}

function playNextAssistantAudio() {
  const next = state.call.assistantAudioQueue.shift();
  if (!next) {
    state.call.playing = false;
    return;
  }
  state.call.playing = true;
  const sampleRate = next.sampleRate || 24000;
  const ctx = state.call.audioContext || new (window.AudioContext || window.webkitAudioContext)({ sampleRate });
  state.call.audioContext = ctx;
  const pcm = pcm16ToFloat32(base64ToArrayBuffer(next.base64));
  const buffer = ctx.createBuffer(1, pcm.length, sampleRate);
  buffer.copyToChannel(pcm, 0);
  const src = ctx.createBufferSource();
  src.buffer = buffer;
  src.connect(ctx.destination);
  src.onended = () => {
    state.call.playing = false;
    playNextAssistantAudio();
  };
  src.start();
}

function startVoiceInput() {
  const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
  if (!SpeechRecognition) return toast('Speech recognition not available in this runtime');
  if (state.recognition) state.recognition.abort();
  const recog = new SpeechRecognition();
  recog.lang = 'en-US';
  recog.continuous = false;
  recog.interimResults = false;
  state.recognition = recog;
  state.listening = true;
  render();
  recog.onresult = (event) => {
    const transcript = event.results?.[0]?.[0]?.transcript?.trim();
    const input = document.querySelector('[data-chat-input]');
    if (input && transcript) input.value = transcript;
  };
  recog.onerror = () => toast('Voice input failed');
  recog.onend = () => {
    state.listening = false;
    render();
  };
  recog.start();
}

function escapeHtml(text) {
  return String(text || '').replace(/[&<>"']/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]));
}

function renderHeader() {
  const upcoming = nextReminder();
  return `
    <header class="hero ${isPanel ? 'hero-panel' : ''}">
      <div class="hero-copy">
        <div class="eyebrow">Astra for Windows</div>
        <h1>${isPanel ? 'Mini panel' : 'Desktop command center'}</h1>
        <p>Same backend. Shared memory. Shared organizer. None of the phone alarm cosplay.</p>
        <div class="hero-meta">
          <span class="pill ${state.connected ? 'connected' : 'offline'}">${state.connected ? 'bridge online' : 'bridge offline'}</span>
          <span class="pill">${reminderCount()} reminders</span>
          <span class="pill">${pendingTaskCount()} open tasks</span>
          <span class="pill">${upcoming ? `next ${timeUntil(upcoming.scheduledTimeMillis)}` : 'nothing queued'}</span>
        </div>
      </div>
      <div class="hero-actions">
        ${isPanel ? `
          <button class="ghost" data-action="minimize-window">–</button>
          <button class="danger" data-action="close-window">×</button>
        ` : `
          <button class="ghost" data-action="open-panel">Open mini panel</button>
          <button data-action="check-updates">${state.busy.updates ? 'Checking…' : 'Check updates'}</button>
        `}
      </div>
    </header>
  `;
}

function renderOverviewCards() {
  const upcoming = nextReminder();
  return `
    <section class="overview-grid ${isPanel ? 'overview-panel' : ''}">
      <article class="glass-card stat-card accent-pink">
        <div class="label">Connection</div>
        <div class="value">${state.connected ? 'Ready' : 'Offline'}</div>
        <div class="hint">${commandCenterBase(state.baseUrl)}</div>
      </article>
      <article class="glass-card stat-card accent-violet">
        <div class="label">Next reminder</div>
        <div class="value">${upcoming ? timeUntil(upcoming.scheduledTimeMillis) : 'None'}</div>
        <div class="hint">${upcoming ? escapeHtml(upcoming.title) : 'You have suspiciously little structure.'}</div>
      </article>
      <article class="glass-card stat-card accent-blue">
        <div class="label">Tasks</div>
        <div class="value">${pendingTaskCount()}</div>
        <div class="hint">unfinished little obligations</div>
      </article>
      <article class="glass-card stat-card accent-gold">
        <div class="label">Updates</div>
        <div class="value">${state.latestRelease?.tag_name || 'Unknown'}</div>
        <div class="hint">installed ${APP_VERSION}</div>
      </article>
    </section>
  `;
}

function renderSettingsCard() {
  const releaseAsset = releaseWindowsAsset(state.latestRelease);
  return `
    <section class="glass-card settings-card">
      <div class="section-head">
        <div>
          <div class="eyebrow">Connection</div>
          <h2>Bridge settings</h2>
        </div>
        <div class="inline-status ${state.connected ? 'ok' : 'warn'}">${state.connected ? 'Connected through Command Center bridge' : 'Not connected yet'}</div>
      </div>
      <div class="settings-grid">
        <label>
          <span>Base URL</span>
          <input data-base-url value="${escapeHtml(state.baseUrl)}" />
        </label>
        <label>
          <span>Agent</span>
          <input data-agent value="${escapeHtml(state.agent)}" />
        </label>
      </div>
      <div class="settings-toggle-row">
        <label class="checkbox-row">
          <input type="checkbox" data-include-prereleases ${state.includePrereleases ? 'checked' : ''} />
          <span>Include prerelease updates for Windows</span>
        </label>
      </div>
      <div class="row actions-row">
        <button class="ghost" data-action="save-settings">Save settings</button>
        <button data-action="connect">${state.busy.connect ? 'Connecting…' : 'Connect now'}</button>
        ${state.latestRelease?.html_url ? `<button class="ghost" data-open-url="${state.latestRelease.html_url}">Open release page</button>` : ''}
        ${releaseAsset?.browser_download_url ? `<button class="ghost" data-open-url="${releaseAsset.browser_download_url}">Download Windows build</button>` : ''}
      </div>
      <div class="subtle-note">Backend target: ${escapeHtml(commandCenterBase(state.baseUrl))}</div>
      <div class="subtle-note">Installed ${escapeHtml(state.currentVersion)} on ${escapeHtml(state.appPlatform)}. ${escapeHtml(updateStatusText())}</div>
    </section>
  `;
}

function renderNav() {
  const tabs = [
    ['chat', 'Chat'],
    ['organizer', 'Organizer'],
    ['memory', 'Memory'],
    ['calendar', 'Calendar'],
    ['analytics', 'Analytics']
  ];
  return `
    <nav class="view-tabs">
      ${tabs.map(([id, label]) => `<button class="tab ${state.activeView === id ? 'active' : ''}" data-view="${id}">${label}</button>`).join('')}
    </nav>
  `;
}

function renderChatView() {
  const gemini = state.geminiConfig;
  return `
    <section class="glass-card view-card chat-view">
      <div class="section-head">
        <div>
          <div class="eyebrow">Conversation</div>
          <h2>Astra chat</h2>
        </div>
        <div class="inline-status ${state.busy.chat ? 'busy' : 'ok'}">${state.busy.chat ? 'Thinking…' : 'Live'}</div>
      </div>
      <div class="call-strip">
        <div>
          <strong>Gemini call</strong>
          <small>Call: ${escapeHtml(state.call.status)}${state.call.sessionId ? ` · ${escapeHtml(state.call.sessionId)}` : ''}</small>
          <small>${gemini ? `Model ${escapeHtml(gemini.model || 'unknown')} · transport ${escapeHtml(gemini.transport || 'unknown')}` : 'Loading Gemini live config…'}</small>
        </div>
        <div class="row slim">
          <button class="ghost" data-action="refresh-gemini">Refresh config</button>
          <button data-action="call-toggle">${state.busy.call ? 'Working…' : state.call.active ? 'End Call' : 'Start Call'}</button>
        </div>
      </div>
      <div class="chat-log">${state.chatMessages.map((message) => `<div class="msg ${message.role}"><span>${message.role === 'assistant' ? 'Astra' : 'You'}</span><p>${escapeHtml(message.text)}</p></div>`).join('')}</div>
      <div class="chat-compose">
        <textarea data-chat-input placeholder="Say something reckless."></textarea>
        <div class="row actions-row">
          <button data-action="chat-send">${state.busy.chat ? 'Sending…' : 'Send'}</button>
          <button class="ghost" data-action="voice-input">${state.listening ? 'Listening…' : 'Talk'}</button>
        </div>
      </div>
    </section>
  `;
}

function renderOrganizerView() {
  return `
    <section class="view-split">
      <article class="glass-card view-card organizer-card">
        <div class="section-head">
          <div>
            <div class="eyebrow">Shared organizer</div>
            <h2>Reminders</h2>
          </div>
          <button class="ghost" data-action="refresh-organizer">${state.busy.organizer ? 'Syncing…' : 'Refresh'}</button>
        </div>
        <form data-form="reminder" class="stack form-block deluxe-form">
          <input name="title" placeholder="Reminder text" required />
          <input name="scheduledTimeMillis" type="datetime-local" required />
          <div class="triple-grid">
            <select name="importance"><option value="1">Normal</option><option value="2" selected>Important</option><option value="3">Critical</option></select>
            <select name="annoyanceLevel"><option value="1">Gentle</option><option value="2" selected>Pushy</option><option value="3">Chaotic</option></select>
            <select name="repeatRule"><option value="once">Once</option><option value="daily">Daily</option><option value="weekly">Weekly</option></select>
          </div>
          <button type="submit">Save reminder</button>
        </form>
        <div class="list tall-list">${(state.organizer.reminders || []).map((item) => `
          <div class="list-item grow rich-item">
            <div>
              <strong>${escapeHtml(item.title)}</strong>
              <small>${importanceLabel(item.importance)} · ${annoyanceLabel(item.annoyanceLevel)} · ${formatDateTime(item.scheduledTimeMillis)} · ${timeUntil(item.scheduledTimeMillis)}</small>
            </div>
            <button class="danger" data-reminder-delete="${item.id}">Delete</button>
          </div>`).join('') || '<p class="muted">No reminders yet.</p>'}</div>
      </article>
      <article class="glass-card view-card organizer-card">
        <div class="section-head">
          <div>
            <div class="eyebrow">Shared organizer</div>
            <h2>Task board</h2>
          </div>
          <div class="inline-status ${pendingTaskCount() ? 'warn' : 'ok'}">${pendingTaskCount()} pending</div>
        </div>
        <form data-form="task" class="stack form-block deluxe-form">
          <input name="title" placeholder="Task title" required />
          <textarea name="notes" placeholder="Notes"></textarea>
          <button type="submit">Save task</button>
        </form>
        <div class="list tall-list">${(state.organizer.tasks || []).map((item) => `
          <div class="list-item grow rich-item ${item.done ? 'done' : ''}">
            <div>
              <strong>${item.done ? '✓ ' : ''}${escapeHtml(item.title)}</strong>
              <small>${escapeHtml(item.notes || 'No notes')} · ${item.done ? 'done' : 'open'}</small>
            </div>
            <div class="row slim">
              <button class="ghost" data-task-toggle="${item.id}">${item.done ? 'Undo' : 'Done'}</button>
              <button class="danger" data-task-delete="${item.id}">Delete</button>
            </div>
          </div>`).join('') || '<p class="muted">No tasks yet.</p>'}</div>
      </article>
    </section>
  `;
}

function renderMemoryView() {
  return `
    <section class="glass-card view-card">
      <div class="section-head">
        <div>
          <div class="eyebrow">Shared memory</div>
          <h2>Notes and preferences</h2>
        </div>
        <button class="ghost" data-action="memory-refresh">${state.busy.memory ? 'Refreshing…' : 'Refresh'}</button>
      </div>
      <div class="toolbar-grid">
        <input data-memory-text placeholder="Save a note or preference" />
        <select data-memory-category>
          <option value="preference">Preference</option>
          <option value="routine">Routine</option>
          <option value="goal">Goal</option>
          <option value="dislike">Dislike</option>
          <option value="misc">Misc</option>
        </select>
        <button data-action="memory-add">Save memory</button>
      </div>
      <div class="list tall-list">${state.memory.map((note, index) => `<div class="list-item rich-item"><div><strong>[${escapeHtml(note.category || 'misc')}]</strong><small>${escapeHtml(note.text || '')}</small></div><button class="danger" data-memory-delete="${index}">Delete</button></div>`).join('') || '<p class="muted">No memory notes yet.</p>'}</div>
    </section>
  `;
}

function renderCalendarView() {
  return `
    <section class="glass-card view-card">
      <div class="section-head">
        <div>
          <div class="eyebrow">Scheduler</div>
          <h2>Calendar / cron</h2>
        </div>
        <button class="ghost" data-action="calendar-refresh">${state.busy.calendar ? 'Refreshing…' : 'Refresh'}</button>
      </div>
      <form data-form="cron" class="stack form-block deluxe-form">
        <div class="dual-grid">
          <input name="name" placeholder="Reminder name" required />
          <input name="cron" placeholder="Cron expression" required />
        </div>
        <div class="dual-grid">
          <input name="tz" value="America/New_York" placeholder="Timezone" required />
          <input name="message" placeholder="Reminder text" required />
        </div>
        <button type="submit">Create cron job</button>
      </form>
      <div class="list tall-list">${state.calendar.map((job) => `<div class="list-item grow rich-item"><div><strong>${escapeHtml(job.name || 'unnamed')}</strong><small>${escapeHtml(job.schedule?.expr || job.schedule || '')} · ${job.enabled ? 'enabled' : 'disabled'} · next ${job.nextRunAtMs ? formatDateTime(job.nextRunAtMs) : '-'}</small></div><div class="row slim"><button class="ghost" data-cron-run="${job.id}">Run</button><button data-cron-toggle="${job.id}" data-enabled="${job.enabled}">${job.enabled ? 'Disable' : 'Enable'}</button></div></div>`).join('') || '<p class="muted">No cron jobs loaded.</p>'}</div>
    </section>
  `;
}

function renderAnalyticsView() {
  return `
    <section class="glass-card view-card">
      <div class="section-head">
        <div>
          <div class="eyebrow">Metrics</div>
          <h2>Wake analytics</h2>
        </div>
        <button class="ghost" data-action="analytics-refresh">${state.busy.analytics ? 'Refreshing…' : 'Refresh'}</button>
      </div>
      ${state.analytics ? `
        <div class="overview-grid mini-stats">
          <article class="glass-card stat-card accent-pink"><div class="label">Wake events</div><div class="value">${state.analytics.totals?.wakeEvents ?? 0}</div></article>
          <article class="glass-card stat-card accent-violet"><div class="label">Ack events</div><div class="value">${state.analytics.totals?.ackEvents ?? 0}</div></article>
          <article class="glass-card stat-card accent-blue"><div class="label">Ack rate</div><div class="value">${state.analytics.totals?.ackRate ?? 0}</div></article>
        </div>
        <div class="list tall-list">${(state.analytics.recent || []).map((event) => `<div class="list-item rich-item"><div><strong>${escapeHtml(event.type || 'event')}</strong><small>${escapeHtml(event.at || '')} · ${escapeHtml(event.reason || '')}</small></div></div>`).join('')}</div>
      ` : '<p class="muted">Analytics not loaded yet.</p>'}
    </section>
  `;
}

function renderView() {
  if (isPanel) return renderChatView();
  switch (state.activeView) {
    case 'organizer': return renderOrganizerView();
    case 'memory': return renderMemoryView();
    case 'calendar': return renderCalendarView();
    case 'analytics': return renderAnalyticsView();
    default: return renderChatView();
  }
}

function renderApp() {
  root.innerHTML = `
    <div class="shell ${isPanel ? 'shell-panel' : ''}">
      ${renderHeader()}
      ${renderOverviewCards()}
      ${renderSettingsCard()}
      ${isPanel ? '' : renderNav()}
      ${renderView()}
    </div>
  `;
  bindEvents();
}

function bindEvents() {
  root.querySelector('[data-action="save-settings"]')?.addEventListener('click', async () => {
    state.baseUrl = root.querySelector('[data-base-url]').value.trim() || DEFAULT_BASE_URL;
    state.agent = root.querySelector('[data-agent]').value.trim() || DEFAULT_AGENT;
    state.includePrereleases = Boolean(root.querySelector('[data-include-prereleases]')?.checked);
    saveSettings();
    await checkForUpdates();
    toast('Settings saved');
  });
  root.querySelector('[data-action="connect"]')?.addEventListener('click', connectBridge);
  root.querySelector('[data-action="chat-send"]')?.addEventListener('click', async () => {
    const input = root.querySelector('[data-chat-input]');
    const text = input.value.trim();
    input.value = '';
    await sendChatMessage(text);
  });
  root.querySelector('[data-chat-input]')?.addEventListener('keydown', async (event) => {
    if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
      const input = event.currentTarget;
      const text = input.value.trim();
      input.value = '';
      await sendChatMessage(text);
    }
  });
  root.querySelector('[data-action="voice-input"]')?.addEventListener('click', startVoiceInput);
  root.querySelector('[data-action="refresh-gemini"]')?.addEventListener('click', loadGeminiConfig);
  root.querySelector('[data-action="call-toggle"]')?.addEventListener('click', () => (state.call.active ? endCall() : startCall()));
  root.querySelector('[data-action="open-panel"]')?.addEventListener('click', () => window.astraDesktop?.openPanel());
  root.querySelector('[data-action="check-updates"]')?.addEventListener('click', checkForUpdates);
  root.querySelector('[data-action="minimize-window"]')?.addEventListener('click', () => window.astraDesktop?.minimizeWindow());
  root.querySelector('[data-action="close-window"]')?.addEventListener('click', () => window.astraDesktop?.closeWindow());
  root.querySelectorAll('[data-view]').forEach((button) => button.addEventListener('click', () => setActiveView(button.dataset.view)));
  root.querySelectorAll('[data-open-url]').forEach((button) => button.addEventListener('click', () => window.astraDesktop?.openExternal(button.dataset.openUrl)));

  root.querySelector('[data-action="memory-add"]')?.addEventListener('click', async () => {
    const text = root.querySelector('[data-memory-text]').value.trim();
    const category = root.querySelector('[data-memory-category]').value;
    if (!text) return;
    root.querySelector('[data-memory-text]').value = '';
    await addMemory(text, category);
  });
  root.querySelector('[data-action="memory-refresh"]')?.addEventListener('click', loadMemory);
  root.querySelectorAll('[data-memory-delete]').forEach((button) => button.addEventListener('click', () => deleteMemory(Number(button.dataset.memoryDelete))));

  root.querySelector('[data-action="refresh-organizer"]')?.addEventListener('click', loadOrganizer);
  root.querySelector('[data-form="reminder"]')?.addEventListener('submit', async (event) => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    await saveReminder({
      title: form.get('title'),
      scheduledTimeMillis: new Date(form.get('scheduledTimeMillis')).getTime(),
      importance: Number(form.get('importance')),
      annoyanceLevel: Number(form.get('annoyanceLevel')),
      repeatRule: form.get('repeatRule')
    });
    event.currentTarget.reset();
  });
  root.querySelector('[data-form="task"]')?.addEventListener('submit', async (event) => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    await saveTask({ title: form.get('title'), notes: form.get('notes') });
    event.currentTarget.reset();
  });
  root.querySelectorAll('[data-reminder-delete]').forEach((button) => button.addEventListener('click', () => deleteReminder(button.dataset.reminderDelete)));
  root.querySelectorAll('[data-task-delete]').forEach((button) => button.addEventListener('click', () => deleteTask(button.dataset.taskDelete)));
  root.querySelectorAll('[data-task-toggle]').forEach((button) => button.addEventListener('click', async () => {
    const task = state.organizer.tasks.find((entry) => entry.id === button.dataset.taskToggle);
    if (!task) return;
    await saveTask({ ...task, done: !task.done, completedAtMillis: task.done ? 0 : Date.now() });
  }));

  root.querySelector('[data-action="calendar-refresh"]')?.addEventListener('click', loadCalendar);
  root.querySelector('[data-form="cron"]')?.addEventListener('submit', async (event) => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    await createCron({
      name: form.get('name'),
      cron: form.get('cron'),
      tz: form.get('tz'),
      message: form.get('message')
    });
    event.currentTarget.reset();
  });
  root.querySelectorAll('[data-cron-run]').forEach((button) => button.addEventListener('click', () => runCron(button.dataset.cronRun)));
  root.querySelectorAll('[data-cron-toggle]').forEach((button) => button.addEventListener('click', () => toggleCron(button.dataset.cronToggle, button.dataset.enabled !== 'true')));

  root.querySelector('[data-action="analytics-refresh"]')?.addEventListener('click', loadAnalytics);
}

async function bootstrap() {
  render();
  await window.astraDesktop?.appInfo?.().then((info) => {
    if (info?.ok) {
      state.currentVersion = info.version || state.currentVersion;
      state.appPlatform = info.platform || state.appPlatform;
    }
  }).catch(() => null);
  render();
  await Promise.allSettled([
    connectBridge(),
    loadMemory(),
    loadOrganizer(),
    loadCalendar(),
    loadAnalytics(),
    checkForUpdates(),
    loadGeminiConfig()
  ]);
}

function render() {
  renderApp();
}

bootstrap();
