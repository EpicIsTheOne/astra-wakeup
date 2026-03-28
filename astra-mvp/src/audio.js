import fs from 'node:fs';
import path from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

const execFileAsync = promisify(execFile);

export async function maybeMixWithSfx(voicePath) {
  const sfxPath = path.resolve('assets/sfx/alarm.mp3');
  if (!fs.existsSync(sfxPath)) return voicePath;

  const outPath = path.resolve('data/out-mixed.mp3');
  try {
    await execFileAsync('ffmpeg', [
      '-y',
      '-i', voicePath,
      '-stream_loop', '-1',
      '-i', sfxPath,
      '-filter_complex', '[1:a]volume=0.15,atrim=0:20[sfx];[0:a][sfx]amix=inputs=2:duration=first:dropout_transition=2[a]',
      '-map', '[a]',
      '-c:a', 'libmp3lame',
      '-q:a', '4',
      outPath
    ]);
    return outPath;
  } catch {
    return voicePath;
  }
}
