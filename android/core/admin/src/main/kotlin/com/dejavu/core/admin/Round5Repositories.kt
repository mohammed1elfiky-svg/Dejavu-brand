package com.dejavu.core.admin

import com.dejavu.core.network.apiCall
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/** Owner alerts, bulk prices and offers, damaged stock, Excel. */
class ManagementRepository(private val api: AdminApi) {
    suspend fun ownerAlerts(take: Int = 50) = apiCall { api.ownerAlerts(take) }
    suspend fun ownerAlertSettings() = apiCall { api.ownerAlertSettings() }

    /** [smsPhone] blank clears it. */
    suspend fun setOwnerAlertSettings(s: OwnerAlertSettings) = apiCall { api.setOwnerAlertSettings(s.copy(smsPhone = s.smsPhone?.trim() ?: "")) }

    suspend fun bulkPrice(request: BulkPriceRequest) = apiCall { api.bulkPrice(request) }
    suspend fun onOffer() = apiCall { api.onOffer() }

    suspend fun writeOffDamage(request: StockDamageRequest) = apiCall { api.writeOffDamage(request) }
    suspend fun damages(page: Int = 1, from: String? = null, to: String? = null) = apiCall { api.damages(page, 30, from, to) }

    /** An Excel file as bytes; [kind]: products, stock, customers, sales, debtors, product-sales. */
    suspend fun export(kind: String, from: String? = null, to: String? = null) = apiCall { api.export(kind, from, to).use { it.bytes() } }

    suspend fun importTemplate() = apiCall { api.importTemplate().use { it.bytes() } }

    /** Checks ([dryRun]) or saves the products in an .xlsx sheet. */
    suspend fun importProducts(bytes: ByteArray, dryRun: Boolean) = apiCall {
        api.importProducts(
            MultipartBody.Part.createFormData("file", "products.xlsx", bytes.toRequestBody("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet".toMediaType())),
            dryRun.toString().toRequestBody("text/plain".toMediaType()),
        )
    }
}

/** Layaways (حجز بعربون) and gift cards. */
class SalesExtrasRepository(private val api: AdminApi) {
    suspend fun layaways(status: String? = null, customerId: String? = null, page: Int = 1) = apiCall { api.layaways(status, customerId, page) }

    /** Open reservations nobody put aside yet (shown with the orders), and "تم حجز القطع". */
    suspend fun layawaysToHold() = apiCall { api.layaways(page = 1, pageSize = 100, toHold = true) }
    suspend fun holdLayaway(id: String) = apiCall { api.holdLayaway(id) }
    suspend fun layaway(id: String) = apiCall { api.layaway(id) }
    suspend fun createLayaway(request: CreateLayawayRequest) = apiCall { api.createLayaway(request) }
    suspend fun payLayaway(id: String, deposit: LayawayDeposit) = apiCall { api.payLayaway(id, deposit) }
    suspend fun completeLayaway(id: String, request: CompleteLayawayRequest) = apiCall { api.completeLayaway(id, request) }
    suspend fun cancelLayaway(id: String, reason: String?) = apiCall { api.cancelLayaway(id, CancelRequest(reason?.takeIf { it.isNotBlank() })) }

    suspend fun giftCards(status: String? = null, q: String? = null, page: Int = 1) = apiCall { api.giftCards(status, q?.takeIf { it.isNotBlank() }, page) }
    suspend fun checkGiftCard(code: String) = apiCall { api.checkGiftCard(code.trim()) }
    suspend fun issueGiftCard(request: IssueGiftCardRequest) = apiCall { api.issueGiftCard(request) }
    suspend fun redeemGiftCard(code: String, customerId: String) = apiCall { api.redeemGiftCard(RedeemGiftCardRequest(code.trim(), customerId)) }
    suspend fun cancelGiftCard(id: String) = apiCall { api.cancelGiftCard(id) }
}

/** The customer app from the owner's side: settings, store page, assistant, campaigns, behaviour. */
class CustomerAppRepository(private val api: AdminApi) {
    suspend fun appSettings() = apiCall { api.storeApp() }
    suspend fun setAppSettings(s: StoreAppSettings) = apiCall { api.setStoreApp(s) }
    suspend fun about() = apiCall { api.storeAbout() }
    suspend fun setAbout(s: StoreAboutSettings) = apiCall { api.setStoreAbout(s) }

    /** The server addresses the customer app tries, in order. */
    suspend fun appServers() = apiCall { api.appServers() }
    suspend fun setAppServers(servers: List<String>) = apiCall { api.setAppServers(AppServersRequest(servers)) }
    suspend fun syncAppServers() = apiCall { api.syncAppServers() }

    suspend fun chatSettings() = apiCall { api.chatSettings() }
    suspend fun setChatSettings(s: ChatSettingsRequest) = apiCall { api.setChatSettings(s) }
    suspend fun testChat() = apiCall { api.testChat() }
    suspend fun conversations() = apiCall { api.chatConversations() }
    suspend fun conversation(customerId: String) = apiCall { api.chatConversation(customerId) }

    suspend fun previewCampaign(segment: Segment) = apiCall { api.campaignPreview(CampaignPreviewRequest(segment)) }
    suspend fun sendCampaign(request: CampaignRequest) = apiCall { api.sendCampaign(request) }
    suspend fun campaigns() = apiCall { api.campaigns() }

    suspend fun behavior(from: String? = null, to: String? = null) = apiCall { api.behavior(from, to) }
    suspend fun customerUsage(customerId: String) = apiCall { api.customerUsage(customerId) }
}

/** Quantity offers, pre-orders and the AI helpers (round 6). */
class OffersRepository(private val api: AdminApi) {
    suspend fun deals(activeOnly: Boolean = false) = apiCall { api.deals(if (activeOnly) true else null) }
    suspend fun createDeal(request: DealRequest) = apiCall { api.createDeal(request) }
    suspend fun updateDeal(id: String, request: DealRequest) = apiCall { api.updateDeal(id, request) }
    suspend fun deleteDeal(id: String) = apiCall { api.deleteDeal(id).let { if (!it.isSuccessful) throw retrofit2.HttpException(it) } }

    /** The offers a till basket gets, before payment. */
    suspend fun posQuote(items: List<PosItem>, customerId: String?) = apiCall { api.posQuote(PosQuoteRequest(items, customerId)) }

    suspend fun preOrders(status: String? = null, productId: String? = null) = apiCall { api.preOrders(status, productId) }
    suspend fun incoming() = apiCall { api.incoming() }
    suspend fun createPreOrder(request: CreatePreOrderRequest) = apiCall { api.createPreOrder(request) }
    suspend fun cancelPreOrder(id: String) = apiCall { api.cancelPreOrder(id) }
    suspend fun convertPreOrder(id: String) = apiCall { api.convertPreOrder(id) }
    suspend fun updatePreOrder(id: String, request: UpdatePreOrderRequest) = apiCall { api.updatePreOrder(id, request) }
    suspend fun preOrderArrived(id: String) = apiCall { api.preOrderArrived(id) }
    suspend fun checkArrivals() = apiCall { api.checkPreOrderArrivals() }

    suspend fun productDraft(imageFileId: String) = apiCall { api.productDraft(ProductDraftRequest(imageFileId)) }
    suspend fun marketingText(idea: String, channel: String) = apiCall { api.marketingText(MarketingTextRequest(idea.trim(), channel)) }
    suspend fun weeklySummary(refresh: Boolean = false) = apiCall { api.weeklySummary(refresh) }
}
