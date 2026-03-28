import { writeFile } from 'node:fs/promises';

export async function openAiTts({ apiKey, model, voice, text, outPath }) {
  if (!apiKey) throw new Error('OPENAI_API_KEY is missing');

  const res = await fetch('https://api.openai.com/v1/audio/speech', {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${apiKey}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      model,
      voice,
      input: text,
      format: 'mp3'
    })
  });

  if (!res.ok) {
    const body = await res.text();
    throw new Error(`OpenAI TTS failed: ${res.status} ${body}`);
  }

  const buf = Buffer.from(await res.arrayBuffer());
  await writeFile(outPath, buf);
  return outPath;
}
