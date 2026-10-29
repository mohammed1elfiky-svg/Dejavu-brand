import { BadRequestException, ForbiddenException, Injectable, Logger, NotFoundException, OnModuleDestroy, OnModuleInit, UnprocessableEntityException } from '@nestjs/common';
import { NotificationType, PostKind, Prisma, ProductStatus } from '../generated/prisma/client';
import { businessDate } from '../common/utils/dates';
import { dec, money } from '../common/utils/money';
import { normalizeUsername } from '../common/username';
import { NotificationsService } from '../notifications/notifications.service';
import { PrismaService, Tx } from '../prisma/prisma.service';
import { CartService } from '../sales/cart.service';
import { LoyaltyService } from '../sales/loyalty';
import { OrdersService } from '../sales/orders.service';
import { CustomerAccountsService } from '../treasury/customer-accounts.service';
import { firstName, publicCard, publicSelect } from './profile';

export interface NewPost {
  kind?: PostKind;
  caption?: string;
  images: string[];
  tags?: { productId: string; variantId?: string; imageIndex?: number; x?: number; y?: number }[];
}

export interface AmbassadorSettings {
  enabled: boolean;
  /** Percent of what was bought through a post. */
  percent: number;
  /** Paid as loyalty points or as wallet credit. */
  kind: 'POINTS' | 'WALLET';
}

const AMBASSADOR_KEY = 'ambassador.settings';
const AMBASSADOR_DEFAULTS: AmbassadorSettings = { enabled: true, percent: 3, kind: 'POINTS' };
/** "منسّق متميز": this many followers, or this many likes on all posts. */
const STYLIST_FOLLOWERS = 50;
const STYLIST_LIKES = 200;
const MENTION = /@([A-Za-z]{2}[A-Za-z0-9._]{1,28})/g;

const tagInclude = {
  product: { select: { id: true, nameAr: true, slug: true, basePrice: true, status: true, images: { take: 1, orderBy: { sortOrder: 'asc' }, select: { url: true } } } },
} satisfies Prisma.PostTagInclude;

const postInclude = {
  author: { select: publicSelect },
  tags: { include: tagInclude },
  mentions: true,
} satisfies Prisma.PostInclude;

type PostRow = Prisma.PostGetPayload<{ include: typeof postInclude }>;

/**
 * Customers' social side: posts of their looks (with products tagged on the photo), follows,
 * likes, comments, mentions and birthdays; and ambassador rewards when someone buys through a
 * post. Everything that should reach a person goes to their notifications (pushed by FCM).
 */
@Injectable()
export class SocialService implements OnModuleInit, OnModuleDestroy {
  private readonly log = new Logger('Social');
  private timer?: NodeJS.Timeout;

  constructor(
    private readonly prisma: PrismaService,
    private readonly notifications: NotificationsService,
    private readonly cart: CartService,
    private readonly orders: OrdersService,
    private readonly loyalty: LoyaltyService,
    private readonly accounts: CustomerAccountsService,
  ) {}

  onModuleInit() {
    // Ambassador rewards follow the order's life.
    this.orders.onPlaced = (tx, id) => this.recordAmbassador(tx, id);
    this.orders.onReceived = (tx, id) => this.payAmbassador(tx, id);
    this.orders.onVoided = (tx, id) => this.cancelAmbassador(tx, id);
    if (process.env.NODE_ENV === 'test') return;
    this.timer = setInterval(() => void this.birthdays().catch((e) => this.log.error(e)), 30 * 60_000);
    this.timer.unref();
  }

  onModuleDestroy() {
    if (this.timer) clearInterval(this.timer);
  }

  private get db() {
    return this.prisma as unknown as Tx;
  }

  private async notify(customerId: string, title: string, body: string, data: Record<string, string>) {
    await this.notifications.notifyCustomer(this.db, customerId, { type: NotificationType.SYSTEM, title, body, data });
  }

  private async me(customerId: string) {
    const c = await this.prisma.customer.findUniqueOrThrow({ where: { id: customerId }, select: { id: true, username: true, name: true } });
    return c;
  }

