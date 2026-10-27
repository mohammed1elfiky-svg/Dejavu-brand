import { BadRequestException, Injectable, Logger } from '@nestjs/common';
import { PrismaService } from '../prisma/prisma.service';

const KEY = 'app.servers';
const MAX = 5;

interface Stored {
  servers: string[];
  updatedAt: string;
  directorySyncedAt?: string | null;
  directoryError?: string | null;
}

/** Where the GitHub "directory" file lives; the customer app reads it when no server answers. */
function directory() {
  const repo = process.env.DIRECTORY_REPO || 'mohammed1elfiky-svg/Dejavu-brand';
  const branch = process.env.DIRECTORY_BRANCH || 'app-directory';
  const path = process.env.DIRECTORY_PATH || 'servers.json';
  return { repo, branch, path, token: process.env.DIRECTORY_GITHUB_TOKEN ?? '', url: `https://raw.githubusercontent.com/${repo}/${branch}/${path}` };
}

/** "dejavu.example.com" or "https://dejavu.example.com/api/v1" → "https://dejavu.example.com/api/v1/". */
export function normalizeServer(raw: string): string {
  let url = raw.trim();
  if (!/^https?:\/\//i.test(url)) url = `https://${url}`;
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    throw new BadRequestException(`عنوان غير صحيح: ${raw}`);
  }
  if (parsed.protocol !== 'https:') throw new BadRequestException(`يجب أن يبدأ العنوان بـ https: ${raw}`);
  if (!parsed.hostname.includes('.')) throw new BadRequestException(`عنوان غير صحيح: ${raw}`);
  const path = parsed.pathname.replace(/\/+$/, '');
  const base = path.endsWith('/api/v1') ? path : `${path}/api/v1`;
  return `https://${parsed.host}${base}/`;
}

/**
 * The server addresses the customer app tries, in order. The app keeps the latest list it
 * saw; when none of them answers it reads the same list from a file on GitHub, which this
 * service rewrites on every change (needs DIRECTORY_GITHUB_TOKEN).
 */
@Injectable()
export class AppServersService {
  private readonly log = new Logger('AppServers');

  constructor(private readonly prisma: PrismaService) {}

  private defaults(): Stored {
    const own = (process.env.PUBLIC_BASE_URL ?? '').trim();
    let servers: string[] = [];
    try {
      if (own.startsWith('https://')) servers = [normalizeServer(own)];
    } catch {
      /* no usable public address */
    }
    return { servers, updatedAt: new Date(0).toISOString() };
  }

  private async stored(): Promise<Stored> {
    const row = await this.prisma.setting.findUnique({ where: { key: KEY } });
    return row ? ({ ...this.defaults(), ...(row.value as Partial<Stored>) } as Stored) : this.defaults();
  }

  private save(value: Stored) {
    return this.prisma.setting.upsert({ where: { key: KEY }, update: { value: value as object }, create: { key: KEY, value: value as object } });
  }

  /** What the customer app reads (GET store/servers). */
  async publicList() {
    const s = await this.stored();
    return { servers: s.servers, updatedAt: s.updatedAt };
  }

  /** For the admin app: the list and how the GitHub copy is doing. */
  async adminView() {
    const s = await this.stored();
    const d = directory();
    return {
      servers: s.servers,
      updatedAt: s.updatedAt,
      directory: { configured: !!d.token, url: d.url, syncedAt: s.directorySyncedAt ?? null, error: s.directoryError ?? null },
    };
  }

  async set(list: string[]) {
    const servers = [...new Set(list.map((s) => s.trim()).filter(Boolean).map(normalizeServer))];
    if (!servers.length) throw new BadRequestException('أضف عنواناً واحداً على الأقل');
    if (servers.length > MAX) throw new BadRequestException(`الحد الأقصى ${MAX} عناوين`);
    const value: Stored = { ...(await this.stored()), servers, updatedAt: new Date().toISOString() };
    await this.save(value);
    await this.syncDirectory();
    return this.adminView();
  }

  /** Writes the current list to the GitHub file. Never throws; the outcome is kept for the admin app. */
  async syncDirectory() {
    const d = directory();
    if (!d.token) return;
    const s = await this.stored();
    try {
      await this.writeFile(d, JSON.stringify({ servers: s.servers, updatedAt: s.updatedAt }, null, 2) + '\n');
      await this.save({ ...s, directorySyncedAt: new Date().toISOString(), directoryError: null });
    } catch (e) {
      const msg = (e as Error).message.slice(0, 300);
      this.log.warn(`Directory sync failed: ${msg}`);
      await this.save({ ...s, directoryError: msg });
    }
  }

  private async gh(d: ReturnType<typeof directory>, path: string, init: RequestInit = {}) {
    const res = await fetch(`https://api.github.com/repos/${d.repo}${path}`, {
      ...init,
      headers: {
        Authorization: `Bearer ${d.token}`,
        Accept: 'application/vnd.github+json',
        'X-GitHub-Api-Version': '2022-11-28',
        'Content-Type': 'application/json',
        'User-Agent': 'dejavu-api',
      },
      signal: AbortSignal.timeout(20_000),
    });
    return res;
  }

  private async writeFile(d: ReturnType<typeof directory>, content: string) {
    const fileUrl = `/contents/${encodeURIComponent(d.path)}`;
    let sha: string | undefined;
    const current = await this.gh(d, `${fileUrl}?ref=${encodeURIComponent(d.branch)}`);
    if (current.ok) {
      sha = ((await current.json()) as { sha?: string }).sha;
    } else if (current.status === 404) {
      await this.ensureBranch(d);
    } else {
      throw new Error(`GitHub ${current.status}: ${await current.text()}`);
    }
    const res = await this.gh(d, fileUrl, {
      method: 'PUT',
      body: JSON.stringify({ message: 'Update app server list', content: Buffer.from(content).toString('base64'), branch: d.branch, ...(sha ? { sha } : {}) }),
    });
    if (!res.ok) throw new Error(`GitHub ${res.status}: ${await res.text()}`);
  }

  /** Creates the directory branch from the repository's default branch the first time. */
  private async ensureBranch(d: ReturnType<typeof directory>) {
    const ref = await this.gh(d, `/git/ref/heads/${encodeURIComponent(d.branch)}`);
    if (ref.ok) return;
    const repo = await this.gh(d, '');
    if (!repo.ok) throw new Error(`GitHub ${repo.status}: ${await repo.text()}`);
    const base = ((await repo.json()) as { default_branch: string }).default_branch;
    const head = await this.gh(d, `/git/ref/heads/${encodeURIComponent(base)}`);
    if (!head.ok) throw new Error(`GitHub ${head.status}: ${await head.text()}`);
    const sha = ((await head.json()) as { object: { sha: string } }).object.sha;
    const made = await this.gh(d, '/git/refs', { method: 'POST', body: JSON.stringify({ ref: `refs/heads/${d.branch}`, sha }) });
    if (!made.ok && made.status !== 422) throw new Error(`GitHub ${made.status}: ${await made.text()}`);
  }
}
