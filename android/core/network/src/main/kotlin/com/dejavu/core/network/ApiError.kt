package com.dejavu.core.network

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException
import java.io.IOException

/** A failed API call, with the server's human-readable message(s). */
class ApiException(
    val status: Int,
    override val message: String,
    /** Per-line issues reported by checkout, e.g. "DJV-1: Only 2 left in stock". */
    val problems: List<String> = emptyList(),
    cause: Throwable? = null,
) : Exception(message, cause) {
    val isUnauthorized get() = status == 401
    val isNetwork get() = status == NETWORK

    companion object {
        const val NETWORK = -1

        const val NOT_AN_API =
            "الرابط يعرض صفحة ويب وليس خادم Dejavu. إن كان من GitHub Codespaces فاجعل المنفذ 3000 عاماً (Public) وتأكد أن الخادم يعمل."

        fun from(t: Throwable): ApiException = when (t) {
            is ApiException -> t
            is HttpException -> parse(t.code(), t.response()?.errorBody()?.string(), t)
            is IOException -> ApiException(NETWORK, "تعذّر الاتصال بالخادم. تحقق من الإنترنت ثم حاول مرة أخرى.", cause = t)
            // A web page instead of JSON: usually a login/sign-in page in front of the server,
            // e.g. a private GitHub Codespaces port.
            is SerializationException -> if (t.message.orEmpty().contains("<")) {
                ApiException(0, NOT_AN_API, cause = t)
            } else {
                ApiException(0, "استجابة غير متوقعة من الخادم", cause = t)
            }
            else -> ApiException(0, t.message ?: "حدث خطأ غير متوقع", cause = t)
        }

        /**
         * NestJS errors look like {"statusCode":400,"message":"..."} or
         * {"message":["phone must be ...", ...]} or {"message":"Cart needs attention","problems":[...]}.
         */
        fun parse(status: Int, body: String?, cause: Throwable? = null): ApiException {
            val json = runCatching { ApiJson.parseToJsonElement(body ?: "") as? JsonObject }.getOrNull()
            val message = when (val m = json?.get("message")) {
                is JsonPrimitive -> m.content
                is JsonArray -> m.joinToString("\n") { it.jsonPrimitive.content }
                else -> null
            } ?: defaultMessage(status)
            val problems = (json?.get("problems") as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
            return ApiException(status, message, problems, cause)
        }

        private fun defaultMessage(status: Int) = when (status) {
            401 -> "انتهت الجلسة، سجّل الدخول مرة أخرى"
            403 -> "غير مسموح بهذه العملية"
            404 -> "غير موجود"
            in 500..599 -> "الخادم غير متاح حالياً، حاول لاحقاً"
            else -> "تعذّر إتمام الطلب"
        }
    }
}

/** Runs an API call and turns every failure into [ApiException]. */
suspend fun <T> apiCall(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (t: Throwable) {
        Result.failure(ApiException.from(t))
    }
