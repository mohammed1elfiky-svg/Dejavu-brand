import { BadRequestException, HttpException, HttpStatus, Injectable, Logger, NotFoundException } from '@nestjs/common';
import { ChatRole } from '../generated/prisma/client';
import { CatalogService } from '../catalog/catalog.service';
import { StoreProductsQueryDto } from '../catalog/dto/catalog.dto';
import { formatAmount } from '../common/currency.service';
import { OwnerAlertsService } from '../notifications/owner-alerts.service';
import { PrismaService } from '../prisma/prisma.service';
import { GeminiClient, GeminiContent, GeminiPart, GeminiTool } from './gemini.client';

export interface ChatSettings {
  enabled: boolean;
  /** The owner's instructions: tone, policies, what to say and not to say. */
  instructions: string;
  /** First message shown when the chat opens. */
  welcome: string;
  /** Staff WhatsApp number the assistant hands customers over to. */
  whatsapp: string;
  /** Messages a customer may send per day. */
  dailyLimit: number;
  /** Photos a customer may send per day. */
  imageLimit: number;
  model: string;
}

const KEY = 'chat.settings';
const DEFAULTS: ChatSettings = {
  enabled: false,
  instructions:
    'أنت مساعدة متجر ديجافو للأحذية والشنط النسائية في اليمن.\n- ردّي باللهجة اليمنية البسيطة وباختصار ولطف.\n- لا تذكري سعراً أو مقاساً إلا من بيانات المتجر.\n- إذا كان السؤال عن شكوى أو مشكلة في طلب، حوّليها لخدمة العملاء عبر واتساب.',
  welcome: 'أهلاً بك في ديجافو 👋 كيف أقدر أساعدك؟ تقدري ترسلي لي صورة أي حذاء أو شنطة وأدوّر لك على شبهها.',
  whatsapp: '',
  dailyLimit: 30,
  imageLimit: 10,
  model: 'gemini-flash-latest',
};

/** Fixed rules on top of the owner's instructions. */
const RULES = `قواعد ثابتة:
- استخدمي الأدوات للحصول على المنتجات والأسعار والمقاسات وحالة الطلبات ومعلومات المتجر، ولا تخترعي أي معلومة.
- عند عرض منتجات اذكري الاسم والسعر والمقاسات المتوفرة باختصار؛ التطبيق يعرض بطاقات المنتجات تحت ردك.
- إذا أرسل العميل صورة: صفي ما فيها (النوع، اللون، الشكل، الكعب، الخامة) ثم ابحثي عن منتجات مشابهة بكلمات بسيطة بالعربي مثل "كعب أسود" أو "شنطة بيج".
- إذا كانت الصورة إيصال تحويل فوجّهيه لرفعه من شاشة "شحن المحفظة". إذا كانت صورة قطعة تالفة أو مشكلة في طلب فاعتذري واستخدمي أداة التحويل لخدمة العملاء.
- لا تستطيعين إنشاء طلب أو تعديل سعر أو إلغاء طلب؛ وجّهي العميل لخطوات التطبيق.
- إذا لم تعرفي الإجابة أو طلب العميل التحدث مع موظف فاستخدمي أداة التحويل لخدمة العملاء.`;

const TOOLS: GeminiTool[] = [
  {
    name: 'search_products',
    description: 'يبحث في منتجات المتجر المتوفرة. استخدميها لأي سؤال عن منتج أو سعر أو مقاس أو لون.',
    parameters: {
      type: 'object',
      properties: {
        query: { type: 'string', description: 'كلمات البحث بالعربي، مثل: كعب أسود، شنطة يد، صندل' },
        size: { type: 'string', description: 'المقاس المطلوب إن ذُكر، مثل 38' },
        color: { type: 'string', description: 'اللون إن ذُكر' },
        maxPrice: { type: 'number', description: 'أعلى سعر بالريال السعودي إن ذُكر' },
      },
    },
  },
  { name: 'my_orders', description: 'آخر طلبات هذا العميل وحالتها (للسؤال: وين طلبي؟).' },
  { name: 'store_info', description: 'معلومات المتجر: العنوان، الدوام، سياسة الاستبدال، طرق الدفع، الشحن والدفع عند الاستلام حسب المحافظة.' },
  { name: 'handover', description: 'تحويل العميل لموظف خدمة العملاء عبر واتساب (شكوى، مشكلة في طلب، أو سؤال لا تعرفين جوابه).' },
];

