@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.admin.ui.round9

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.FullStatement
import com.dejavu.core.admin.Round9Repository
import com.dejavu.core.model.Currency
import com.dejavu.core.model.Money
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.formatDate
import com.dejavu.core.ui.theme.Success
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

class StatementViewModel(repo: Round9Repository, customerId: String) : LoadViewModel<FullStatement>({ repo.statement(customerId) })

/** Signed balance as the customer reads it: "عليه 120" / "له 30". */
private fun balanceText(v: BigDecimal, currency: Currency): String = when {
    v.signum() > 0 -> "عليه ${Money.plain(v, currency)}"
    v.signum() < 0 -> "له ${Money.plain(v.abs(), currency)}"
    else -> "0"
}

/**
 * The customer's full statement: one section per currency they dealt in, each line with its
 * invoice number and pieces. Tapping a line opens the invoice; the PDF can be shared on WhatsApp.
 */
@Composable
fun StatementScreen(customerId: String, onBack: () -> Unit, openOrder: (String) -> Unit) {
    val vm = adminViewModel(key = "statement:$customerId") { StatementViewModel(it.round9, customerId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var shown by rememberSaveable { mutableStateOf<String?>(null) }
    CollectMessages(vm, snackbar)
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            BackTopBar("كشف الحساب", onBack) {
                val s = (state as? com.dejavu.core.ui.Load.Ready<*>)?.value as? FullStatement
                if (s != null) {
                    IconButton(onClick = {
                        exporting = true
                        scope.launch {
                            runCatching { statementPdf(context, s) }
                                .onSuccess { share(context, it, "كشف حساب ${s.customer.name}") }
                                .onFailure { snackbar.showSnackbar("تعذّر تجهيز الملف") }
                            exporting = false
                        }
                    }, enabled = !exporting) {
                        if (exporting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Filled.Share, "مشاركة PDF")
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LoadContent(state, onRetry = vm::load) { s ->
                if (s.currencies.isEmpty()) {
                    Text("لا توجد حركات على حساب هذا العميل", Modifier.padding(24.dp), color = Lux.InkSoft)
                    return@LoadContent
                }
                val current = s.currencies.firstOrNull { it.currency == shown } ?: s.currencies.first()
                val cur = Currency.of(current.currency)
                if (s.currencies.size > 1) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        s.currencies.forEach { c -> FilterChip(selected = c.currency == current.currency, onClick = { shown = c.currency }, label = { Text(Currency.of(c.currency).nameAr) }) }
                    }
                }
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        GlassCard(Modifier.fillMaxWidth(), contentPadding = 12.dp) {
                            Text(s.customer.name, style = MaterialTheme.typography.titleMedium)
                            Text(listOf(s.customer.code, s.customer.phone).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                            HorizontalDivider(Modifier.padding(vertical = 6.dp))
                            Row { Text("على العميل", Modifier.weight(1f)); Text(Money.plain(Money.parse(current.owes), cur), color = MaterialTheme.colorScheme.error) }
                            Row { Text("للعميل", Modifier.weight(1f)); Text(Money.plain(Money.parse(current.credit), cur), color = Success) }
                            Row {
                                Text("الرصيد", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                Text(balanceText(Money.parse(current.closing), cur), style = MaterialTheme.typography.titleSmall, color = Lux.GoldDeep)
                            }
                        }
                    }
                    items(current.rows, key = { it.id }) { r ->
                        val owes = Money.parse(r.owes)
                        GlassCard(Modifier.fillMaxWidth(), elevation = 3.dp, contentPadding = 10.dp, onClick = r.orderId?.let { id -> { openOrder(id) } }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(r.description, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        listOfNotNull(formatDate(r.date), r.reference, r.pieces?.let { "$it قطعة" }).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Lux.InkSoft,
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    if (owes.signum() > 0) {
                                        Text("+ ${Money.plain(owes, cur)}", color = MaterialTheme.colorScheme.error)
                                    } else {
                                        Text("− ${Money.plain(Money.parse(r.credit), cur)}", color = Success)
                                    }
                                    Text(balanceText(Money.parse(r.balance), cur), style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------------------- PDF ---

/**
 * A4 statement with the DEJAVU logo: a page (or more) per currency the customer used, with the
 * columns البيان، التاريخ، رقم الفاتورة، القطع، على العميل، للعميل، الرصيد.
 */
suspend fun statementPdf(context: Context, s: FullStatement): File = withContext(Dispatchers.IO) {
    val pageW = 595
    val pageH = 842
    val margin = 28f
    val right = pageW - margin
    val doc = PdfDocument()
    val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.rgb(26, 26, 26); textSize = 9.5f; textAlign = Paint.Align.RIGHT }
    val soft = Paint(ink).apply { color = AndroidColor.rgb(108, 117, 125); textSize = 9f }
    val bold = Paint(ink).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = 12f }
    val red = Paint(ink).apply { color = AndroidColor.rgb(192, 57, 43) }
    val green = Paint(ink).apply { color = AndroidColor.rgb(39, 129, 77) }
    val gold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.rgb(184, 134, 43) }
    val band = Paint().apply { color = AndroidColor.rgb(251, 246, 228) }
    val line = Paint().apply { color = AndroidColor.rgb(225, 225, 225); strokeWidth = 0.8f }
    val rowH = 19f
    // Columns, right to left.
    val descX = right
    val dateX = right - 165f
    val refX = right - 225f
    val piecesX = right - 300f
    val owesX = right - 330f
    val creditX = right - 405f
    val balanceX = margin + 2f
    val logo = ContextCompat.getDrawable(context, com.dejavu.core.ui.R.drawable.dejavu_logo)

    var pageNo = 0
    var page: PdfDocument.Page? = null
    var y = 0f
    fun canvas() = page!!.canvas

    fun columns() {
        val c = canvas()
        c.drawRect(margin, y - 12f, right, y + 5f, band)
        listOf("البيان" to descX, "التاريخ" to dateX, "رقم الفاتورة" to refX, "القطع" to piecesX, "على العميل" to owesX, "للعميل" to creditX).forEach { (t, x) -> c.drawText(t, x, y, soft) }
        soft.textAlign = Paint.Align.LEFT
        c.drawText("الرصيد", balanceX, y, soft)
        soft.textAlign = Paint.Align.RIGHT
        y += rowH
    }

    fun newPage(currency: Currency, continued: Boolean) {
        page?.let(doc::finishPage)
        pageNo += 1
        page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
        val c = canvas()
        c.drawRect(0f, 0f, pageW.toFloat(), 5f, gold)
        logo?.let {
            it.setBounds((right - 120f).toInt(), (margin - 4f).toInt(), right.toInt(), (margin + 56f).toInt())
            it.draw(c)
        }
        soft.textAlign = Paint.Align.LEFT
        c.drawText("صفحة $pageNo", margin, margin + 10f, soft)
        c.drawText(LocalDate.now().toString(), margin, margin + 24f, soft)
        soft.textAlign = Paint.Align.RIGHT
        y = margin + 78f
        bold.textSize = 14f
        c.drawText("كشف حساب عميل — ${currency.nameAr}${if (continued) " (تابع)" else ""}", right, y, bold)
        bold.textSize = 12f
        y += 18f
        c.drawText("${s.customer.name}  ·  ${listOf(s.customer.code, s.customer.phone).filter { it.isNotBlank() }.joinToString(" · ")}", right, y, ink)
        y += 22f
    }

    fun money(v: BigDecimal, cur: Currency) = v.setScale(cur.decimals, RoundingMode.HALF_UP).toPlainString()

    s.currencies.forEach { section ->
        val cur = Currency.of(section.currency)
        newPage(cur, continued = false)
        val c0 = canvas()
        val opening = Money.parse(section.opening)
        if (opening.signum() != 0) {
            c0.drawText("رصيد سابق: ${balanceText(opening, cur)}", right, y, ink)
            y += rowH
        }
        columns()
        section.rows.forEach { r ->
            if (y + rowH > pageH - margin - 60f) {
                newPage(cur, continued = true)
                columns()
            }
            val c = canvas()
            val owes = Money.parse(r.owes)
            val credit = Money.parse(r.credit)
            val bal = Money.parse(r.balance)
            c.drawText(fit(r.description, ink, 160f), descX, y, ink)
            c.drawText(formatDate(r.date), dateX, y, ink)
            c.drawText(r.reference ?: "", refX, y, ink)
            c.drawText(r.pieces?.toString() ?: "", piecesX, y, ink)
            if (owes.signum() != 0) c.drawText(money(owes, cur), owesX, y, red)
            if (credit.signum() != 0) c.drawText(money(credit, cur), creditX, y, green)
            val balPaint = Paint(if (bal.signum() > 0) red else green).apply { textAlign = Paint.Align.LEFT }
            c.drawText((if (bal.signum() > 0) "عليه " else if (bal.signum() < 0) "له " else "") + money(bal.abs(), cur), balanceX, y, balPaint)
            y += 5f
            c.drawLine(margin, y, right, y, line)
            y += rowH - 5f
        }
        // Totals of this currency.
        val c = canvas()
        y += 6f
        c.drawRect(margin, y - 13f, right, y + 26f, band)
        c.drawText("الإجمالي", descX, y, bold)
        c.drawText(money(Money.parse(section.owes), cur), owesX, y, red)
        c.drawText(money(Money.parse(section.credit), cur), creditX, y, green)
        y += 18f
        c.drawText("الرصيد النهائي: ${balanceText(Money.parse(section.closing), cur)}", right, y, bold)
    }
    page?.let(doc::finishPage)

    val dir = File(context.cacheDir, "photos").apply { mkdirs() }
    val file = File(dir, "statement-${s.customer.code.ifBlank { "customer" }}-${System.currentTimeMillis()}.pdf")
    file.outputStream().use(doc::writeTo)
    doc.close()
    file
}

private fun fit(text: String, paint: Paint, max: Float): String {
    if (paint.measureText(text) <= max) return text
    var t = text
    while (t.length > 1 && paint.measureText("$t…") > max) t = t.dropLast(1)
    return "$t…"
}

private fun share(context: Context, file: File, subject: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.photos", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/pdf")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "مشاركة كشف الحساب").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
