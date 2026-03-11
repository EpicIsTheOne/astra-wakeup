import fs from 'node:fs';
import path from 'node:path';

const statePath = path.resolve('data/state.json');

const defaults = {
  streak: 0,
  lastWakeAt: null,
  lastAckAt: null,
  pendingWake: false,
  pendingWakeCount: 0,
  wakeProfile: 'bully'
};

export function loadState() {
  try {
    const raw = fs.readFileSync(statePath, 'utf8');
    return { ...defaults, ...JSON.parse(raw) };
  } catch {
    return { ...defaults };
  }
}

export function saveState(state) {
  fs.mkdirSync(path.dirname(statePath), { recursive: true });
  fs.writeFileSync(statePath, JSON.stringify(state, null, 2));
}