const ORDER_STATUS: Record<string, string> = {
  PENDING: 'بانتظار التأكيد',
  CONFIRMED: 'تم التأكيد',
  PROCESSING: 'قيد التجهيز',
  SHIPPED: 'تم الشحن',
  DELIVERED: 'تم التسليم',
  COMPLETED: 'مكتمل',
  CANCELLED: 'ملغي',
  RETURNED: 'لم يتم الاستلام',
};

/** The shopping assistant in the customer app, answered by Gemini with the store's own data. */
@Injectable()
export class ChatService {
  private readonly log = new Logger('Chat');

  constructor(
    private readonly prisma: PrismaService,
    private readonly gemini: GeminiClient,
    private readonly catalog: CatalogService,
    private readonly alerts: OwnerAlertsService,
  ) {}

  // ------------------------------------------------------------- settings ---

  async settings(): Promise<ChatSettings> {
    const row = await this.prisma.setting.findUnique({ where: { key: KEY } });
    const saved = { ...DEFAULTS, ...((row?.value ?? {}) as Partial<ChatSettings>) };
    // The old default was slow (and is being retired): the owner never chose it on purpose.
    if (saved.model === 'gemini-2.5-flash') saved.model = DEFAULTS.model;
    return saved;
  }

  async setSettings(dto: Partial<ChatSettings>) {
    const value = { ...(await this.settings()), ...dto };
    await this.prisma.setting.upsert({ where: { key: KEY }, update: { value: value as object }, create: { key: KEY, value: value as object } });
    return { ...value, configured: this.gemini.configured };
  }

  // ------------------------------------------------------------- customer ---

  /** The conversation so far, and what the chat screen needs. */
  async history(customerId: string) {
    const s = await this.settings();
    const rows = await this.prisma.chatMessage.findMany({ where: { customerId }, orderBy: { createdAt: 'desc' }, take: 60 });
    rows.reverse();
    const ids = [...new Set(rows.flatMap((r) => r.productIds))];
    const products = new Map((await this.catalog.storeProductsByIds(ids, customerId)).map((p) => [p.id, p]));
    return {
      enabled: s.enabled && this.gemini.configured,
      welcome: s.welcome,
      whatsapp: s.whatsapp,
      messages: rows.map((r) => ({
        id: r.id,
        role: r.role,
        text: r.text,
        imageFileId: r.imageFileId,
        handover: r.handover,
        reported: !!r.reportedAt,
        createdAt: r.createdAt,
        products: r.productIds.map((id) => products.get(id)).filter((p): p is NonNullable<typeof p> => !!p),
      })),
    };
  }

  /** The customer flags an assistant answer as wrong or offensive; the owner is told. */
  async report(customerId: string, messageId: string, reason?: string) {
    const m = await this.prisma.chatMessage.findFirst({ where: { id: messageId, customerId } });
    if (!m) throw new NotFoundException('الرسالة غير موجودة');
    if (m.role !== ChatRole.BOT) throw new BadRequestException('يمكن الإبلاغ عن ردود المساعد فقط');
    if (m.reportedAt) return { reported: true };
    const why = reason?.trim() || null;
    await this.prisma.chatMessage.update({ where: { id: m.id }, data: { reportedAt: new Date(), reportReason: why } });
    const customer = await this.prisma.customer.findUnique({ where: { id: customerId }, select: { name: true } });
    const quote = m.text.length > 200 ? `${m.text.slice(0, 197)}…` : m.text;
    await this.alerts.raise('CHAT_REPORT', 'إبلاغ عن رد المساعد', `${customer?.name ?? 'عميل'}${why ? `: ${why}` : ''}\n«${quote}»`, { customerId, messageId: m.id });
    return { reported: true };
  }