  private async requireUsername(customerId: string) {
    const c = await this.me(customerId);
    if (!c.username) throw new UnprocessableEntityException({ statusCode: 422, code: 'NO_USERNAME', message: 'اختر يوزراً لحسابك أولاً من «حسابي»' });
    return c as typeof c & { username: string };
  }

  // ---------------------------------------------------------------- posts ---

  async createPost(customerId: string, dto: NewPost) {
    const me = await this.requireUsername(customerId);
    const images = dto.images.map((u) => u.trim()).filter(Boolean);
    if (!images.length) throw new BadRequestException('أضف صورة واحدة على الأقل');
    const tags = dto.tags ?? [];
    if (tags.length) {
      const found = await this.prisma.product.count({ where: { id: { in: [...new Set(tags.map((t) => t.productId))] } } });
      if (found !== new Set(tags.map((t) => t.productId)).size) throw new NotFoundException('Some tagged products were not found');
    }
    const mentioned = await this.mentionedIn(dto.caption, customerId);
    const post = await this.prisma.post.create({
      data: {
        authorId: customerId,
        kind: dto.kind ?? PostKind.LOOK,
        caption: dto.caption?.trim() || null,
        images,
        tags: { create: tags.map((t) => ({ productId: t.productId, variantId: t.variantId ?? null, imageIndex: t.imageIndex ?? 0, x: t.x ?? null, y: t.y ?? null })) },
        mentions: { create: mentioned.map((id) => ({ customerId: id })) },
      },
    });
    for (const id of mentioned) await this.notify(id, `@${me.username} أشار إليك`, dto.caption?.slice(0, 120) ?? 'في منشور جديد', { post: post.id, screen: 'post' });
    return this.getPost(post.id, customerId);
  }

  /** Customers named with @username in [text] (not [selfId]). */
  private async mentionedIn(text: string | undefined | null, selfId: string) {
    const names = [...new Set([...(text ?? '').matchAll(MENTION)].map((m) => normalizeUsername(m[1])))].slice(0, 20);
    if (!names.length) return [];
    const rows = await this.prisma.customer.findMany({ where: { username: { in: names }, id: { not: selfId } }, select: { id: true } });
    return rows.map((r) => r.id);
  }

  async deletePost(customerId: string, postId: string) {
    const p = await this.prisma.post.findUnique({ where: { id: postId }, select: { authorId: true } });
    if (!p) throw new NotFoundException('Post not found');
    if (p.authorId !== customerId) throw new ForbiddenException('You can only delete your own posts');
    await this.prisma.post.delete({ where: { id: postId } });
  }

  async getPost(postId: string, viewerId?: string) {
    const p = await this.prisma.post.findUnique({ where: { id: postId }, include: postInclude });
    if (!p || p.isHidden) throw new NotFoundException('Post not found');
    return (await this.present([p], viewerId))[0];
  }

  /** FOLLOWING: posts of the people I follow, newest first. EXPLORE: the most liked and commented lately. */
  async feed(viewerId: string | undefined, tab: 'FOLLOWING' | 'EXPLORE', page = 1, pageSize = 20) {
    const take = Math.min(Math.max(pageSize, 1), 50);
    const skip = (Math.max(page, 1) - 1) * take;
    let rows: PostRow[];
    if (tab === 'FOLLOWING') {
      if (!viewerId) return { items: [], page, pageSize: take, hasMore: false };
      rows = await this.prisma.post.findMany({
        where: { isHidden: false, author: { followers: { some: { followerId: viewerId } } } },
        include: postInclude,
        orderBy: { createdAt: 'desc' },
        skip,
        take: take + 1,
      });
    } else {
      const ids = await this.prisma.$queryRaw<{ id: string }[]>`
        SELECT id::text FROM posts WHERE NOT is_hidden AND created_at > now() - interval '60 days'
        ORDER BY (like_count * 2 + comment_count) DESC, created_at DESC OFFSET ${skip} LIMIT ${take + 1}`;
      const found = await this.prisma.post.findMany({ where: { id: { in: ids.map((r) => r.id) } }, include: postInclude });
      rows = ids.map((r) => found.find((p) => p.id === r.id)!).filter(Boolean);
    }
    const hasMore = rows.length > take;
    return { items: await this.present(rows.slice(0, take), viewerId), page, pageSize: take, hasMore };
  }

