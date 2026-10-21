import { Body, Controller, Get, Header, HttpCode, HttpStatus, Param, ParseUUIDPipe, Post, Query } from '@nestjs/common';
import { ApiExcludeEndpoint, ApiTags } from '@nestjs/swagger';
import { SkipThrottle } from '@nestjs/throttler';
import { IsIn, IsString } from 'class-validator';
import { Public } from '../common/decorators/auth.decorators';
import { money } from '../common/utils/money';
import { OnlinePaymentsService } from './online-payments.service';

class TestOutcomeDto {
  @IsString()
  sig: string;

  @IsIn(['success', 'failure'])
  outcome: 'success' | 'failure';
}

/** Deep link the customer app listens for, so "back to the app" lands on the order. */
const appLink = (orderNumber?: string) => `dejavu://payment${orderNumber ? `?order=${encodeURIComponent(orderNumber)}` : ''}`;

const esc = (s: string) => s.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);

function page(title: string, body: string) {
  return `<!doctype html><html lang="ar" dir="rtl"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>${esc(title)}</title>
<style>
body{font-family:system-ui,-apple-system,"Segoe UI",Tahoma,sans-serif;background:#f6f3ee;color:#1f1b16;margin:0;padding:24px;display:flex;justify-content:center}
.card{background:#fff;border-radius:16px;padding:24px;max-width:420px;width:100%;box-shadow:0 2px 12px rgba(0,0,0,.06);text-align:center}
h1{font-size:20px;margin:0 0 8px}p{color:#5c554c;line-height:1.6}.amount{font-size:28px;font-weight:700;margin:16px 0}
.btn{display:block;width:100%;box-sizing:border-box;padding:14px;border-radius:12px;border:0;font-size:16px;margin-top:12px;text-decoration:none;cursor:pointer}
.primary{background:#7a5c2e;color:#fff}.secondary{background:#efe9df;color:#1f1b16}.danger{background:#fbe9e7;color:#b3261e}
.badge{display:inline-block;padding:4px 10px;border-radius:999px;background:#fff4d6;color:#8a6100;font-size:12px}
</style></head><body><div class="card">${body}</div></body></html>`;
}

function resultPage(status: string | undefined, orderNumber?: string) {
  const [title, text] =
    status === 'SUCCEEDED'
      ? ['تم الدفع بنجاح ✓', 'شكراً لك! تم استلام المبلغ وسيتم تجهيز طلبك.']
      : status === 'FAILED'
        ? ['لم تتم عملية الدفع', 'تم رفض البطاقة أو إلغاء العملية. يمكنك المحاولة مرة أخرى من صفحة الطلب في التطبيق.']
        : ['جارٍ تأكيد الدفع…', 'سيظهر تأكيد الدفع في التطبيق خلال لحظات.'];
  return page(
    title,
    `<h1>${title}</h1>${orderNumber ? `<p>الطلب ${esc(orderNumber)}</p>` : ''}<p>${text}</p>
<a class="btn primary" href="${appLink(orderNumber)}">العودة إلى التطبيق</a>`,
  );
}

@ApiTags('Payments')
@Public()
@Controller('payments')
export class PaymentsController {
  constructor(private readonly online: OnlinePaymentsService) {}

  /** Paymob "transaction processed" webhook. Signed with the account's HMAC secret. */
  @Post('paymob/callback')
  @SkipThrottle()
  @HttpCode(HttpStatus.OK)
  paymobCallback(@Body() body: Record<string, unknown>, @Query('hmac') hmac?: string) {
    return this.online.paymobCallback(body, hmac);
  }

  /** Where the customer's browser lands after the gateway. The ledger is only updated by the webhook. */
  @Get('return')
  @ApiExcludeEndpoint()
  @Header('Content-Type', 'text/html; charset=utf-8')
  async returnPage(@Query('merchant_order_id') paymentId?: string, @Query('success') success?: string) {
    const attempt = await this.online.attemptSummary(paymentId);
    // The callback may land a moment after the redirect; trust the redirect's flag only for the message.
    const status = attempt?.status === 'PENDING' ? (success === 'false' ? 'FAILED' : 'PENDING') : attempt?.status;
    return resultPage(status, attempt?.order.number);
  }

  @Get('test/:id')
  @ApiExcludeEndpoint()
  @Header('Content-Type', 'text/html; charset=utf-8')
  async testPage(@Param('id', ParseUUIDPipe) id: string, @Query('sig') sig?: string) {
    const p = await this.online.testAttempt(id, sig);
    if (p.status !== 'PENDING') return resultPage(p.status, p.order.number);
    const form = (outcome: string, cls: string, label: string) =>
      `<form method="post" action="${id}"><input type="hidden" name="sig" value="${esc(sig ?? '')}">` +
      `<input type="hidden" name="outcome" value="${outcome}"><button class="btn ${cls}" type="submit">${label}</button></form>`;
    return page(
      'دفع تجريبي',
      `<span class="badge">بوابة دفع تجريبية — لا يتم خصم أي مبلغ حقيقي</span>
<h1>الدفع بالبطاقة</h1><p>الطلب ${esc(p.order.number)}</p><div class="amount">${money(p.amount).toFixed(2)} ج.م</div>
${form('success', 'primary', 'ادفع الآن')}${form('failure', 'danger', 'محاكاة رفض البطاقة')}`,
    );
  }

  @Post('test/:id')
  @ApiExcludeEndpoint()
  @HttpCode(HttpStatus.OK)
  @Header('Content-Type', 'text/html; charset=utf-8')
  async testComplete(@Param('id', ParseUUIDPipe) id: string, @Body() dto: TestOutcomeDto) {
    const p = await this.online.testComplete(id, dto.sig, dto.outcome === 'success');
    return resultPage(p.status, p.order.number);
  }
}
