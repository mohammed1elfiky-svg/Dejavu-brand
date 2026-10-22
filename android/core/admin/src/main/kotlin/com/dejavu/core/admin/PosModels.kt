package com.dejavu.core.admin

import com.dejavu.core.model.BankRef
import com.dejavu.core.model.Currency
import com.dejavu.core.model.Money
import com.dejavu.core.model.Page
import com.dejavu.core.model.PromotionTag
import com.dejavu.core.model.TopUpStatus
import kotlinx.serialization.Serializable

/*
 * Wire models for the till (POS), cash boxes, customer accounts, wallet top-ups,
 * promotions and currency settings. Amounts are decimal strings; SAR unless a
 * field says it is in a currency.
 */

// ------------------------------------------------------------------- POS ---

@Serializable
data class PosVariant(
    val id: String,
    val sku: String,
    val barcode: String? = null,
    val size: String? = null,
    val color: String? = null,
    val colorHex: String? = null,
    /** Selling price now (after a running promotion), SAR. */
    val price: String,
    val regularPrice: String = price,
    val available: Int = 0,
    val imageUrl: String? = null,
) {
    val title: String get() = listOfNotNull(color, size).joinToString(" · ").ifBlank { sku }
}

@Serializable
data class PosProduct(
    val id: String,
    val nameAr: String,
    val nameEn: String,
    val skuNumber: Int? = null,
    val basePrice: String,
    val price: String = basePrice,
    val posSortOrder: Int = 0,
    val category: CategoryName? = null,
    val imageUrl: String? = null,
    val promotion: PromotionTag? = null,
    val variants: List<PosVariant> = emptyList(),
) {
    val available: Int get() = variants.sumOf { it.available.coerceAtLeast(0) }
}

@Serializable
data class PosLookup(val product: PosProduct, val variantId: String? = null)

@Serializable
data class PosArrangeRequest(val productIds: List<String>)

@Serializable
data class PosItem(val variantId: String, val quantity: Int)

@Serializable
data class PosTender(val currency: Currency, val amount: String)

@Serializable
enum class SaleType { CASH, CREDIT }

@Serializable
enum class DiscountType { PERCENT, AMOUNT }

@Serializable
data class PosSaleRequest(
    val items: List<PosItem>,
    val saleType: SaleType,
    /** Invoice currency (its current rate is locked in). */
    val currency: Currency? = null,
    val customerId: String? = null,
    val discountType: DiscountType? = null,
    /** Percent, or an amount in the invoice currency. */
    val discountValue: String? = null,
    val payments: List<PosTender> = emptyList(),
    val changeCurrency: Currency? = null,
    val note: String? = null,
)

@Serializable
data class PosInCurrency(
    val currency: Currency,
    val exchangeRate: String,
    val subtotal: String,
    val discountTotal: String,
    val taxTotal: String,
    val grandTotal: String,
)

@Serializable
data class PosReceipt(
    val id: String,
    val number: String,
    val placedAt: String,
    val subtotal: String,
    val discountTotal: String,
    val taxTotal: String,
    val grandTotal: String,
    val currency: Currency = Currency.SAR,
    val exchangeRate: String = "1",
    val items: List<AdminOrderItem> = emptyList(),
    val payments: List<AdminPayment> = emptyList(),
    val customer: PersonRef? = null,
    val cashier: NameRef? = null,
    val inCurrency: PosInCurrency,
    /** Put on the customer's account (credit sale), SAR. */
    val onAccount: String = "0",
) {
    fun inInvoiceCurrency(amountSar: String?): String = Money.format(amountSar, currency, exchangeRate)
}

@Serializable
data class ShiftSale(
    val id: String,
    val number: String,
    val grandTotal: String,
    val grandTotalInCurrency: String,
    val currency: Currency,
    val placedAt: String,
    val customer: NameRef? = null,
)

@Serializable
data class PosShift(val count: Int, val total: String, val sales: List<ShiftSale> = emptyList(), val boxes: List<CashBox> = emptyList())

@Serializable
data class QuickCustomerRequest(val name: String, val phone: String)

@Serializable
data class QuickCustomer(val id: String, val code: String, val name: String, val phone: String)

// ------------------------------------------------------------- cash boxes ---

@Serializable
enum class CashBoxKind { CASHIER, VAULT, BANK }

@Serializable
data class BoxAccount(val id: String, val code: String)