  async userPosts(username: string, viewerId?: string, page = 1) {
    const author = await this.byUsername(username);
    const rows = await this.prisma.post.findMany({ where: { authorId: author.id, isHidden: false }, include: postInclude, orderBy: { createdAt: 'desc' }, skip: (page - 1) * 30, take: 30 });
    return this.present(rows, viewerId);
  }

  private async present(rows: PostRow[], viewerId?: string) {
    const ids = rows.map((r) => r.id);
    const liked = viewerId && ids.length ? new Set((await this.prisma.postLike.findMany({ where: { customerId: viewerId, postId: { in: ids } }, select: { postId: true } })).map((l) => l.postId)) : new Set<string>();
    const stylists = await this.stylists([...new Set(rows.map((r) => r.authorId))]);
    const mentionIds = [...new Set(rows.flatMap((r) => r.mentions.map((m) => m.customerId)))];
    const mentionNames = new Map((await this.prisma.customer.findMany({ where: { id: { in: mentionIds } }, select: { id: true, username: true } })).map((c) => [c.id, c.username]));
    return rows.map((r) => ({
      id: r.id,
      kind: r.kind,
      caption: r.caption,
      images: r.images,
      likeCount: r.likeCount,
      commentCount: r.commentCount,
      createdAt: r.createdAt,
      liked: liked.has(r.id),
      author: { ...publicCard(r.author), stylist: stylists.has(r.authorId) },
      mentions: r.mentions.map((m) => mentionNames.get(m.customerId)).filter(Boolean),
      tags: r.tags.map((t) => ({
        id: t.id,
        productId: t.productId,
        variantId: t.variantId,
        imageIndex: t.imageIndex,
        x: t.x,
        y: t.y,
        name: t.product.nameAr,
        slug: t.product.slug,
        price: t.product.basePrice,
        imageUrl: t.product.images[0]?.url ?? null,
        available: t.product.status === ProductStatus.ACTIVE,
      })),
    }));
  }

  /** Authors with many followers or likes get the "منسّق متميز" badge. */
  private async stylists(authorIds: string[]) {
    if (!authorIds.length) return new Set<string>();
    const [followers, likes] = await Promise.all([
      this.prisma.follow.groupBy({ by: ['followingId'], where: { followingId: { in: authorIds } }, _count: { _all: true } }),
      this.prisma.post.groupBy({ by: ['authorId'], where: { authorId: { in: authorIds } }, _sum: { likeCount: true } }),
    ]);
    const out = new Set<string>();
    for (const f of followers) if (f._count._all >= STYLIST_FOLLOWERS) out.add(f.followingId);
    for (const l of likes) if ((l._sum.likeCount ?? 0) >= STYLIST_LIKES) out.add(l.authorId);
    return out;
  }

  async like(customerId: string, postId: string) {
    const me = await this.requireUsername(customerId);
    const post = await this.prisma.post.findUnique({ where: { id: postId }, select: { authorId: true, isHidden: true } });
    if (!post || post.isHidden) throw new NotFoundException('Post not found');
    const created = await this.prisma.postLike.createMany({ data: [{ postId, customerId }], skipDuplicates: true });
    if (created.count) {
      await this.prisma.post.update({ where: { id: postId }, data: { likeCount: { increment: 1 } } });
      if (post.authorId !== customerId) await this.notify(post.authorId, `@${me.username} أعجبه منشورك ❤️`, 'اضغط لعرض المنشور', { post: postId, screen: 'post' });
    }
    return this.counts(postId, customerId);
  }

  async unlike(customerId: string, postId: string) {
    const removed = await this.prisma.postLike.deleteMany({ where: { postId, customerId } });
    if (removed.count) await this.prisma.post.update({ where: { id: postId }, data: { likeCount: { decrement: 1 } } });
    return this.counts(postId, customerId);
  }

