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

/** Calls Google's Gemini API (generateContent). The key stays on the server. */
@Injectable()
export class GeminiClient {
  private readonly log = new Logger('Gemini');

  get configured() {
    return !!process.env.GEMINI_API_KEY;
  }

  async generate(req: GeminiRequest): Promise<GeminiPart[]> {
    const key = process.env.GEMINI_API_KEY;
    if (!key) throw new Error('GEMINI_API_KEY is not set');
    const res = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(req.model)}:generateContent`, {
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
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      this.log.warn(`Gemini ${res.status}: ${body.slice(0, 300)}`);
      throw new Error(`Gemini answered ${res.status}`);
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
