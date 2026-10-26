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

  private async call(model: string, req: GeminiRequest, key: string) {
    return fetch(`${BASE}/models/${encodeURIComponent(model)}:generateContent`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'x-goog-api-key': key },
      body: JSON.stringify({
        system_instruction: { parts: [{ text: req.system }] },
        contents: req.contents,
        tools: req.tools.length ? [{ function_declarations: req.tools }] : undefined,
        generationConfig: { temperature: req.json ? 0.3 : 0.4, maxOutputTokens: 2048, ...(req.json ? { responseMimeType: 'application/json' } : {}) },
      }),
      signal: AbortSignal.timeout(45_000),
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
    const model = this.resolved.get(req.model) ?? req.model;
    let res = await this.call(model, req, key);
    // The model name is not (or no longer) available to this key: use one that is.
    if (!res.ok && (res.status === 404 || res.status === 400)) {
      const alt = this.pick(await this.models().catch(() => []));
      if (alt && alt !== model) {
        this.log.warn(`Model ${model} refused (${res.status}); using ${alt}`);
        this.resolved.set(req.model, alt);
        res = await this.call(alt, req, key);
      }
    }
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      let reason = body.slice(0, 300);
      try {
        reason = (JSON.parse(body) as { error?: { message?: string } }).error?.message ?? reason;
      } catch {
        /* keep the raw text */
      }
      this.log.warn(`Gemini ${res.status}: ${reason}`);
      throw new GeminiError(reason || `Gemini answered ${res.status}`, res.status);
    }
    const json = (await res.json()) as { candidates?: { content?: { parts?: GeminiPart[] } }[] };
    return json.candidates?.[0]?.content?.parts ?? [];
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