  async clear(customerId: string) {
    await this.prisma.chatMessage.deleteMany({ where: { customerId } });
  }

  /** The customer writes (and maybe sends a photo); the assistant answers. */
  async send(customerId: string, dto: { text?: string; imageFileId?: string }) {
    const s = await this.settings();
    if (!s.enabled || !this.gemini.configured) throw new HttpException('المساعد غير متاح حالياً', HttpStatus.SERVICE_UNAVAILABLE);
    const text = (dto.text ?? '').trim();
    if (!text && !dto.imageFileId) throw new BadRequestException('اكتب رسالة أو أرسل صورة');

    const dayStart = new Date(Date.now() - 24 * 3600_000);
    const [sentToday, imagesToday] = await Promise.all([
      this.prisma.chatMessage.count({ where: { customerId, role: ChatRole.USER, createdAt: { gte: dayStart } } }),
      dto.imageFileId ? this.prisma.chatMessage.count({ where: { customerId, role: ChatRole.USER, imageFileId: { not: null }, createdAt: { gte: dayStart } } }) : 0,
    ]);
    if (sentToday >= s.dailyLimit) throw new HttpException('وصلت للحد اليومي للرسائل، جرّب بكرة أو تواصل معنا عبر واتساب', HttpStatus.TOO_MANY_REQUESTS);
    if (dto.imageFileId && imagesToday >= s.imageLimit) throw new HttpException('وصلت للحد اليومي للصور', HttpStatus.TOO_MANY_REQUESTS);

    let image: { mime: string; data: Buffer } | null = null;
    if (dto.imageFileId) {
      const f = await this.prisma.storedFile.findUnique({ where: { id: dto.imageFileId }, select: { customerId: true, mimeType: true, data: true } });
      if (!f || f.customerId !== customerId || !f.mimeType.startsWith('image/')) throw new NotFoundException('Image not found');
      image = { mime: f.mimeType, data: Buffer.from(f.data) };
    }

    const customer = await this.prisma.customer.findUniqueOrThrow({ where: { id: customerId }, select: { name: true, governorate: true } });
    const past = await this.prisma.chatMessage.findMany({ where: { customerId }, orderBy: { createdAt: 'desc' }, take: 12, select: { role: true, text: true, imageFileId: true } });
    past.reverse();
    await this.prisma.chatMessage.create({ data: { customerId, role: ChatRole.USER, text, imageFileId: dto.imageFileId ?? null } });

    const contents: GeminiContent[] = past
      .filter((m) => m.text || m.imageFileId)
      .map((m) => ({ role: m.role === ChatRole.USER ? 'user' : 'model', parts: [{ text: m.text || '[صورة]' }] }));
    const now: GeminiPart[] = [];
    if (image) now.push({ inline_data: { mime_type: image.mime, data: image.data.toString('base64') } });
    now.push({ text: text || 'هذه صورة، هل عندكم شبهها؟' });
    contents.push({ role: 'user', parts: now });

    const system = `${s.instructions}\n\n${RULES}\n\nالعميل: ${customer.name}${customer.governorate ? ` من ${customer.governorate}` : ''}. الأسعار بالريال السعودي.`;
    const shown: string[] = [];
    let handover = false;
    let answer = '';
    try {
      for (let round = 0; round < 4; round++) {
        const parts = await this.gemini.generate({ model: s.model, system, contents, tools: TOOLS });
        const calls = parts.filter((p): p is Extract<GeminiPart, { functionCall: unknown }> => 'functionCall' in p);
        const said = parts
          .filter((p): p is { text: string } => 'text' in p)
          .map((p) => p.text)
          .join('')
          .trim();
        if (!calls.length) {
          answer = said;
          break;
        }
        contents.push({ role: 'model', parts });
        const results: GeminiPart[] = [];
        for (const c of calls) {
          const out = await this.tool(c.functionCall.name, c.functionCall.args ?? {}, customerId, s, shown);
          if (c.functionCall.name === 'handover') handover = true;
          results.push({ functionResponse: { name: c.functionCall.name, response: out } });
        }
        contents.push({ role: 'user', parts: results });
        answer = said;
      }
    } catch (e) {
      this.log.warn(`Chat failed: ${(e as Error).message}`);
      answer = '';
    }
    if (!answer) answer = handover ? 'حوّلتك لخدمة العملاء، اضغط زر واتساب وبيردوا عليك بأسرع وقت 🌷' : 'عذراً، ما قدرت أرد الآن. جرّب مرة ثانية أو تواصل معنا عبر واتساب.';
    const productIds = [...new Set(shown)].slice(0, 8);
    const saved = await this.prisma.chatMessage.create({ data: { customerId, role: ChatRole.BOT, text: answer, productIds, handover } });
    return {
      id: saved.id,
      role: saved.role,
      text: saved.text,
      imageFileId: null,
      handover,
      createdAt: saved.createdAt,
      whatsapp: s.whatsapp,
      products: await this.catalog.storeProductsByIds(productIds, customerId),
    };
  }