  private async counts(postId: string, viewerId: string) {
    const p = await this.prisma.post.findUniqueOrThrow({ where: { id: postId }, select: { likeCount: true, commentCount: true } });
    const liked = (await this.prisma.postLike.count({ where: { postId, customerId: viewerId } })) > 0;
    return { ...p, liked };
  }

  async comments(postId: string, page = 1) {
    const rows = await this.prisma.postComment.findMany({ where: { postId, isHidden: false }, orderBy: { createdAt: 'asc' }, skip: (page - 1) * 50, take: 50 });
    const authors = new Map(
      (await this.prisma.customer.findMany({ where: { id: { in: [...new Set(rows.map((r) => r.authorId))] } }, select: publicSelect })).map((c) => [c.id, publicCard(c)]),
    );
    return rows.map((r) => ({ id: r.id, body: r.body, createdAt: r.createdAt, author: authors.get(r.authorId) ?? null }));
  }

  async comment(customerId: string, postId: string, body: string) {
    const me = await this.requireUsername(customerId);
    const text = body.trim();
    if (!text) throw new BadRequestException('اكتب التعليق');
    const post = await this.prisma.post.findUnique({ where: { id: postId }, select: { authorId: true, isHidden: true } });
    if (!post || post.isHidden) throw new NotFoundException('Post not found');
    const c = await this.prisma.$transaction(async (tx) => {
      const row = await tx.postComment.create({ data: { postId, authorId: customerId, body: text } });
      await tx.post.update({ where: { id: postId }, data: { commentCount: { increment: 1 } } });
      return row;
    });
    if (post.authorId !== customerId) await this.notify(post.authorId, `@${me.username} علّق على منشورك`, text.slice(0, 120), { post: postId, screen: 'post' });
    for (const id of await this.mentionedIn(text, customerId)) {
      if (id !== post.authorId) await this.notify(id, `@${me.username} أشار إليك في تعليق`, text.slice(0, 120), { post: postId, screen: 'post' });
    }
    return { id: c.id, body: c.body, createdAt: c.createdAt };
  }

  async deleteComment(customerId: string, commentId: string) {
    const c = await this.prisma.postComment.findUnique({ where: { id: commentId }, include: { post: { select: { authorId: true } } } });
    if (!c) throw new NotFoundException('Comment not found');
    // The writer, or the post's author, may remove it.
    if (c.authorId !== customerId && c.post.authorId !== customerId) throw new ForbiddenException('Not your comment');
    await this.prisma.$transaction([
      this.prisma.postComment.delete({ where: { id: commentId } }),
      this.prisma.post.update({ where: { id: c.postId }, data: { commentCount: { decrement: c.isHidden ? 0 : 1 } } }),
    ]);
  }

  // -------------------------------------------------------------- follows ---

  private async byUsername(raw: string) {
    const c = await this.prisma.customer.findFirst({ where: { username: normalizeUsername(raw), status: 'ACTIVE' }, select: { ...publicSelect, birthDate: true } });
    if (!c) throw new NotFoundException('لا يوجد حساب بهذا اليوزر');
    return c;
  }

  async follow(customerId: string, username: string) {
    const me = await this.requireUsername(customerId);
    const target = await this.byUsername(username);
    if (target.id === customerId) throw new BadRequestException('لا يمكنك متابعة نفسك');
    const created = await this.prisma.follow.createMany({ data: [{ followerId: customerId, followingId: target.id }], skipDuplicates: true });
    if (created.count) await this.notify(target.id, `@${me.username} بدأ متابعتك`, `${firstName(me.name)} أصبح من متابعيك`, { username: me.username, screen: 'user' });
    return this.profile(target.username!, customerId);
  }

  async unfollow(customerId: string, username: string) {
    const target = await this.byUsername(username);
    await this.prisma.follow.deleteMany({ where: { followerId: customerId, followingId: target.id } });
    return this.profile(target.username!, customerId);
  }

