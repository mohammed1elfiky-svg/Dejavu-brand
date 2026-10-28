import { ConflictException, Injectable, Logger } from '@nestjs/common';
import { Prisma, TransferSource } from '../generated/prisma/client';
import { GeminiClient } from '../chat/gemini.client';
import { paginated, PaginationQueryDto } from '../common/dto/pagination.dto';
import { dec } from '../common/utils/money';
import { PrismaService, Tx } from '../prisma/prisma.service';

/** What could be read off a transfer / deposit slip photo. */
export interface SlipReading {
  reference: string | null;
  amount: string | null;
  currency: string | null;
  bankName: string | null;
}

const EMPTY: SlipReading = { reference: null, amount: null, currency: null, bankName: null };
const CURRENCY_WORDS: [RegExp, string][] = [
  [/(SAR|SR|ريال سعودي|ر\.س)/i, 'SAR'],
  [/(USD|\$|دولار)/i, 'USD'],
  [/(YER|ريال يمني|ر\.ي)/i, 'YER'],
];

/** Reference numbers compare by their letters and digits only: "TRX-00 1234" = "trx001234". */
export const normalizeReference = (ref: string | null | undefined) => {
  const r = (ref ?? '').toUpperCase().replace(/[٠-٩]/g, (d) => String('٠١٢٣٤٥٦٧٨٩'.indexOf(d))).replace(/[^A-Z0-9]/g, '');
  return r.length >= 4 ? r : null;
};

/**
 * The register of every bank transfer the store accepted (app top-ups and till sales), keyed
 * by its reference number. A slip photo is read with Gemini to find that number; the same
 * reference (or the same photo) never goes through twice.
 */
@Injectable()
export class TransfersService {
  private readonly log = new Logger('Transfers');
  /** Slip readings by file id, so the preview and the request read a photo once. */
  private readonly readings = new Map<string, { at: number; reading: SlipReading }>();

  constructor(
    private readonly prisma: PrismaService,
    private readonly gemini: GeminiClient,
  ) {}

  /** Reads the reference number (and amount) off a slip photo; nothing when it cannot. */
  async read(fileId: string): Promise<SlipReading> {
    const cached = this.readings.get(fileId);
    if (cached && Date.now() - cached.at < 3600_000) return cached.reading;
    if (!this.gemini.configured) return EMPTY;
    const file = await this.prisma.storedFile.findUnique({ where: { id: fileId }, select: { mimeType: true, data: true } });
    if (!file || !file.mimeType.startsWith('image/')) return EMPTY;
    try {
      const settings = await this.prisma.setting.findUnique({ where: { key: 'chat.settings' } });
      const model = ((settings?.value as { model?: string } | null)?.model ?? 'gemini-flash-latest').replace(/^gemini-2\.5-flash$/, 'gemini-flash-latest');
      const raw = await this.gemini.text(
        model,
        'You read bank transfer receipts and deposit slips (Arabic or English, often from Yemeni and Saudi banks and exchange companies). Answer JSON only.',
        [
          { inline_data: { mime_type: file.mimeType, data: Buffer.from(file.data).toString('base64') } },
          {
            text: `Return {"reference": "the transfer / transaction / receipt reference number exactly as printed (رقم الحوالة، رقم العملية، رقم المرجع), or null", "amount": "the amount transferred as digits, or null", "currency": "ISO code (SAR, YER, USD...) or null", "bankName": "the bank or exchange company, or null"}. Never invent a number.`,
          },
        ],
        true,
      );
      const d = JSON.parse(raw.replace(/^```(?:json)?|```$/g, '').trim()) as Record<string, unknown>;
      const str = (v: unknown) => (typeof v === 'string' && v.trim() && v.trim().toLowerCase() !== 'null' ? v.trim() : typeof v === 'number' ? String(v) : null);
      const amount = str(d.amount)?.replace(/[^\d.]/g, '') || null;
      let currency = str(d.currency)?.toUpperCase() ?? null;
      if (currency && !/^[A-Z]{3}$/.test(currency)) currency = CURRENCY_WORDS.find(([re]) => re.test(currency!))?.[1] ?? null;
      const reading = { reference: str(d.reference), amount: amount && Number(amount) > 0 ? amount : null, currency, bankName: str(d.bankName) };
      this.readings.set(fileId, { at: Date.now(), reading });
      if (this.readings.size > 500) this.readings.delete(this.readings.keys().next().value!);
      return reading;
    } catch (e) {
      this.log.warn(`Could not read slip ${fileId}: ${(e as Error).message}`);
      return EMPTY;
    }
  }

