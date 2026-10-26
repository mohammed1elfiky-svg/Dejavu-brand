import { Injectable, Logger } from '@nestjs/common';

/** One part of a Gemini message: text, an inline image, or a tool call / its result. */
export type GeminiPart =
  | { text: string }
  | { inline_data: { mime_type: string; data: string } }
  | { functionCall: { name: string; args?: Record<string, unknown> } }
  | { functionResponse: { name: string; response: Record<string, unknown> } };

export interface GeminiContent {
  role: 'user' | 'model';
  parts: GeminiPart[];
}

export interface GeminiTool {
  name: string;
  description: string;
  parameters?: { type: 'object'; properties: Record<string, { type: string; description?: string }>; required?: string[] };
}

export interface GeminiRequest {
  model: string;
  system: string;
  contents: GeminiContent[];
  tools: GeminiTool[];
  /** Ask for a JSON answer. */
  json?: boolean;
}

/** Why the last call failed, for the admin's "test the connection". */
export class GeminiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
  }
}

const BASE = 'https://generativelanguage.googleapis.com/v1beta';

/** Calls Google's Gemini API (generateContent). The key stays on the server. */
@Injectable()
export class GeminiClient {
  private readonly log = new Logger('Gemini');
  /** Models the key can use, found when the configured one was refused. */
  private resolved = new Map<string, string>();

  get configured() {
    return !!process.env.GEMINI_API_KEY;
  }

  private async call(model: string, req: GeminiRequest, key: string, timeoutMs = 30_000) {
    return fetch(`${BASE}/models/${encodeURIComponent(model)}:generateContent`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'x-goog-api-key': key },
      body: JSON.stringify({
        system_instruction: { parts: [{ text: req.system }] },
        contents: req.contents,
        tools: req.tools.length ? [{ function_declarations: req.tools }] : undefined,
        generationConfig: { temperature: req.json ? 0.3 : 0.4, maxOutputTokens: 2048, ...(req.json ? { responseMimeType: 'application/json' } : {}) },
      }),
      signal: AbortSignal.timeout(timeoutMs),
    });
  }

  /** The models this key may call generateContent on, newest flash first. */
  async models(): Promise<string[]> {
    const key = process.env.GEMINI_API_KEY;
    if (!key) return [];
    const res = await fetch(`${BASE}/models?pageSize=200`, { headers: { 'x-goog-api-key': key }, signal: AbortSignal.timeout(20_000) });
    if (!res.ok) return [];
    const json = (await res.json()) as { models?: { name: string; supportedGenerationMethods?: string[] }[] };
    return (json.models ?? [])
      .filter((m) => m.supportedGenerationMethods?.includes('generateContent'))
      .map((m) => m.name.replace(/^models\//, ''));
  }

  /** The best flash model of [models]: "latest" aliases first, then the highest version. */
  private pick(models: string[]): string | null {
    const flash = models.filter((m) => /flash/.test(m) && !/(lite|image|tts|live|thinking|audio|embedding|preview|exp)/.test(m));
    const alias = flash.find((m) => m === 'gemini-flash-latest');
    if (alias) return alias;
    const version = (m: string) => Number(/gemini-(\d+(?:\.\d+)?)/.exec(m)?.[1] ?? 0);
    return flash.sort((a, b) => version(b) - version(a) || a.length - b.length)[0] ?? null;
  }

  async generate(req: GeminiRequest): Promise<GeminiPart[]> {
    const key = process.env.GEMINI_API_KEY;
    if (!key) throw new GeminiError('GEMINI_API_KEY is not set', 0);
    const first = this.resolved.get(req.model) ?? req.model;
    // When one model is busy (503), slow, or unknown to this key, walk down the list of others.
    const queue = [first, 'gemini-flash-latest', 'gemini-flash-lite-latest', 'gemini-2.5-flash-lite', 'gemini-2.0-flash'].filter((m, i, all) => all.indexOf(m) === i);
    let last: GeminiError = new GeminiError('Gemini did not answer', 0);
    let listed = false;
    for (let i = 0; i < queue.length; i++) {
      const model = queue[i];
      let res: Response;
      try {
        res = await this.call(model, req, key, i === 0 ? 30_000 : 15_000);
      } catch (e) {
        last = new GeminiError((e as Error).message, 0);
        this.log.warn(`Model ${model} did not answer (${last.message}); trying the next one`);
        continue;
      }
      if (res.ok) {
        const json = (await res.json()) as { candidates?: { content?: { parts?: GeminiPart[] } }[] };
        return json.candidates?.[0]?.content?.parts ?? [];
      }
      const body = await res.text().catch(() => '');
      let reason = body.slice(0, 300);
      try {
        reason = (JSON.parse(body) as { error?: { message?: string } }).error?.message ?? reason;
      } catch {
        /* keep the raw text */
      }
      this.log.warn(`Gemini ${res.status} on ${model}: ${reason}`);
      last = new GeminiError(reason || `Gemini answered ${res.status}`, res.status);
      if (res.status === 404 || res.status === 400) {
        // Not available to this key: ask which models are, and try the best one next.
        if (!listed) {
          listed = true;
          const alt = this.pick(await this.models().catch(() => []));
          if (alt && !queue.includes(alt)) queue.splice(i + 1, 0, alt);
        }
        continue;
      }
      if (![429, 500, 502, 503, 504].includes(res.status)) break; // e.g. a bad key: others will fail the same way
      if (res.status === 503 && i === 0) await new Promise((r) => setTimeout(r, 1500));
    }
    throw last;
  }

  /** One answer as plain text (no tools). */
  async text(model: string, system: string, parts: GeminiPart[], json = false): Promise<string> {
    const out = await this.generate({ model, system, contents: [{ role: 'user', parts }], tools: [], json });
    return out
      .filter((p): p is { text: string } => 'text' in p)
      .map((p) => p.text)
      .join('')
      .trim();
  }
}
