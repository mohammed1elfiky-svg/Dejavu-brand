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

    suspend fun chatSettings() = apiCall { api.chatSettings() }
    suspend fun setChatSettings(s: ChatSettingsRequest) = apiCall { api.setChatSettings(s) }
    suspend fun conversations() = apiCall { api.chatConversations() }
    suspend fun conversation(customerId: String) = apiCall { api.chatConversation(customerId) }

    suspend fun previewCampaign(segment: Segment) = apiCall { api.campaignPreview(CampaignPreviewRequest(segment)) }
    suspend fun sendCampaign(request: CampaignRequest) = apiCall { api.sendCampaign(request) }
    suspend fun campaigns() = apiCall { api.campaigns() }

    suspend fun behavior(from: String? = null, to: String? = null) = apiCall { api.behavior(from, to) }
    suspend fun customerUsage(customerId: String) = apiCall { api.customerUsage(customerId) }
}
