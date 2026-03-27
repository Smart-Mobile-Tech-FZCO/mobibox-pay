package io.mobibox.pay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Processes payments and polls transaction status via the Mobibox API.
 *
 * Purchase endpoint: `POST {checkoutHost}/api/v1/processing/purchase/card`
 * Status  endpoint: `POST {checkoutHost}/api/v1/payment/status`
 */
internal class MobiPayPaymentProcessor(
    private val checkoutHost: String,
    private val merchantKey: String,
    private val password: String,
    private val client: OkHttpClient = OkHttpClient(),
) {

    // ── Purchase ─────────────────────────────────────────────────────────

    suspend fun processPayment(
        sessionToken: String,
        cardholderName: String,
        email: String? = null,
        billingAddress: Map<String, Any?>? = null,
        browserInfo: Map<String, Any?>? = null,
        birthDay: String? = null,
        selectedLanguage: String? = null,
    ): PaymentProcessingResponse = withContext(Dispatchers.IO) {

        val body = JSONObject().apply {
            put("name", cardholderName)
            put("with_hosted_fields", true)
            email?.let { put("email", it) }
            billingAddress?.let { put("billing_address", JSONObject(it)) }
            browserInfo?.let { put("browser_info", JSONObject(it)) }
            birthDay?.let { put("birth_day", it) }
            selectedLanguage?.let { put("selected_language", it) }
        }

        val request = Request.Builder()
            .url("$checkoutHost/api/v1/processing/purchase/card")
            .addHeader("Content-Type", "application/json")
            .addHeader("Token", sessionToken)
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()
            ?: throw MobiPayException("Empty response from payment API")

        if (!response.isSuccessful) {
            val errorMsg = try {
                JSONObject(responseBody).optString("error_message", "Payment processing failed")
            } catch (_: Exception) {
                "Payment processing failed (HTTP ${response.code})"
            }
            throw MobiPayException(errorMsg)
        }

        PaymentProcessingResponse.fromJson(JSONObject(responseBody))
    }

    // ── Transaction status ───────────────────────────────────────────────

    suspend fun getTransactionStatus(paymentId: String): TransactionStatusResponse =
        withContext(Dispatchers.IO) {

            val hash = MobiPayHash.generateTransactionStatusHash(
                paymentId = paymentId,
                password = password,
            )

            val body = JSONObject().apply {
                put("merchant_key", merchantKey)
                put("payment_id", paymentId)
                put("hash", hash)
            }

            val request = Request.Builder()
                .url("$checkoutHost/api/v1/payment/status")
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
                ?: throw MobiPayException("Empty response from status API")

            if (!response.isSuccessful) {
                val errorMsg = try {
                    JSONObject(responseBody).optString(
                        "error_message",
                        "Failed to get transaction status",
                    )
                } catch (_: Exception) {
                    "Failed to get transaction status (HTTP ${response.code})"
                }
                throw MobiPayException(errorMsg)
            }

            TransactionStatusResponse.fromJson(JSONObject(responseBody))
        }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

// ── Response models ──────────────────────────────────────────────────────

/**
 * Response from `POST /api/v1/processing/purchase/card`.
 *
 * Possible [result] values: `success`, `decline`, `redirect`, `waiting`, `undefined`.
 */
data class PaymentProcessingResponse(
    val result: String,
    val redirectUrl: String?,
    val redirectParams: List<Map<String, Any?>>?,
    val returnUrlQueryParams: Map<String, Any?>?,
    val declineMessage: String?,
    val publicId: String?,
) {
    val requiresPostRedirect: Boolean
        get() = !redirectParams.isNullOrEmpty()

    companion object {
        fun fromJson(json: JSONObject): PaymentProcessingResponse {
            val redirectParams: MutableList<Map<String, Any?>>? =
                json.optJSONArray("redirect_params")?.let { arr ->
                    (0 until arr.length()).map { i -> arr.getJSONObject(i).toMap() }.toMutableList()
                } ?: json.optJSONObject("redirect_params")?.let { obj ->
                    mutableListOf(obj.toMap())
                }

            val returnUrlQueryParams = json.optJSONObject("return_url_query_params")?.toMap()

            return PaymentProcessingResponse(
                result = json.optString("result", "undefined"),
                redirectUrl = json.optString("redirect_url").takeIf { it.isNotEmpty() },
                redirectParams = redirectParams,
                returnUrlQueryParams = returnUrlQueryParams,
                declineMessage = json.optString("decline_message").takeIf { it.isNotEmpty() },
                publicId = json.optString("public_id").takeIf { it.isNotEmpty() },
            )
        }
    }
}

/**
 * Response from `POST /api/v1/payment/status`.
 *
 * Possible [status] values: `prepare`, `settled`, `pending`, `3ds`, `redirect`,
 * `decline`, `refund`, `reversal`, `void`, `chargeback`.
 */
data class TransactionStatusResponse(
    val status: String?,
    val paymentId: String?,
    val order: Map<String, Any?>?,
    val date: String?,
    val reason: String?,
    val customer: Map<String, Any?>?,
) {
    val isSuccess get() = status == "settled"
    val isFailed get() = status == "decline"
    val isProcessing get() = status == "prepare" || status == "3ds"
    val requiresRedirect get() = status == "redirect"
    val isPending get() = status == "pending"

    val statusDescription: String
        get() = when (status) {
            "prepare" -> "Payment is being prepared"
            "settled" -> "Payment completed successfully"
            "pending" -> "Payment is pending"
            "3ds" -> "3D Secure authentication in progress"
            "redirect" -> "Redirect required"
            "decline" -> "Payment declined"
            "refund" -> "Payment refunded"
            "reversal" -> "Payment reversed"
            "void" -> "Payment voided"
            "chargeback" -> "Chargeback received"
            else -> status ?: "Unknown status"
        }

    companion object {
        fun fromJson(json: JSONObject) = TransactionStatusResponse(
            status = json.optString("status").takeIf { it.isNotEmpty() },
            paymentId = json.optString("payment_id").takeIf { it.isNotEmpty() },
            order = json.optJSONObject("order")?.toMap(),
            date = json.optString("date").takeIf { it.isNotEmpty() },
            reason = json.optString("reason").takeIf { it.isNotEmpty() },
            customer = json.optJSONObject("customer")?.toMap(),
        )
    }
}

// ── JSONObject helpers ───────────────────────────────────────────────────

internal fun JSONObject.toMap(): Map<String, Any?> {
    val map = mutableMapOf<String, Any?>()
    keys().forEach { key ->
        map[key] = when (val value = opt(key)) {
            is JSONObject -> value.toMap()
            is org.json.JSONArray -> (0 until value.length()).map { value.opt(it) }
            JSONObject.NULL -> null
            else -> value
        }
    }
    return map
}
