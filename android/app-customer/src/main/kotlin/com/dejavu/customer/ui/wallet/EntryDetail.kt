@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.customer.ui.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dejavu.core.model.Currency
import com.dejavu.core.model.Money
import com.dejavu.core.model.OrderStatus
import com.dejavu.core.model.PaymentMethod
import com.dejavu.core.model.WalletEntryDetail
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.SummaryRow
import com.dejavu.core.ui.formatDateTime
import com.dejavu.core.ui.label
import com.dejavu.core.ui.theme.Liquid
import com.dejavu.core.ui.theme.Success
import com.dejavu.core.ui.toLoad
import com.dejavu.customer.ui.appContainer

private fun topUpStatus(s: String) = when (s) {
    "PENDING" -> "قيد المراجعة"
    "APPROVED" -> "مقبول"
    "REJECTED" -> "مرفوض"
    else -> s
}

private fun returnStatus(s: String) = when (s) {
    "REQUESTED" -> "قيد المراجعة"
    "APPROVED" -> "موافَق عليه"
    "RECEIVED" -> "استُلمت القطع"
    "REFUNDED" -> "تم الاسترداد"
    "REJECTED" -> "مرفوض"
    else -> s
}

private fun method(code: String?): String? = code?.let { c -> runCatching { PaymentMethod.valueOf(c).label }.getOrNull() }

/**
 * Tap a wallet movement to see what it was: for a purchase the items and how many, for a top-up
 * the way it was paid and its reference, for a return what came back.
 */
@Composable
fun EntryDetailSheet(entryId: String, onDismiss: () -> Unit) {
    val wallet = appContainer().wallet
    var state by remember { mutableStateOf<Load<WalletEntryDetail>>(Load.Loading) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(entryId, attempt) {
        state = Load.Loading
        state = wallet.entry(entryId).toLoad()
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LoadContent(state, onRetry = { attempt++ }) { d -> Detail(d) }
        }
    }
}

@Composable
private fun Detail(d: WalletEntryDetail) {
    val currency = Currency.of(d.currency)
    val amount = Money.parse(d.amount)
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(d.title.ifBlank { "حركة" }, style = MaterialTheme.typography.titleMedium)
            Text(formatDateTime(d.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            (if (amount.signum() >= 0) "+" else "−") + Money.plain(amount.abs(), currency),
            style = MaterialTheme.typography.titleMedium,
            color = if (amount.signum() >= 0) Success else MaterialTheme.colorScheme.error,
        )
    }
    HorizontalDivider()
    when (d.kind) {
        "ORDER" -> d.order?.let { o ->
            val cur = Currency.SAR
            SummaryRow("رقم الفاتورة", o.number, strong = true)
            runCatching { OrderStatus.valueOf(o.status).label }.getOrNull()?.let { SummaryRow("الحالة", it) }
            (method(d.paymentMethod) ?: method(o.paymentMethod))?.let { SummaryRow("طريقة الدفع", it) }
            d.paymentNumber?.let { SummaryRow("رقم السند", it) }
            Text("الأصناف (${o.items.sumOf { it.quantity }} قطعة)", style = MaterialTheme.typography.titleSmall, color = Liquid.GoldDeep, modifier = Modifier.padding(top = 6.dp))
            o.items.forEach { i ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(i.productName, style = MaterialTheme.typography.bodyMedium)
                        Text(listOfNotNull(i.color, i.size?.let { "مقاس $it" }).joinToString(" · ").ifBlank { " " }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("× ${i.quantity}", style = MaterialTheme.typography.titleSmall)
                    Text(Money.format(i.lineTotal), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium, color = Liquid.GoldDeep)
                }
            }
            HorizontalDivider()
            if (!Money.isZero(o.discountTotal)) SummaryRow("الخصم", "- ${Money.format(o.discountTotal)}", valueColor = Success)
            if (!Money.isZero(o.shippingFee)) SummaryRow("الشحن", Money.format(o.shippingFee))
            SummaryRow("إجمالي الفاتورة", Money.plain(Money.parse(o.grandTotal), cur), strong = true)
        }
        "TOP_UP" -> d.topUp?.let { t ->
            SummaryRow("رقم الطلب", t.number, strong = true)
            SummaryRow("طريقة الدفع", t.bankName?.let { "حوالة إلى $it" } ?: "حوالة / إيداع بنكي")
            SummaryRow("المبلغ المحوَّل", Money.plain(Money.parse(t.amount), Currency.of(t.currency)))
            t.reference?.takeIf { it.isNotBlank() }?.let { SummaryRow("رقم الحوالة", it) }
            SummaryRow("الحالة", topUpStatus(t.status))
        }
        "RETURN" -> d.returnInfo?.let { r ->
            SummaryRow("رقم المرتجع", r.number, strong = true)
            SummaryRow("من الفاتورة", r.orderNumber)
            SummaryRow("الحالة", returnStatus(r.status))
            r.items.forEach { i -> SummaryRow("${i.productName} × ${i.quantity}", Money.format(i.lineTotal)) }
            SummaryRow("المبلغ المسترد", Money.format(r.refundAmount), strong = true)
        }
        "LAYAWAY" -> d.layaway?.let { l ->
            SummaryRow("رقم الحجز", l.number, strong = true)
            SummaryRow("إجمالي الحجز", Money.format(l.total))
            Text("العربون يُخصم من ثمن القطع عند إتمام الحجز", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> Unit
    }
    d.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}
