# Astra MVP

A local Node app that generates a sassy wake-up line, renders TTS audio, optionally mixes alarm SFX, and DMs it to a Discord user at 7:00 AM ET.

## Features (MVP)
- Daily scheduler (`node-cron`) with timezone support
- Wake line generator with multiple styles
- TTS provider abstraction (`openai` and `elevenlabs` placeholder)
- Optional SFX mix via `ffmpeg`
- Discord DM delivery using bot token
- Mini control panel (`/`) with **Fire now**, **I'm awake**, **Snooze 10m**

## Quick start
1. `cd astra-mvp`
2. `cp .env.example .env` and fill secrets
3. Install deps: `npm i`
4. Install ffmpeg (`apt install ffmpeg`) if you want SFX mixing
5. Put optional SFX file at `assets/sfx/alarm.mp3`
6. Run: `npm run start`

## Required Discord setup
- Create a Discord bot, invite it, enable DM permissions.
- Set `DISCORD_BOT_TOKEN` and `DISCORD_USER_ID`.

## API
- `POST /api/wakeup/fire` => generate + send wakeup now
- `POST /api/wakeup/ack` => mark user awake, increment streak
- `POST /api/wakeup/snooze` => schedule one-off wakeup in X minutes
- `POST /api/wakeup/line` => returns dynamic Astra wake line (OpenAI-backed)
- `GET /api/state` => current streak/state

## Notes
- This is the MVP foundation. Next layer is richer daily brief injection (weather/news/calendar) and richer SFX scenes.