  /** Runs one tool the model asked for; product ids it returns are shown under the answer. */
  private async tool(name: string, args: Record<string, unknown>, customerId: string, s: ChatSettings, shown: string[]): Promise<Record<string, unknown>> {
    if (name === 'search_products') {
      const query = String(args.query ?? '').trim();
      const size = args.size ? String(args.size).trim() : '';
      const color = args.color ? String(args.color).trim() : '';
      const maxPrice = typeof args.maxPrice === 'number' ? args.maxPrice : undefined;
      const search = async (q?: string) => (await this.catalog.storeProducts(Object.assign(new StoreProductsQueryDto(), { q, page: 1, pageSize: 20 }), customerId, 'assistant')).items;
      let items = await search(query || undefined);
      // Several words that found nothing: try each word alone.
      if (!items.length && query.includes(' ')) {
        for (const w of query.split(/\s+/).filter((x) => x.length > 2)) {
          items = await search(w);
          if (items.length) break;
        }
      }
      const fits = items.filter((p) => {
        if (maxPrice !== undefined && Number(p.basePrice) > maxPrice) return false;
        const vs = p.variants.filter((v) => v.inStock);
        if (!vs.length) return false;
        if (size && !vs.some((v) => v.size === size)) return false;
        if (color && !vs.some((v) => (v.color ?? '').includes(color)) && !p.nameAr.includes(color)) return false;
        return true;
      });
      const list = fits.slice(0, 6);
      shown.push(...list.map((p) => p.id));
      return {
        found: list.length,
        products: list.map((p) => ({
          name: p.nameAr,
          price: formatAmount(p.basePrice),
          oldPrice: p.compareAtPrice ? formatAmount(p.compareAtPrice) : null,
          offer: p.promotion ? `${p.promotion.name} ${p.promotion.percent}%` : null,
          sizes: [...new Set(p.variants.filter((v) => v.inStock).map((v) => v.size).filter(Boolean))],
          colors: [...new Set(p.variants.filter((v) => v.inStock).map((v) => v.color).filter(Boolean))],
          category: p.category.nameAr,
        })),
      };
    }
    if (name === 'my_orders') {
      const orders = await this.prisma.order.findMany({
        where: { customerId },
        orderBy: { placedAt: 'desc' },
        take: 5,
        select: { number: true, status: true, grandTotal: true, placedAt: true, channel: true, shippingReceiptFileId: true, items: { select: { productName: true, quantity: true } } },
      });
      return {
        orders: orders.map((o) => ({
          number: o.number,
          status: ORDER_STATUS[o.status] ?? o.status,
          total: formatAmount(o.grandTotal),
          date: o.placedAt.toISOString().slice(0, 10),
          where: o.channel === 'POS' ? 'من المحل' : 'من التطبيق',
          shippingReceipt: o.shippingReceiptFileId ? 'صورة سند الشحن موجودة في تفاصيل الطلب' : null,
          items: o.items.map((i) => `${i.productName} × ${i.quantity}`),
        })),
      };
    }
    if (name === 'store_info') {
      const [about, methods, zones] = await Promise.all([
        this.prisma.setting.findUnique({ where: { key: 'store.about' } }),
        this.prisma.setting.findUnique({ where: { key: 'store.paymentMethods' } }),
        this.prisma.shippingZone.findMany({ where: { codEnabled: false }, select: { governorate: true } }),
      ]);
      return {
        about: about?.value ?? {},
        paymentMethods: methods?.value ?? null,
        cashOnDeliveryNotIn: zones.map((z) => z.governorate),
        shipping: 'رسوم الشحن تُدفع لشركة الشحن عند الاستلام',
      };
    }
    if (name === 'handover') return { whatsapp: s.whatsapp || null, note: 'سيظهر للعميل زر واتساب للتواصل مع الموظف' };
    return { error: 'unknown tool' };
  }