  /**
   * The reference to record for a transfer: the one read off the photo when there is one
   * (a typed number cannot override it), else the one typed.
   */
  async referenceOf(fileId: string | null | undefined, typed: string | null | undefined) {
    const reading = fileId ? await this.read(fileId) : EMPTY;
    const read = normalizeReference(reading.reference);
    return { reference: read ?? normalizeReference(typed), extracted: !!read, reading };
  }

  /**
   * Throws when this transfer was already used: by its reference, or by the very same photo
   * (this upload or an identical one), on the register or on a top-up other than [exceptTopUpId].
   */
  async assertNew(db: Tx | PrismaService, reference: string | null, fileId?: string | null, exceptTopUpId?: string | null) {
    if (reference) {
      const used = await db.bankTransfer.findUnique({ where: { reference } });
      if (used) throw this.duplicate(reference, used.createdAt);
    }
    if (!fileId) return;
    const file = await db.storedFile.findUnique({ where: { id: fileId }, select: { sha256: true } });
    const files = [fileId, ...(file?.sha256 ? (await db.storedFile.findMany({ where: { sha256: file.sha256, id: { not: fileId } }, select: { id: true } })).map((f) => f.id) : [])];
    const used = await db.bankTransfer.findFirst({ where: { fileId: { in: files } } });
    if (used) throw this.duplicate(used.reference, used.createdAt);
    const topUp = await db.walletTopUp.findFirst({
      where: { receiptFileId: { in: files }, status: { not: 'REJECTED' }, ...(exceptTopUpId && { id: { not: exceptTopUpId } }) },
    });
    if (topUp) throw this.duplicate(topUp.reference ? normalizeReference(topUp.reference) : null, topUp.createdAt);
  }

  /** Records a transfer; a reference already on the register is refused. */
  async register(
    tx: Tx,
    t: {
      reference: string | null;
      source: TransferSource;
      extracted?: boolean;
      customerId?: string | null;
      currency?: string | null;
      amount?: Prisma.Decimal | string | null;
      bankName?: string | null;
      fileId?: string | null;
      topUpId?: string | null;
      orderId?: string | null;
      userId?: string | null;
    },
  ) {
    await this.assertNew(tx, t.reference, t.fileId, t.topUpId);
    if (!t.reference && !t.fileId) return null;
    try {
      return await tx.bankTransfer.create({
        data: {
          reference: t.reference,
          source: t.source,
          extracted: !!t.extracted,
          customerId: t.customerId ?? null,
          currency: t.currency ?? null,
          amount: t.amount === null || t.amount === undefined ? null : dec(t.amount),
          bankName: t.bankName ?? null,
          fileId: t.fileId ?? null,
          topUpId: t.topUpId ?? null,
          orderId: t.orderId ?? null,
          createdById: t.userId ?? null,
        },
      });
    } catch (e) {
      if ((e as { code?: string }).code === 'P2002') throw this.duplicate(t.reference, new Date());
      throw e;
    }
  }

  /** Frees a reference again (the top-up or sale it paid for was reversed). */
  async release(tx: Tx, where: { topUpId?: string; orderId?: string }) {
    await tx.bankTransfer.deleteMany({ where });
  }

  async list(q: PaginationQueryDto & { q?: string; source?: TransferSource }) {
    const term = normalizeReference(q.q);
    const where: Prisma.BankTransferWhereInput = { source: q.source, ...(q.q && { OR: [{ reference: { contains: term ?? q.q.toUpperCase() } }, { bankName: { contains: q.q, mode: 'insensitive' } }] }) };
    const [rows, total] = await Promise.all([
      this.prisma.bankTransfer.findMany({ where, orderBy: { createdAt: 'desc' }, skip: q.skip, take: q.pageSize }),
      this.prisma.bankTransfer.count({ where }),
    ]);
    const customers = new Map(
      (await this.prisma.customer.findMany({ where: { id: { in: rows.map((r) => r.customerId).filter((x): x is string => !!x) } }, select: { id: true, name: true, phone: true } })).map((c) => [c.id, c]),
    );
    return paginated(
      rows.map((r) => ({ ...r, customer: r.customerId ? (customers.get(r.customerId) ?? null) : null })),
      total,
      q,
    );
  }

  private duplicate(reference: string | null, at: Date) {
    const day = at.toISOString().slice(0, 10);
    return new ConflictException({
      statusCode: 409,
      code: 'DUPLICATE_TRANSFER',
      message: reference ? `هذه الحوالة مستخدمة سابقاً (رقم المرجع ${reference} بتاريخ ${day})` : `صورة الإشعار هذه مستخدمة سابقاً (${day})`,
    });
  }
}