  /** A customer's public page: card, counts, whether I follow them, and their birthday (day and month). */
  async profile(username: string, viewerId?: string) {
    const c = await this.byUsername(username);
    const [followers, following, posts, isFollowing, stylist] = await Promise.all([
      this.prisma.follow.count({ where: { followingId: c.id } }),
      this.prisma.follow.count({ where: { followerId: c.id } }),
      this.prisma.post.count({ where: { authorId: c.id, isHidden: false } }),
      viewerId ? this.prisma.follow.count({ where: { followerId: viewerId, followingId: c.id } }) : Promise.resolve(0),
      this.stylists([c.id]),
    ]);
    return {
      ...publicCard(c),
      stylist: stylist.has(c.id),
      birthday: c.birthDate ? c.birthDate.toISOString().slice(5, 10) : null,
      counts: { followers, following, posts },
      isFollowing: isFollowing > 0,
      isMe: viewerId === c.id,
    };
  }

  async followList(username: string, which: 'followers' | 'following') {
    const c = await this.byUsername(username);
    const rows =
      which === 'followers'
        ? (await this.prisma.follow.findMany({ where: { followingId: c.id }, orderBy: { createdAt: 'desc' }, take: 500, include: { follower: { select: publicSelect } } })).map((f) => f.follower)
        : (await this.prisma.follow.findMany({ where: { followerId: c.id }, orderBy: { createdAt: 'desc' }, take: 500, include: { following: { select: publicSelect } } })).map((f) => f.following);
    return rows.map(publicCard);
  }

  /** The products a customer wishes for (their wishlist), so friends can gift one. */
  async wishlistOf(username: string) {
    const c = await this.byUsername(username);
    const rows = await this.prisma.wishlistItem.findMany({
      where: { customerId: c.id, product: { status: ProductStatus.ACTIVE } },
      orderBy: { createdAt: 'desc' },
      select: { product: { select: { id: true, nameAr: true, slug: true, basePrice: true, images: { take: 1, orderBy: { sortOrder: 'asc' }, select: { url: true } } } } },
    });
    return { owner: publicCard(c), items: rows.map((r) => ({ productId: r.product.id, name: r.product.nameAr, slug: r.product.slug, price: r.product.basePrice, imageUrl: r.product.images[0]?.url ?? null })) };
  }

  // ------------------------------------------------------------ birthdays ---

  private monthDay(d: Date) {
    return d.toISOString().slice(5, 10);
  }

  /** Customers with a username whose birthday is today (store time), people I follow first. */
  async birthdaysToday(viewerId?: string) {
    const today = this.monthDay(businessDate(process.env.STORE_TIMEZONE ?? 'Asia/Aden'));
    const rows = await this.prisma.$queryRaw<{ id: string }[]>`
      SELECT id::text FROM customers WHERE username IS NOT NULL AND status = 'ACTIVE' AND birth_date IS NOT NULL
        AND to_char(birth_date, 'MM-DD') = ${today} LIMIT 300`;
    const people = await this.prisma.customer.findMany({ where: { id: { in: rows.map((r) => r.id) } }, select: publicSelect });
    const mine = viewerId ? new Set((await this.prisma.follow.findMany({ where: { followerId: viewerId, followingId: { in: people.map((p) => p.id) } }, select: { followingId: true } })).map((f) => f.followingId)) : new Set<string>();
    return people
      .filter((p) => p.id !== viewerId)
      .map((p) => ({ ...publicCard(p), isFollowing: mine.has(p.id) }))
      .sort((a, b) => Number(b.isFollowing) - Number(a.isFollowing));
  }

