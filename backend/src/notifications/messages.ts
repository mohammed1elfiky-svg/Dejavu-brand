import { NotificationType, OrderStatus, PaymentMethod } from '../generated/prisma/client';
import { formatAmount } from '../common/currency.service';
import { DecimalLike } from '../common/utils/money';
import { NewNotification } from './notifications.service';

/** Customer-facing texts (Arabic, gender-neutral). */

type Priced = { number: string; currency: string; exchangeRate: DecimalLike };

/** A SAR amount shown in the order's own currency, at its locked rate. */
export const orderAmount = (order: Pick<Priced, 'currency' | 'exchangeRate'>, amountSar: DecimalLike) =>
  formatAmount(amountSar, order.currency, order.exchangeRate);

/** Internal cancel reasons the customer should see in plain words. */
const CANCEL_REASONS: Record<string, string> = {
  'Card payment not completed in time': 'لم يكتمل الدفع بالبطاقة في الوقت المحدد، وأعدنا القطع للمخزون.',
};

export function orderStatusMessage(
  order: { number: string },
  to: OrderStatus,
  extra: { carrier?: string | null; trackingNumber?: string | null; reason?: string | null } = {},
): NewNotification | null {
  const n = order.number;
  const data = { order: n, status: to };
  const msg = (title: string, body: string): NewNotification => ({ type: NotificationType.ORDER_STATUS, title, body, data });
  switch (to) {
    case OrderStatus.CONFIRMED:
      return msg('تم تأكيد طلبك', `الطلب ${n} مؤكد وسنبدأ تجهيزه قريباً.`);
    case OrderStatus.PROCESSING:
      return msg('طلبك قيد التجهيز', `نجهّز الآن الطلب ${n} للشحن.`);
    case OrderStatus.SHIPPED: {
      const carrier = extra.carrier && extra.carrier !== 'in-house' ? ` مع ${extra.carrier}` : '';
      const tracking = extra.trackingNumber ? `، رقم التتبع ${extra.trackingNumber}` : '';
      return msg('طلبك في الطريق 🚚', `خرج الطلب ${n} للتوصيل${carrier}${tracking}.`);
    }
    case OrderStatus.DELIVERED:
      return msg('تم توصيل طلبك', `وصل الطلب ${n}. نتمنى أن ينال إعجابك!`);
    case OrderStatus.CANCELLED: {
      const why = (extra.reason && CANCEL_REASONS[extra.reason]) ?? '';
      return msg('تم إلغاء طلبك', `تم إلغاء الطلب ${n}.${why ? ` ${why}` : ''} أي مبلغ مدفوع سيُرد إليك.`);
    }
    case OrderStatus.RETURNED:
      return msg('عاد الطلب إلينا', `لم يتم استلام الطلب ${n}. أي مبلغ مدفوع سيُرد إليك.`);
    default:
      return null;
  }
}

export function paymentReceivedMessage(order: Priced, amount: DecimalLike, method: PaymentMethod): NewNotification | null {
  // Cash on delivery is covered by the "delivered" message.
  if (method === PaymentMethod.CASH_ON_DELIVERY) return null;
  return {
    type: NotificationType.ORDER_STATUS,
    title: 'تم استلام الدفع ✓',
    body: `استلمنا ${orderAmount(order, amount)} للطلب ${order.number}. شكراً لك!`,
    data: { order: order.number },
  };
}

export function refundMessage(order: Priced, amount: DecimalLike): NewNotification {
  return {
    type: NotificationType.ORDER_STATUS,
    title: 'تم رد المبلغ',
    body: `رددنا ${orderAmount(order, amount)} عن الطلب ${order.number}. قد يستغرق ظهوره في حسابك بضعة أيام حسب وسيلة الدفع.`,
    data: { order: order.number },
  };
}

export function returnMessage(
  r: { number: string; order: { number: string } },
  event: 'approved' | 'rejected' | 'received',
  reason?: string,
): NewNotification {
  const data = { order: r.order.number, return: r.number };
  const [title, body] =
    event === 'approved'
      ? ['تمت الموافقة على طلب الإرجاع', `يمكنك تسليم القطع الخاصة بالمرتجع ${r.number} لمندوب الشحن.`]
      : event === 'rejected'
        ? ['تعذّر قبول طلب الإرجاع', `طلب الإرجاع ${r.number} مرفوض${reason ? `: ${reason}` : '.'}`]
        : ['استلمنا المرتجع', `وصلتنا القطع الخاصة بالمرتجع ${r.number} وسنرد المبلغ قريباً.`];
  return { type: NotificationType.ORDER_STATUS, title, body, data };
}