  // ---------------------------------------------------------------- admin ---

  /** Asks Gemini a tiny question and says what happened (key wrong, model refused, quota…). */
  async testConnection() {
    const s = await this.settings();
    if (!this.gemini.configured) return { ok: false, model: s.model, error: 'مفتاح Gemini غير مضاف في الخادم (GEMINI_API_KEY)' };
    try {
      const answer = await this.gemini.text(s.model, 'أجب بكلمة واحدة.', [{ text: 'قل: جاهز' }]);
      return { ok: true, model: s.model, answer };
    } catch (e) {
      const status = (e as { status?: number }).status ?? 0;
      const hint =
        status === 400 || status === 403
          ? 'المفتاح غير صحيح أو غير مفعّل لهذه الخدمة. أنشئ مفتاحاً جديداً من aistudio.google.com'
          : status === 429
            ? 'تجاوزت الحد المجاني من جوجل. انتظر قليلاً أو فعّل الدفع في Google AI Studio'
            : status === 404
              ? 'اسم النموذج غير متاح لهذا المفتاح'
              : 'تعذّر الاتصال بجوجل';
      return { ok: false, model: s.model, status, error: `${hint} — ${(e as Error).message}`, available: (await this.gemini.models().catch(() => [])).slice(0, 15) };
    }
  }

  /** Customers who chatted, newest first. */
  async conversations(take = 50) {
    const rows = await this.prisma.$queryRaw<{ customer_id: string; name: string; phone: string; messages: bigint; images: bigint; handovers: bigint; reports: bigint; last_at: Date; last_text: string }[]>`
      SELECT m.customer_id::text, c.name, c.phone, COUNT(*) AS messages,
             COUNT(*) FILTER (WHERE m.image_file_id IS NOT NULL) AS images,
             COUNT(*) FILTER (WHERE m.handover) AS handovers,
             COUNT(*) FILTER (WHERE m.reported_at IS NOT NULL) AS reports,
             MAX(m.created_at) AS last_at,
             (SELECT text FROM chat_messages x WHERE x.customer_id = m.customer_id ORDER BY created_at DESC LIMIT 1) AS last_text
      FROM chat_messages m JOIN customers c ON c.id = m.customer_id
      GROUP BY m.customer_id, c.name, c.phone
      ORDER BY last_at DESC LIMIT ${Math.min(Math.max(take, 1), 200)}`;
    return rows.map((r) => ({
      customer: { id: r.customer_id, name: r.name, phone: r.phone },
      messages: Number(r.messages),
      images: Number(r.images),
      handovers: Number(r.handovers),
      reports: Number(r.reports),
      lastAt: r.last_at,
      lastText: r.last_text,
    }));
  }

  async conversation(customerId: string) {
    const rows = await this.prisma.chatMessage.findMany({ where: { customerId }, orderBy: { createdAt: 'asc' }, take: 500 });
    return rows;
  }
}