  /**
   * Once a day: wishes the birthday person from the store, tells their followers it is today,
   * and three days before ("يقترب عيد ميلاد…") so friends can plan a gift.
   */
  async birthdays(now = new Date()) {
    const tz = process.env.STORE_TIMEZONE ?? 'Asia/Aden';
    const day = businessDate(tz, now).toISOString().slice(0, 10);
    const done = await this.prisma.setting.findUnique({ where: { key: 'birthday.lastRun' } });
    if (done?.value === day) return { skipped: true };
    await this.prisma.setting.upsert({ where: { key: 'birthday.lastRun' }, update: { value: day }, create: { key: 'birthday.lastRun', value: day } });
    const inThree = this.monthDay(new Date(new Date(`${day}T00:00:00Z`).getTime() + 3 * 86_400_000));
    const today = day.slice(5, 10);
    const rows = await this.prisma.$queryRaw<{ id: string; md: string }[]>`
      SELECT id::text, to_char(birth_date, 'MM-DD') AS md FROM customers
      WHERE status = 'ACTIVE' AND birth_date IS NOT NULL AND to_char(birth_date, 'MM-DD') IN (${today}, ${inThree})`;
    let told = 0;
    for (const r of rows) {
      const c = await this.prisma.customer.findUniqueOrThrow({ where: { id: r.id }, select: { id: true, name: true, username: true } });
      const followers = c.username ? await this.prisma.follow.findMany({ where: { followingId: c.id }, select: { followerId: true } }) : [];
      if (r.md === today) {
        await this.notify(
          c.id,
          `كل عام وأنت بخير يا ${firstName(c.name)} 🎂`,
          'ديجافو تتمنى لك عاماً سعيداً! شارك صورة عيد ميلادك في الستوري ليهديك أصدقاؤك من التطبيق.',
          { screen: 'birthday', ...(c.username ? { username: c.username } : {}) },
        );
        for (const f of followers) {
          await this.notify(f.followerId, `اليوم عيد ميلاد @${c.username} 🎉`, 'أرسل له هدية أو رصيداً من ديجافو', { username: c.username!, screen: 'birthday' });
        }
      } else {
        for (const f of followers) {
          await this.notify(f.followerId, `يقترب عيد ميلاد @${c.username} بعد 3 أيام 🎁`, 'جهّز هديته من ديجافو، أو ساهم مع الأصدقاء في هدية جماعية', { username: c.username!, screen: 'user' });
        }
      }
      told += 1 + followers.length;
    }
    return { skipped: false, told };
  }

  // ------------------------------------------------------- shop the look ---

  /** Every product tagged on the post into the cart (one each, the tagged size or the first in stock). */
  async shopTheLook(customerId: string, postId: string) {
    const post = await this.prisma.post.findUnique({ where: { id: postId }, include: { tags: true } });
    if (!post || post.isHidden) throw new NotFoundException('Post not found');
    let added = 0;
    const skipped: string[] = [];
    for (const t of post.tags) {
      const variantId =
        t.variantId ??
        (
          await this.prisma.productVariant.findFirst({
            where: { productId: t.productId, isActive: true, stockLevels: { some: { onHand: { gt: 0 } } } },
            orderBy: { sku: 'asc' },
            select: { id: true },
          })
        )?.id;
      if (!variantId) {
        skipped.push(t.productId);
        continue;
      }
      try {
        await this.cart.addItem(customerId, { variantId, quantity: 1, viaPostId: postId });
        added++;
      } catch {
        skipped.push(t.productId);
      }
    }
    return { added, skipped };
  }

  // ---------------------------------------------------------- ambassadors ---

  async ambassadorSettings(db: Tx | PrismaService = this.prisma): Promise<AmbassadorSettings> {
    const row = await db.setting.findUnique({ where: { key: AMBASSADOR_KEY } });
    return { ...AMBASSADOR_DEFAULTS, ...((row?.value as Partial<AmbassadorSettings>) ?? {}) };
  }

  async setAmbassadorSettings(dto: Partial<AmbassadorSettings>) {
    const value = { ...(await this.ambassadorSettings()), ...dto };
    await this.prisma.setting.upsert({ where: { key: AMBASSADOR_KEY }, update: { value: value as object }, create: { key: AMBASSADOR_KEY, value: value as object } });
    return value;
  }

