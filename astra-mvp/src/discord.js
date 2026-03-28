import fs from 'node:fs/promises';

export async function sendDiscordDmAudio({ token, userId, filePath, caption }) {
  if (!token) throw new Error('DISCORD_BOT_TOKEN is missing');
  if (!userId) throw new Error('DISCORD_USER_ID is missing');

  const channelRes = await fetch('https://discord.com/api/v10/users/@me/channels', {
    method: 'POST',
    headers: {
      Authorization: `Bot ${token}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({ recipient_id: userId })
  });

  if (!channelRes.ok) throw new Error(`Failed creating DM channel: ${channelRes.status}`);
  const channel = await channelRes.json();

  const data = await fs.readFile(filePath);
  const form = new FormData();
  form.set('content', caption || 'Wake up.');
  form.set('file', new Blob([data], { type: 'audio/mpeg' }), 'astra.mp3');

  const msgRes = await fetch(`https://discord.com/api/v10/channels/${channel.id}/messages`, {
    method: 'POST',
    headers: { Authorization: `Bot ${token}` },
    body: form
  });

  if (!msgRes.ok) {
    const body = await msgRes.text();
    throw new Error(`Failed sending DM: ${msgRes.status} ${body}`);
  }

  return msgRes.json();
}
