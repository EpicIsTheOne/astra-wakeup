const styles = {
  savage: [
    "Wake up, {name}. The sun is already productive and you're losing.",
    "Rise and move, {name}. Even your alarm is embarrassed for you.",
    "Morning, chaos goblin. Get vertical before I escalate."
  ],
  flirty: [
    "Good morning, {name}~ Be useful today and I'll pretend I'm impressed.",
    "Up, sleepy menace. I made your wake-up voice pretty; don't waste it.",
    "Hey cutie disaster, eyes open. Your mission starts now."
  ],
  gremlin: [
    "BEEP BEEP. It's quest o'clock, {name}. No side quests in bed.",
    "Spawn point loaded. Move, {name}, before I trigger boss music.",
    "Morning patch notes: less procrastination, more wins. Go."
  ]
};

export function buildWakeLine(name = 'Epic', mode = 'flirty') {
  const pool = styles[mode] || styles.flirty;
  const pick = pool[Math.floor(Math.random() * pool.length)];
  return pick.replaceAll('{name}', name);
}