  /** At checkout: what was bought through each post (pending until the order is received). */
  private async recordAmbassador(tx: Tx, orderId: string) {
    const s = await this.ambassadorSettings(tx);
    if (!s.enabled || s.percent <= 0) return;
    const order = await tx.order.findUniqueOrThrow({ where: { id: orderId }, select: { customerId: true, items: { where: { viaPostId: { not: null } }, select: { viaPostId: true, lineTotal: true } } } });
    const byPost = new Map<string, Prisma.Decimal>();
    for (const i of order.items) byPost.set(i.viaPostId!, (byPost.get(i.viaPostId!) ?? dec(0)).add(i.lineTotal));
    for (const [postId, sales] of byPost) {
      const post = await tx.post.findUnique({ where: { id: postId }, select: { authorId: true } });
      if (!post || post.authorId === order.customerId) continue;
      const reward = money(dec(sales).mul(s.percent).div(100));
      if (!reward.gt(0)) continue;
      await tx.ambassadorReward.create({ data: { postId, posterId: post.authorId, buyerId: order.customerId, orderId, salesSar: money(sales), rewardSar: reward, kind: s.kind } });
    }
  }

  /** The order was received: the post's author gets their reward (points or wallet credit). */
  private async payAmbassador(tx: Tx, orderId: string) {
    const rewards = await tx.ambassadorReward.findMany({ where: { orderId, status: 'PENDING' } });
    for (const r of rewards) {
      if (r.kind === 'WALLET') {
        await this.accounts.reward(tx, { customerId: r.posterId, amountSar: dec(r.rewardSar), description: `Ambassador reward (order ${orderId})`, sourceId: r.id });
      } else {
        const rate = (await this.loyalty.settings(tx)).sarPer100Points || 5;
        const points = Math.max(1, Math.round(dec(r.rewardSar).mul(100).div(rate).toNumber()));
        await tx.loyaltyEntry.create({ data: { customerId: r.posterId, points, kind: 'AMBASSADOR', orderId, note: 'مكافأة سفير' } });
        await tx.customer.update({ where: { id: r.posterId }, data: { loyaltyPoints: { increment: points } } });
      }
      await tx.ambassadorReward.update({ where: { id: r.id }, data: { status: 'PAID', paidAt: new Date() } });
      await this.notifications.notifyCustomer(tx, r.posterId, {
        type: NotificationType.PROMOTION,
        title: 'ربحت مكافأة سفير 🌟',
        body: `اشترى أحدهم من منشورك. ${r.kind === 'WALLET' ? `أُضيف ${money(r.rewardSar).toFixed(2)} ر.س إلى رصيدك` : 'أُضيفت نقاط إلى حسابك'}.`,
        data: { post: r.postId, screen: 'post' },
      });
    }
  }

  private async cancelAmbassador(tx: Tx, orderId: string) {
    await tx.ambassadorReward.updateMany({ where: { orderId, status: 'PENDING' }, data: { status: 'CANCELLED' } });
  }

  async myRewards(customerId: string) {
    const rows = await this.prisma.ambassadorReward.findMany({ where: { posterId: customerId }, orderBy: { createdAt: 'desc' }, take: 100 });
    const paid = rows.filter((r) => r.status === 'PAID').reduce((t, r) => t.add(r.rewardSar), dec(0));
    const pending = rows.filter((r) => r.status === 'PENDING').reduce((t, r) => t.add(r.rewardSar), dec(0));
    return { paid: money(paid), pending: money(pending), items: rows };
  }

  // ----------------------------------------------------------- moderation ---

  async hidePost(postId: string, hidden: boolean) {
    await this.prisma.post.update({ where: { id: postId }, data: { isHidden: hidden } });
    return { hidden };
  }

  async hideComment(commentId: string) {
    const c = await this.prisma.postComment.findUnique({ where: { id: commentId } });
    if (!c) throw new NotFoundException('Comment not found');
    if (!c.isHidden) {
      await this.prisma.$transaction([
        this.prisma.postComment.update({ where: { id: commentId }, data: { isHidden: true } }),
        this.prisma.post.update({ where: { id: c.postId }, data: { commentCount: { decrement: 1 } } }),
      ]);
    }
    return { hidden: true };
  }

  async adminPosts(page = 1) {
    const rows = await this.prisma.post.findMany({ include: postInclude, orderBy: { createdAt: 'desc' }, skip: (page - 1) * 30, take: 30 });
    return (await this.present(rows)).map((p, i) => ({ ...p, isHidden: rows[i].isHidden }));
  }
}
