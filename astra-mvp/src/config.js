import 'dotenv/config';

export const cfg = {
  port: Number(process.env.PORT || 8787),
  tz: process.env.TZ || 'America/New_York',
  wakeCron: process.env.WAKE_CRON || '0 7 * * *',
  wakeUserName: process.env.WAKE_USER_NAME || 'Epic',
  discordBotToken: process.env.DISCORD_BOT_TOKEN || '',
  discordUserId: process.env.DISCORD_USER_ID || '',
  ttsProvider: (process.env.TTS_PROVIDER || 'openai').toLowerCase(),
  openaiApiKey: process.env.OPENAI_API_KEY || '',
  openaiTtsModel: process.env.OPENAI_TTS_MODEL || 'gpt-4o-mini-tts',
  openaiTtsVoice: process.env.OPENAI_TTS_VOICE || 'nova',
  elevenlabsApiKey: process.env.ELEVENLABS_API_KEY || '',
  elevenlabsVoiceId: process.env.ELEVENLABS_VOICE_ID || '',
  punishmentEnabled: (process.env.PUNISHMENT_ENABLED || 'true').toLowerCase() === 'true',
  punishmentMinutes: Number(process.env.PUNISHMENT_MINUTES || 5)
};