@Serializable
data class CashBox(
    val id: String,
    val name: String,
    val kind: CashBoxKind,
    val currency: Currency,
    val isActive: Boolean = true,
    val user: NameRef? = null,
    val account: BoxAccount? = null,
    /** Money in the box, in its own currency. */
    val balance: String = "0",
    /** What that money is carried at in the books (SAR). */
    val bookValue: String = "0",
) {
    val shownBalance: String get() = Money.plain(Money.parse(balance), currency)
}

@Serializable
data class CashBoxEntry(
    val id: String,
    val amount: String,
    val baseAmount: String,
    val sourceType: String,
    val note: String? = null,
    val createdAt: String,
)

@Serializable
data class CreateBoxRequest(val name: String, val kind: CashBoxKind, val currency: Currency)

@Serializable
data class UpdateBoxRequest(val name: String? = null, val isActive: Boolean? = null)

@Serializable
data class TransferRequest(
    val fromBoxId: String,
    val toBoxId: String,
    /** In the source box's currency. */
    val amount: String,
    /** In the target box's currency; required when the currencies differ (an exchange). */
    val toAmount: String? = null,
    val note: String? = null,
)

@Serializable
data class BoxRef(val id: String, val name: String, val currency: Currency)

@Serializable
data class CashTransfer(
    val id: String,
    val number: String,
    val fromAmount: String,
    val toAmount: String,
    val baseAmount: String,
    /** Exchange gain (+) or loss (-), SAR. */
    val fxDifference: String = "0",
    val note: String? = null,
    val createdAt: String,
    val fromBox: BoxRef,
    val toBox: BoxRef,
) {
    val isExchange: Boolean get() = fromBox.currency != toBox.currency
}

// ------------------------------------------------------- customer accounts ---

@Serializable
data class AccountBalance(
    val currency: Currency,
    /** + the customer owes the store, - the store holds their money. */
    val balance: String,
    val available: String = "0",
    val bookValue: String = "0",
)

@Serializable
data class AccountEntry(
    val id: String,
    val currency: Currency,
    val amount: String,
    val sourceType: String,
    val note: String? = null,
    val createdAt: String,
)

@Serializable
data class CustomerAccount(val balances: List<AccountBalance> = emptyList(), val entries: Page<AccountEntry>)

@Serializable
data class AccountBalances(val balances: List<AccountBalance> = emptyList())

@Serializable
data class CustomerReceiptRequest(val currency: Currency, val amount: String, val cashBoxId: String? = null, val note: String? = null)

// ---------------------------------------------------------- wallet top-ups ---

@Serializable
data class AdminTopUp(
    val id: String,
    val number: String,
    val currency: Currency,
    val amount: String,
    val reference: String? = null,
    val note: String? = null,
    val status: TopUpStatus,
    val receiptFileId: String,
    val rejectReason: String? = null,
    val createdAt: String,
    val reviewedAt: String? = null,
    val bankAccount: BankRef? = null,
    val customer: PersonRef? = null,
    val cashBox: BoxRef? = null,
    val reviewedBy: NameRef? = null,
    val balances: List<AccountBalance> = emptyList(),
) {
    val shownAmount: String get() = Money.plain(Money.parse(amount), currency)
}

@Serializable
data class ApproveTopUpRequest(val cashBoxId: String, val amount: String? = null)

@Serializable
data class BankAccountRequest(
    val bankName: String,
    val accountName: String,
    val accountNumber: String,
    val currency: Currency,
    val iban: String? = null,
    val instructions: String? = null,
    val isActive: Boolean? = null,
)

// -------------------------------------------------------------- promotions ---

@Serializable
data class PromotionProduct(val id: String, val nameAr: String, val nameEn: String, val basePrice: String, val skuNumber: Int? = null, val images: List<ImageUrl> = emptyList())

@Serializable
data class Promotion(
    val id: String,
    val name: String,
    val percent: String,
    val startsAt: String,
    val endsAt: String? = null,
    val isActive: Boolean = true,
    val running: Boolean = false,
    val products: List<PromotionProduct> = emptyList(),
)

@Serializable
data class PromotionRequest(
    val name: String,
    val percent: String,
    val productIds: List<String>,
    val startsAt: String? = null,
    val endsAt: String? = null,
    val isActive: Boolean? = null,
)

// -------------------------------------------------------------- currencies ---

@Serializable
data class CurrencyRequest(
    val code: String? = null,
    val nameAr: String? = null,
    val nameEn: String? = null,
    val symbol: String? = null,
    val decimals: Int? = null,
    val isActive: Boolean? = null,
    val showInCustomerApp: Boolean? = null,
    val sortOrder: Int? = null,
    /** Initial rate (create only): units per 1 SAR. */
    val rate: String? = null,
)
